package dev.keepclaims;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

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
    /** Players already shown the shovel hint this session. */
    private final Set<UUID> holdingHints = ConcurrentHashMap.newKeySet();
    /**
     * Staff who ran /claims admin serverclaim and are now picking a rectangle with the
     * netherite shovel. Completing the rectangle creates server land, not player land.
     */
    private final Set<UUID> serverSelecting = ConcurrentHashMap.newKeySet();

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
        serverSelecting.remove(p.getUniqueId());
        plugin.borders().stopPreview(p);
    }

    /** True when this player is picking server land with the admin shovel. */
    public boolean isServerSelecting(Player p) {
        return serverSelecting.contains(p.getUniqueId());
    }

    /**
     * Hands the staff member the selection shovel and switches them into server mode.
     * Called by /claims admin serverclaim.
     */
    public void beginServerSelection(Player p) {
        cancel(p);
        serverSelecting.add(p.getUniqueId());
        giveAdminShovel(p);
        plugin.send(p, "admin-server-select-start");
    }

    /** Builds the netherite selection shovel. Its identity is what the listener matches. */
    private static org.bukkit.inventory.ItemStack adminShovel(KeepClaims plugin) {
        org.bukkit.inventory.ItemStack item =
                new org.bukkit.inventory.ItemStack(Material.NETHERITE_SHOVEL);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.mm("<aqua><bold>Server Claim Tool"));
            meta.lore(java.util.List.of(
                    plugin.mm("<gray>Left click: first corner"),
                    plugin.mm("<gray>Right click: second corner"),
                    plugin.mm("<gray>Right click the air to cancel"),
                    plugin.mm("<dark_gray>Creates server land. Costs nothing.")));
            // No enchantment glint on purpose: enchantment constants moved to a registry
            // in 1.21 and the display name is enough to identify the tool.
            item.setItemMeta(meta);
        }
        return item;
    }

    private void giveAdminShovel(Player p) {
        org.bukkit.inventory.ItemStack item = adminShovel(plugin);
        // Put it in the hotbar, replacing whatever is in hand, so it is obvious and ready.
        int slot = p.getInventory().getHeldItemSlot();
        org.bukkit.inventory.ItemStack previous = p.getInventory().getItem(slot);
        if (previous != null && previous.getType() != Material.AIR) {
            // Do not destroy anything the player was holding.
            var leftover = p.getInventory().addItem(previous);
            for (var left : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        p.getInventory().setItem(slot, item);
    }

    /** Removes the admin shovel from the whole inventory, wherever it ended up. */
    private void takeAdminShovel(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            org.bukkit.inventory.ItemStack it = inv.getItem(i);
            if (isAdminShovel(it)) {
                inv.setItem(i, null);
            }
        }
    }

    /** True when the item is the server-claim shovel, matched by its display name. */
    private boolean isAdminShovel(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_SHOVEL) {
            return false;
        }
        var meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return false;
        }
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(meta.displayName()).trim();
        return plain.equals(SHOVEL_NAME);
    }

    private static final String SHOVEL_NAME = "Server Claim Tool";

    @EventHandler
    public void onHold(PlayerItemHeldEvent e) {
        var inv = e.getPlayer().getInventory();
        var item = inv.getItem(e.getNewSlot());
        if (item != null && item.getType() == Material.GOLDEN_SHOVEL) {
            hint(e.getPlayer());
            return;
        }
        // Switching away from the shovel stops the cursor outline. Without this the preview
        // task kept running and redrawing the rectangle wherever the player was looking,
        // even while they held something else entirely.
        plugin.borders().stopPreview(e.getPlayer());
        // Dropping out of the admin tool also ends server mode, so the next selection is not
        // silently treated as server land.
        if (serverSelecting.remove(e.getPlayer().getUniqueId()) && hasCorner(e.getPlayer())) {
            cancel(e.getPlayer());
            plugin.send(e.getPlayer(), "admin-server-select-cancelled");
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

        // ------------------------------------------------ admin server shovel
        if (isAdminShovel(e.getItem())) {
            e.setCancelled(true);
            if (!p.hasPermission("materialclaim.admin")) {
                // Promotion dropped: do not let a non-admin keep the tool.
                takeAdminShovel(p);
                plugin.send(p, "no-permission");
                return;
            }
            serverSelecting.add(p.getUniqueId());
            if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_AIR) {
                // Cancel out of server mode and take the tool back.
                boolean had = hasCorner(p);
                cancel(p);
                takeAdminShovel(p);
                plugin.send(p, had ? "selection-cancelled" : "admin-server-select-cancelled");
                return;
            }
            if (e.getAction() == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null) {
                if (hasCorner(p)) {
                    plugin.send(p, "admin-server-select-have-corner");
                    return;
                }
                Location l = e.getClickedBlock().getLocation();
                if (!cm.worldEnabled(l.getWorld())) {
                    plugin.send(p, "world-disabled");
                    return;
                }
                corners.put(p.getUniqueId(), l);
                startPreview(p, l);
                plugin.send(p, "selection-first",
                        "x", String.valueOf(l.getBlockX()),
                        "z", String.valueOf(l.getBlockZ()));
                return;
            }
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
                Location second = e.getClickedBlock().getLocation();
                Location pendingFirst = corners.get(p.getUniqueId());
                if (pendingFirst == null) {
                    // Right click with nothing selected starts the selection, like the gold shovel.
                    corners.put(p.getUniqueId(), second);
                    startPreview(p, second);
                    plugin.send(p, "selection-first",
                            "x", String.valueOf(second.getBlockX()),
                            "z", String.valueOf(second.getBlockZ()));
                    return;
                }
                if (pendingFirst.getWorld() == null
                        || !pendingFirst.getWorld().equals(second.getWorld())) {
                    cancel(p);
                    plugin.send(p, "selection-other-world");
                    return;
                }
                // Both corners captured BEFORE cancel() clears the stored state.
                Location first = pendingFirst;
                cancel(p);
                Area area = Area.of(second.getWorld().getUID(),
                        first.getBlockX(), first.getBlockZ(),
                        second.getBlockX(), second.getBlockZ());
                // The tool is consumed by a successful selection, whatever the outcome.
                takeAdminShovel(p);
                cm.serverClaimArea(p, area);
                return;
            }
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
            // Right clicking the air is the border command: inside your own land it shows
            // the outline, and it also cancels a half-made selection so nobody gets stuck.
            if (hasCorner(p)) {
                cancel(p);
                plugin.send(p, "selection-cancelled");
                return;
            }
            if (e.getAction() == Action.RIGHT_CLICK_AIR) {
                showBorder(p);
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

    /**
     * Right click with the shovel in the air. If the player stands in their own land, the
     * border is shown for the configured time; otherwise it tells them how to claim.
     */
    private void showBorder(Player p) {
        ClaimManager cm = plugin.claims();
        Claim c = cm.at(p.getLocation());
        if (c == null) {
            plugin.send(p, "border-none");
            plugin.send(p, "shovel-hint-wild");
            return;
        }
        if (!c.isServer() && p.getUniqueId().equals(c.owner)) {
            plugin.borders().showClaim(p, c);
            plugin.send(p, "border-shown",
                    "name", c.displayName(),
                    "seconds", String.valueOf(plugin.borders().showSeconds()));
            plugin.send(p, "shovel-hint-own");
            return;
        }
        // Someone else's land, or server land: show it, but say whose it is.
        plugin.borders().showClaim(p, c);
        plugin.send(p, "border-shown",
                "name", c.isServer() ? "the server" : c.ownerName + "'s claim",
                "seconds", String.valueOf(plugin.borders().showSeconds()));
    }

    /**
     * One-time hint the first time a player holds the shovel, so the tool explains itself.
     * Shown at most once per session per player.
     */
    public void hint(Player p) {
        if (!holdingHints.add(p.getUniqueId())) {
            return;
        }
        Claim c = plugin.claims().at(p.getLocation());
        boolean own = c != null && !c.isServer() && p.getUniqueId().equals(c.owner);
        plugin.send(p, own ? "shovel-hint-own" : "shovel-hint-wild");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer());
        holdingHints.remove(e.getPlayer().getUniqueId());
        serverSelecting.remove(e.getPlayer().getUniqueId());
    }

    /** Leaving the world would otherwise leave the outline drawing in the old world. */
    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        cancel(e.getPlayer());
    }

    /** Dying with a half-made selection leaves a stale outline behind. */
    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        cancel(e.getPlayer());
    }

    // ---------------------------------------------------------------- preview

    private void startPreview(Player p, Location corner) {
        // The renderer restarts only its own particle task; it must never touch the stored
        // corner, which is what broke claiming in 0.4.0.
        plugin.borders().startPreview(p, Area.of(corner.getWorld().getUID(),
                corner.getBlockX(), corner.getBlockZ(),
                corner.getBlockX(), corner.getBlockZ()));
    }

    /** Removes MiniMessage tags, for text that is re-inserted into a message. */
    private static String stripTags(String s) {
        return s.replaceAll("<[^>]*>", "");
    }

    /** Stops every preview task, for plugin disable. */
    public void shutdown() {
        corners.clear();
        removing.clear();
    }
}
