package dev.keepclaims;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws territory borders and selection outlines with particles.
 *
 * Only the EDGE of a rectangle is drawn, never a fill, so a large claim does not spam
 * particles across every block. The default particle is END_ROD: bright white, no fall
 * and visible from a long way off.
 *
 * Configuration (config.yml):
 * <pre>
 * borders:
 *   show-seconds: 30
 *   particle: END_ROD
 *   density: 2
 * </pre>
 */
public final class BorderRenderer {
    /** Task drawing a timed border, per player, so asking again restarts rather than stacks. */
    private final Map<UUID, BukkitRunnable> shown = new ConcurrentHashMap<>();
    /** Task drawing the live selection outline, per player. */
    private final Map<UUID, BukkitRunnable> previews = new ConcurrentHashMap<>();

    private final KeepClaims plugin;

    public BorderRenderer(KeepClaims plugin) {
        this.plugin = plugin;
    }

    private Particle particle() {
        String raw = plugin.getConfig().getString("borders.particle", "END_ROD");
        try {
            Particle p = Particle.valueOf(raw == null ? "END_ROD" : raw.trim().toUpperCase());
            // Guard against particles that need extra data; fall back to a safe one.
            if (p.getDataType() == Void.class) {
                return p;
            }
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Unknown borders.particle '" + raw + "', using END_ROD.");
        }
        return Particle.END_ROD;
    }

    /**
     * Blocks between particles along an edge. 3 keeps the line readable while emitting far
     * fewer points than one per block; the old chunk-based outline used roughly this
     * spacing and looked right.
     */
    private int spacing() {
        int spacing = plugin.getConfig().getInt("borders.spacing", 3);
        if (!plugin.getConfig().isSet("borders.spacing")
                && plugin.getConfig().isSet("borders.density")) {
            // Backwards compatibility with the 0.4.2 'density' key (particles per block).
            int density = Math.max(1, plugin.getConfig().getInt("borders.density", 1));
            spacing = Math.max(1, (int) Math.round(1.0 / density));
        }
        return Math.max(1, Math.min(16, spacing));
    }

    /**
     * Upper bound on particles per redraw. A huge claim would otherwise emit thousands of
     * points every half second, which is what made the outline look like a cloud while the
     * player was moving.
     */
    private int maxPoints() {
        return Math.max(24, plugin.getConfig().getInt("borders.max-points", 240));
    }

    public int showSeconds() {
        return Math.max(1, plugin.getConfig().getInt("borders.show-seconds", 30));
    }

    /**
     * Shows one territory's border for the configured number of seconds. Asking again
     * restarts the timer instead of drawing a second, overlapping outline.
     */
    public void showClaim(Player p, Claim c) {
        Area bounds = c.bounds();
        if (bounds == null) {
            return;
        }
        List<Area> shapes = new ArrayList<>(c.areas);
        int seconds = showSeconds();

        BukkitRunnable previous = shown.remove(p.getUniqueId());
        if (previous != null) {
            previous.cancel();
        }
        BukkitRunnable task = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline() || ticks++ >= seconds * 20) {
                    cancel();
                    shown.remove(p.getUniqueId(), this);
                    return;
                }
                for (Area a : shapes) {
                    if (a.world().equals(p.getWorld().getUID())) {
                        drawEdges(p, a);
                    }
                }
            }
        };
        shown.put(p.getUniqueId(), task);
        task.runTaskTimer(plugin, 0L, 10L);
    }

    /** Starts (or restarts) the live outline that follows the cursor while selecting. */
    /**
     * Live outline that follows the cursor while the player picks the second corner.
     *
     * Redrawing stops once the rectangle has not changed for a moment. While the player
     * moves, each redraw leaves a fresh generation of particles behind, and redrawing a
     * static rectangle over and over just stacks those generations on top of each other
     * until the line looks like a cloud. Settling when the shape stops changing keeps the
     * outline crisp, and it wakes up again the moment the cursor moves to a new block.
     */
    public void startPreview(Player p, Area corner) {
        stopPreview(p);
        BukkitRunnable task = new BukkitRunnable() {
            private Area last = null;
            private int idle = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    previews.remove(p.getUniqueId());
                    return;
                }
                Area target = followCursor(p, corner);
                if (target == null) {
                    return;
                }
                if (!target.equals(last)) {
                    // The selection changed: draw it once and reset the idle counter.
                    last = target;
                    idle = 0;
                    drawEdges(p, target);
                    return;
                }
                idle++;
                // Refresh only occasionally while idle, so the line stays visible without
                // piling up. The particle lifetime is about a second, so half a second is
                // enough to look continuous.
                if (idle % 2 == 0) {
                    drawEdges(p, target);
                }
            }
        };
        previews.put(p.getUniqueId(), task);
        task.runTaskTimer(plugin, 1L, 10L);
    }

    public void stopPreview(Player p) {
        BukkitRunnable r = previews.remove(p.getUniqueId());
        if (r != null) {
            r.cancel();
        }
    }

    public void stopAll(Player p) {
        stopPreview(p);
        BukkitRunnable r = shown.remove(p.getUniqueId());
        if (r != null) {
            r.cancel();
        }
    }

    public void shutdown() {
        for (BukkitRunnable r : previews.values()) {
            r.cancel();
        }
        previews.clear();
        for (BukkitRunnable r : shown.values()) {
            r.cancel();
        }
        shown.clear();
    }

    /**
     * The rectangle from the stored corner to whatever the player is looking at, so the
     * outline grows with the cursor until the second corner is clicked.
     */
    private Area followCursor(Player p, Area corner) {
        var found = p.getTargetBlockExact(64);
        int x;
        int z;
        if (found != null) {
            x = found.getX();
            z = found.getZ();
        } else {
            var eye = p.getEyeLocation();
            double dx = eye.getDirection().getX();
            double dz = eye.getDirection().getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) {
                return null;
            }
            x = (int) Math.floor(corner.minX() + dx / len * 8);
            z = (int) Math.floor(corner.minZ() + dz / len * 8);
        }
        return Area.of(corner.world(), corner.minX(), corner.minZ(), x, z);
    }

    /**
     * Draws the four edges of one rectangle.
     *
     * Only the perimeter gets particles, placed every `borders.spacing` blocks (default 3)
     * at a single height above the ground. Drawing one layer instead of two and spacing the
     * points out is what keeps the line clean while the player moves and the outline is
     * redrawn: fewer new particles per redraw means far fewer overlapping generations.
     */
    public void drawEdges(Player p, Area a) {
        World w = p.getWorld();
        if (w == null || !a.world().equals(w.getUID())) {
            return;
        }
        Particle particle = particle();
        int spacing = spacing();
        int cap = maxPoints();
        double y = p.getLocation().getY() + 0.4;
        int emitted = 0;

        // X edges (constant Z).
        for (int z : new int[]{a.minZ(), a.maxZ()}) {
            for (int x = a.minX(); x <= a.maxX() && emitted < cap; x += spacing) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                emit(p, particle, x + 0.5, groundY(w, x, z, y), z + 0.5);
                emitted++;
            }
        }
        // Z edges (constant X).
        for (int x : new int[]{a.minX(), a.maxX()}) {
            for (int z = a.minZ(); z <= a.maxZ() && emitted < cap; z += spacing) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                emit(p, particle, x + 0.5, groundY(w, x, z, y), z + 0.5);
                emitted++;
            }
        }
        // The far corner is easy to miss when the loop steps over it; always mark it.
        if (emitted < cap) {
            emit(p, particle, a.maxX() + 0.5,
                    groundY(w, a.maxX(), a.maxZ(), y), a.maxZ() + 0.5);
        }
    }

    /** Ground height at that column, but never below the player's feet. */
    private double groundY(World w, int x, int z, double fallback) {
        int top = w.getHighestBlockYAt(x, z);
        return Math.max(top + 1.0, fallback);
    }

    private void emit(Player p, Particle particle, double x, double y, double z) {
        try {
            p.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
        } catch (RuntimeException ex) {
            // A particle that needs data would throw here; never let that break the task.
            plugin.getLogger().warning("Particle " + particle + " could not be drawn: " + ex.getMessage());
        }
    }
}
