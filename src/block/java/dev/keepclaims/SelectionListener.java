package dev.keepclaims;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Golden shovel and stick, GriefPrevention style.
 *
 * <ul>
 *   <li><b>Golden shovel, left click</b> a block: first corner of a selection.</li>
 *   <li><b>Golden shovel, right click</b> a block: second corner. The price menu opens
 *       automatically, showing the adjusted price for exactly those blocks.</li>
 *   <li><b>Golden shovel, sneak + right click</b> your own claim: removes that land, free.</li>
 *   <li><b>Stick, right click</b> a block: tell the player who owns it.</li>
 * </ul>
 *
 * While a first corner is set, a particle outline is drawn on the ground around the
 * current rectangle so the player can see what they are about to claim.
 */
public final class SelectionListener implements Listener {
    private final KeepClaims plugin;

    /** First corner per player, waiting for the second. */
    private final Map<UUID, Location> corners = new ConcurrentHashMap<>();
    /** Players whose pending selection will REMOVE land instead of adding it. */
    private final Set<UUID> removing = ConcurrentHashMap.newKeySet();
    /** Players whose particle preview task is running. */
    private final Map<UUID, BukkitRunnable> previews = new ConcurrentHashMap<>();

    public SelectionListener(KeepClaims plugin) {
        this.plugin = plugin;
    }

    public boolean hasCorner(Player p) {
        return corners.containsKey(p.getUniqueId());
    }

    public Location corner(Player p) {
        return corners.get(p.getUniqueId());
    }

    /** Forgets the pending corner and stops the preview. */
    public void cancel(Player p) {
        corners.remove(p.getUniqueId());
        removing.remove(p.getUniqueId());
        BukkitRunnable r = previews.remove(p.getUniqueId());
        if (r != null) {
            r.cancel();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Material inHand = e.getItem() == null ? null : e.getItem().getType();
        if (inHand == null) {
            return;
        }
        ClaimManager cm = plugin.claims();

        // ------------------------------------------------------------- stick
        if (inHand == Material.STICK) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
                return;
            }
            e.setCancelled(true);
            Claim c = cm.at(e.getClickedBlock());
            if (c == null) {
                plugin.send(p, "stick-unclaimed");
                return;
            }
            if (c.isServer()) {
                plugin.send(p, "stick-server");
                return;
            }
            plugin.send(p, "stick-info",
                    "owner", c.ownerName,
                    "size", String.valueOf(c.blocks()),
                    "dims", cm.sizeLine(c),
                    "status", stripTags(cm.statusLine(c)));
            return;
        }

        // ----------------------------------------------------- golden shovel
        if (inHand != Material.GOLDEN_SHOVEL) {
            return;
        }
        if (!p.hasPermission("materialclaim.use")) {
            return;
        }
        if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_AIR) {
            // Clicking at the sky cancels a pending selection, so a mis-aimed first corner
            // is easy to get out of.
            if (hasCorner(p)) {
                cancel(p);
                plugin.send(p, "selection-cancelled");
            }
            return;
        }
        if (e.getAction() == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null) {
            e.setCancelled(true);
            Location l = e.getClickedBlock().getLocation();
            if (!cm.worldEnabled(l.getWorld())) {
                plugin.send(p, "world-disabled");
                return;
            }
            boolean remove = p.isSneaking();
            Claim own = cm.at(l);
            if (remove && (own == null || own.isServer() || !p.getUniqueId().equals(own.owner))) {
                plugin.send(p, "shovel-remove-not-yours");
                return;
            }
            if (remove) {
                removing.add(p.getUniqueId());
            } else {
                removing.remove(p.getUniqueId());
            }
            corners.put(p.getUniqueId(), l);
            startPreview(p, l);
            if (remove) {
                plugin.send(p, "selection-first-remove",
                        "x", String.valueOf(l.getBlockX()),
                        "z", String.valueOf(l.getBlockZ()));
            } else {
                plugin.send(p, "selection-first",
                        "x", String.valueOf(l.getBlockX()),
                        "z", String.valueOf(l.getBlockZ()));
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
            return;
        }
        e.setCancelled(true);
        Location second = e.getClickedBlock().getLocation();

        Location pendingFirst = corners.get(p.getUniqueId());
        if (pendingFirst == null && p.isSneaking()) {
            Claim own = cm.at(second);
            if (own != null && !own.isServer() && p.getUniqueId().equals(own.owner)) {
                plugin.gui().openUnclaim(p, own);
                return;
            }
        }
        if (pendingFirst == null) {
            // Right click with nothing selected starts a selection too, like GP.
            corners.put(p.getUniqueId(), second);
            startPreview(p, second);
            plugin.send(p, "selection-first",
                    "x", String.valueOf(second.getBlockX()),
                    "z", String.valueOf(second.getBlockZ()));
            return;
        }
        if (pendingFirst.getWorld() == null || !pendingFirst.getWorld().equals(second.getWorld())) {
            cancel(p);
            plugin.send(p, "selection-other-world");
            return;
        }
        boolean removeMode = removing.contains(p.getUniqueId());
        // Capture BOTH corners BEFORE cancel() clears the stored state. Reading the corner
        // after cancel() is what threw the first corner away.
        Location first = pendingFirst;
        cancel(p);
        Area area = Area.of(second.getWorld().getUID(),
                first.getBlockX(), first.getBlockZ(),
                second.getBlockX(), second.getBlockZ());
        if (removeMode) {
            // Shrinking is free and never refunds. Only land the player owns is removed.
            Claim own = cm.at(second);
            if (own == null || own.isServer() || !p.getUniqueId().equals(own.owner)) {
                plugin.send(p, "shovel-remove-not-yours");
                return;
            }
            plugin.gui().openRemove(p, own, area);
            return;
        }
        plugin.gui().openSelection(p, area);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer());
    }

    // ---------------------------------------------------------------- preview

    private void startPreview(Player p, Location corner) {
        // Restart ONLY the particle task. This used to call cancel(p), which also cleared
        // the corner that was just stored, so the first click was thrown away instantly and
        // the second click started a new selection instead of finishing one. That made it
        // impossible to complete a selection.
        BukkitRunnable previous = previews.remove(p.getUniqueId());
        if (previous != null) {
            previous.cancel();
        }
        BukkitRunnable r = new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline() || !hasCorner(p)) {
                    cancel();
                    previews.remove(p.getUniqueId());
                    return;
                }
                Location target = targetOf(p, corner);
                if (target == null) {
                    return;
                }
                drawOutline(p, Area.of(corner.getWorld().getUID(),
                        corner.getBlockX(), corner.getBlockZ(),
                        target.getBlockX(), target.getBlockZ()));
            }
        };
        previews.put(p.getUniqueId(), r);
        r.runTaskTimer(plugin, 1L, 10L);
    }

    /**
     * The corner the preview grows towards: the block the player is looking at, so the
     * rectangle follows the cursor until they click the second corner.
     */
    private Location targetOf(Player p, Location corner) {
        var found = p.getTargetBlockExact(48);
        if (found != null) {
            return found.getLocation();
        }
        Location eye = p.getEyeLocation();
        World w = eye.getWorld();
        if (w == null) {
            return null;
        }
        // No block in range: project 8 blocks forward on the ground plane.
        double dx = eye.getDirection().getX();
        double dz = eye.getDirection().getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            return null;
        }
        dx /= len;
        dz /= len;
        int x = (int) Math.floor(corner.getX() + dx * 8);
        int z = (int) Math.floor(corner.getZ() + dz * 8);
        int y = corner.getWorld().getHighestBlockYAt(x, z);
        return new Location(corner.getWorld(), x, y, z);
    }

    /** Draws the rectangle edges with particles at ground level. */
    public void drawOutline(Player p, Area a) {
        World w = Bukkit.getWorld(a.world());
        if (w == null) {
            return;
        }
        int step = Math.max(1, Math.max(a.width(), a.depth()) / 32);
        List<int[]> pts = new ArrayList<>();
        for (int x = a.minX(); x <= a.maxX(); x += step) {
            pts.add(new int[]{x, a.minZ()});
            pts.add(new int[]{x, a.maxZ()});
        }
        for (int z = a.minZ(); z <= a.maxZ(); z += step) {
            pts.add(new int[]{a.minX(), z});
            pts.add(new int[]{a.maxX(), z});
        }
        for (int[] pt : pts) {
            if (!w.isChunkLoaded(pt[0] >> 4, pt[1] >> 4)) {
                continue;
            }
            double y = w.getHighestBlockYAt(pt[0], pt[1]) + 1.0;
            p.spawnParticle(Particle.HAPPY_VILLAGER, pt[0] + 0.5, y, pt[1] + 0.5, 1, 0, 0, 0, 0);
        }
    }

    /** Removes MiniMessage tags, for text that is re-inserted into a message. */
    private static String stripTags(String s) {
        return s.replaceAll("<[^>]*>", "");
    }

    /** Stops every preview task, for plugin disable. */
    public void shutdown() {
        for (BukkitRunnable r : previews.values()) {
            r.cancel();
        }
        previews.clear();
        corners.clear();
    }
}
