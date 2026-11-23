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

    private int density() {
        return Math.max(1, Math.min(8, plugin.getConfig().getInt("borders.density", 2)));
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
    public void startPreview(Player p, Area corner) {
        stopPreview(p);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    previews.remove(p.getUniqueId());
                    return;
                }
                Area target = followCursor(p, corner);
                if (target != null) {
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
     * Draws the four edges of one rectangle. Only the perimeter gets particles, so cost is
     * proportional to the border length rather than the area.
     */
    public void drawEdges(Player p, Area a) {
        World w = p.getWorld();
        if (w == null || !a.world().equals(w.getUID())) {
            return;
        }
        Particle particle = particle();
        int density = density();
        // Ground level relative to the player: slightly above the player's feet so the line
        // is visible in front of them, and also at eye height for tall terrain.
        double y = p.getLocation().getY() + 0.4;
        double eyeY = p.getLocation().getY() + 1.6;

        // X edges (constant Z).
        for (int z : new int[]{a.minZ(), a.maxZ()}) {
            int span = a.width();
            int steps = Math.max(1, span * density);
            for (int i = 0; i <= steps; i++) {
                double x = a.minX() + (span - 1) * (double) i / steps;
                if (!w.isChunkLoaded((int) x >> 4, z >> 4)) {
                    continue;
                }
                double groundY = groundY(w, (int) x, z, y);
                emit(p, particle, x + 0.5, groundY, z + 0.5);
                if (eyeY > groundY + 1.2) {
                    emit(p, particle, x + 0.5, eyeY, z + 0.5);
                }
            }
        }
        // Z edges (constant X).
        for (int x : new int[]{a.minX(), a.maxX()}) {
            int span = a.depth();
            int steps = Math.max(1, span * density);
            for (int i = 0; i <= steps; i++) {
                double z = a.minZ() + (span - 1) * (double) i / steps;
                if (!w.isChunkLoaded(x >> 4, (int) z >> 4)) {
                    continue;
                }
                double groundY = groundY(w, x, (int) z, y);
                emit(p, particle, x + 0.5, groundY, z + 0.5);
                if (eyeY > groundY + 1.2) {
                    emit(p, particle, x + 0.5, eyeY, z + 0.5);
                }
            }
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
