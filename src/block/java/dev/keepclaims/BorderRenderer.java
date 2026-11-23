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

    /**
     * How many blocks tall the border wall is. The claim itself still covers the whole
     * vertical column; this only affects how tall the particles are drawn, so the border is
     * easy to see from any angle instead of being a flat line on the ground.
     */
    private int wallHeight() {
        return Math.max(1, Math.min(16, plugin.getConfig().getInt("borders.height", 3)));
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
                int height = wallHeight();
                for (Area a : shapes) {
                    if (a.world().equals(p.getWorld().getUID())) {
                        drawEdges(p, a, height);
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
                    drawEdges(p, target, 1);
                    return;
                }
                idle++;
                // Refresh only occasionally while idle, so the line stays visible without
                // piling up. The particle lifetime is about a second, so half a second is
                // enough to look continuous.
                if (idle % 2 == 0) {
                    drawEdges(p, target, 1);
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
     * Draws the four edges of one rectangle as a wall of particles.
     *
     * Only the perimeter is drawn, with one column of points every `borders.spacing` blocks
     * (default 3). Each column is `borders.height` blocks tall (default 3), so the border
     * reads as a wall you can see from any angle rather than a flat line on the ground.
     *
     * @param wallHeight how many blocks tall to stack each column
     */
    public void drawEdges(Player p, Area a, int wallHeight) {
        World w = p.getWorld();
        if (w == null || !a.world().equals(w.getUID())) {
            return;
        }
        Particle particle = particle();
        int spacing = spacing();
        int height = Math.max(1, wallHeight);
        // The cap counts columns, not individual particles, so a tall wall does not silently
        // thin the outline out. Total particles drawn is at most cap * height.
        int cap = Math.max(16, maxPoints() / height);
        double y = p.getLocation().getY() + 0.4;
        int columns = 0;

        // X edges (constant Z).
        for (int z : new int[]{a.minZ(), a.maxZ()}) {
            for (int x = a.minX(); x <= a.maxX() && columns < cap; x += spacing) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                emitColumn(p, particle, x, z, w, y, height);
                columns++;
            }
        }
        // Z edges (constant X).
        for (int x : new int[]{a.minX(), a.maxX()}) {
            for (int z = a.minZ(); z <= a.maxZ() && columns < cap; z += spacing) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                emitColumn(p, particle, x, z, w, y, height);
                columns++;
            }
        }
        // The far corner is easy to miss when the loop steps over it; always mark it.
        if (columns < cap) {
            emitColumn(p, particle, a.maxX(), a.maxZ(), w, y, height);
        }
    }

    /** Stacks one column of particles `height` blocks tall, starting just above the ground. */
    private void emitColumn(Player p, Particle particle, int x, int z, World w,
                            double fallbackY, int height) {
        double base = groundY(w, x, z, fallbackY);
        for (int i = 0; i < height; i++) {
            emit(p, particle, x + 0.5, base + i, z + 0.5);
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
