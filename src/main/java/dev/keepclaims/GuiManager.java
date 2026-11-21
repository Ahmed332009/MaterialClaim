package dev.keepclaims;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All chest-style menus. Actions are stored per slot as short strings like "pay:24". */
public final class GuiManager implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    enum Type { MAIN, PAY, TRUST_ADD, TRUST_LIST, MY_CLAIMS, UNCLAIM }

    static final class MenuHolder implements InventoryHolder {
        final Type type;
        final ChunkKey key;
        final Map<Integer, String> actions = new HashMap<>();
        Inventory inv;

        MenuHolder(Type type, ChunkKey key) {
            this.type = type;
            this.key = key;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final KeepClaims plugin;

    public GuiManager(KeepClaims plugin) {
        this.plugin = plugin;
    }

    // ----------------------------------------------------------------- helpers

    private Component mm(String s) {
        return MM.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private String esc(String s) {
        return MM.escapeTags(s);
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm(name));
        if (lore != null && !lore.isEmpty()) {
            List<Component> list = new ArrayList<>();
            for (String s : lore) {
                list.add(mm(s));
            }
            meta.lore(list);
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack item(Material m, String name, String... lore) {
        return item(m, name, List.of(lore));
    }

    private ItemStack glow(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack head(OfflinePlayer op, String name, String... lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(op);
        meta.displayName(mm(name));
        List<Component> list = new ArrayList<>();
        for (String s : lore) {
            list.add(mm(s));
        }
        meta.lore(list);
        it.setItemMeta(meta);
        return it;
    }

    private Inventory create(MenuHolder h, int size, String title) {
        Inventory inv = Bukkit.createInventory(h, size, MM.deserialize(title));
        h.inv = inv;
        return inv;
    }

    private void set(Inventory inv, MenuHolder h, int slot, ItemStack it, String action) {
        inv.setItem(slot, it);
        if (action != null) {
            h.actions.put(slot, action);
        }
    }

    private void fillEmpty(Inventory inv) {
        ItemStack f = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = f.getItemMeta();
        meta.displayName(Component.text(" "));
        f.setItemMeta(meta);
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, f);
            }
        }
    }

    private String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString().substring(0, 8);
    }

    private boolean isOwner(Player p, Claim c) {
        return c != null && p.getUniqueId().equals(c.owner);
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    // ---------------------------------------------------------------- main menu

    public void openMain(Player p) {
        ClaimManager cm = plugin.claims();
        PaymentService pay = plugin.payments();
        ChunkKey key = cm.keyOf(p.getLocation());
        Claim c = cm.get(key);
        MenuHolder h = new MenuHolder(Type.MAIN, key);
        Inventory inv = create(h, 27, "<dark_green><bold>Land Claims");

        List<String> info = new ArrayList<>();
        info.add("<gray>World: <white>" + esc(cm.worldName(key)));
        if (c == null) {
            info.add("<green>Unclaimed");
        } else {
            info.add("<gray>Owner: <white>" + esc(c.isServer() ? "Server" : c.ownerName));
            info.add("<gray>Status: " + cm.statusLine(c));
            info.add("<gray>Trusted players: <white>" + c.trusted.size());
        }
        set(inv, h, 4, item(Material.PAPER, "<gold>Chunk " + key.x() + ", " + key.z(), info), null);

        if (c == null) {
            List<Claim> mine = cm.byOwner(p.getUniqueId());
            double mult = cm.multiplierFor(mine.size() + 1);
            // The charge is clamped to the prepaid cap, so show the clamped price.
            double maxInitial = cm.maxPrepaidHours();
            double initial = Math.min(plugin.getConfig().getDouble("initial-hours", 168), maxInitial);
            double raw = initial * mult;
            Material choice = pay.choiceOf(p);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Claims used: <white>" + mine.size() + "/" + cm.limitFor(p));
            lore.add("<gray>Paying with: <white>" + (choice == null ? "cheapest items first" : Util.pretty(choice)));
            lore.add("<gray>Cost, pick one kind of item:");
            lore.addAll(pay.costLines(raw));
            lore.add("<yellow>Click to claim");
            set(inv, h, 10, item(Material.GRASS_BLOCK, "<green>Claim this chunk", lore), "claim");
        } else if (isOwner(p, c)) {
            set(inv, h, 10, item(Material.DIAMOND, "<aqua>Pay upkeep",
                    "<gray>Add time to this claim.", "<yellow>Click to open"), "pay_menu");
            set(inv, h, 11, item(Material.PLAYER_HEAD, "<green>Trusted players",
                    "<gray>Choose who can build here.", "<yellow>Click to open"), "trust_menu");
            set(inv, h, 13, item(Material.TNT, "<red>Unclaim",
                    "<gray>Gives up this chunk.", "<gray>Upkeep is not refunded."), "unclaim_menu");
        }
        set(inv, h, 12, item(Material.SPYGLASS, "<yellow>Show chunk borders",
                "<gray>Particles show this chunk for a few seconds."), "borders");
        set(inv, h, 14, item(Material.BOOK, "<gold>My claims", "<gray>See all your claims and their time."), "my_claims");
        set(inv, h, 22, item(Material.BARRIER, "<red>Close"), "close");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    // ----------------------------------------------------------------- pay menu

    public void openPay(Player p, ChunkKey key) {
        ClaimManager cm = plugin.claims();
        PaymentService pay = plugin.payments();
        Claim c = cm.get(key);
        if (!isOwner(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        double mult = cm.multiplierFor(cm.ordinalOf(c));
        Material choice = pay.choiceOf(p);
        MenuHolder h = new MenuHolder(Type.PAY, key);
        Inventory inv = create(h, 36, "<dark_green><bold>Pay upkeep");

        set(inv, h, 4, item(Material.CLOCK, "<gold>Chunk " + key.x() + ", " + key.z(),
                "<gray>Status: " + cm.statusLine(c),
                "<gray>Paying with: <white>" + (choice == null ? "cheapest items first" : Util.pretty(choice))), null);

        // How much upkeep this claim can still take before the prepaid cap.
        double room = cm.roomHours(c);
        infoAddRoom(inv, room);

        double[] hours = {24, 72, 168, 336, 0};
        String[] names = {"+1 day", "+3 days", "+7 days", "+14 days", "Fill to maximum"};
        for (int i = 0; i < hours.length; i++) {
            List<String> lore = new ArrayList<>();
            // Never advertise more than the claim can actually hold: the charge is
            // shrunk to fit, so showing the unclamped cost would overstate the price.
            double want = hours[i] > 0 ? Math.min(hours[i], room) : room;
            if (hours[i] > 0 && want + 1e-9 < hours[i]) {
                lore.add("<red>Only " + Util.duration((long) (want * 3_600_000L))
                        + " of room left on this claim.");
            }
            if (want <= 1e-9) {
                lore.add("<red>No room left for more upkeep.");
            } else if (choice != null) {
                int n = (int) Math.ceil(want * mult / pay.valueOf(choice) - 1e-9);
                lore.add("<gray>Cost: <white>" + Math.max(1, n) + "x " + Util.pretty(choice));
                lore.add("<gray>You have: <white>" + pay.count(p, choice));
            } else {
                lore.add("<gray>Cost, pick one kind of item:");
                lore.addAll(pay.costLines(want * mult));
            }
            lore.add("<yellow>Click to pay");
            String action = hours[i] > 0 ? "pay:" + (int) hours[i] : "pay:max";
            set(inv, h, 10 + i, item(Material.GOLD_INGOT, "<green>" + names[i], lore), action);
        }

        int slot = 18;
        ItemStack auto = item(Material.HOPPER, "<white>Automatic",
                "<gray>Uses your cheapest accepted items first.", choice == null ? "<green>Selected" : "<yellow>Click to select");
        set(inv, h, slot++, choice == null ? glow(auto) : auto, "choose:AUTO");
        for (Map.Entry<Material, Double> e : pay.values().entrySet()) {
            if (slot > 31) {
                break;
            }
            Material m = e.getKey();
            ItemStack it = item(m, "<white>" + Util.pretty(m),
                    "<gray>Worth: <white>" + trim(e.getValue() / mult) + " hours <gray>of upkeep here",
                    "<gray>You have: <white>" + pay.count(p, m),
                    m == choice ? "<green>Selected" : "<yellow>Click to pay with this item");
            set(inv, h, slot++, m == choice ? glow(it) : it, "choose:" + m.name());
        }
        set(inv, h, 35, item(Material.ARROW, "<yellow>Back"), "main");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    /** Adds the remaining-room line to the item already sitting in the info slot. */
    private void infoAddRoom(Inventory inv, double roomHours) {
        ItemStack it = inv.getItem(4);
        if (it == null || it.getItemMeta() == null) {
            return;
        }
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(mm("<gray>Room left: <white>" + Util.duration((long) (roomHours * 3_600_000L))));
        meta.lore(lore);
        it.setItemMeta(meta);
        inv.setItem(4, it);
    }

    private String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    // ------------------------------------------------------------- trust menus

    public void openTrustAdd(Player p, ChunkKey key) {
        ClaimManager cm = plugin.claims();
        Claim c = cm.get(key);
        if (!isOwner(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.TRUST_ADD, key);
        Inventory inv = create(h, 54, "<dark_green><bold>Trust a player");
        int slot = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(p.getUniqueId()) || c.trusted.contains(o.getUniqueId())) {
                continue;
            }
            if (slot >= 45) {
                break;
            }
            set(inv, h, slot++, head(o, "<green>" + esc(o.getName()), "<gray>Click to let them build here"),
                    "trust:" + o.getUniqueId());
        }
        set(inv, h, 45, item(Material.ARROW, "<yellow>Back"), "main");
        set(inv, h, 49, item(Material.BOOK, "<gold>Trusted players (" + c.trusted.size() + ")",
                "<gray>See and remove trusted players."), "trust_list");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    public void openTrustList(Player p, ChunkKey key) {
        ClaimManager cm = plugin.claims();
        Claim c = cm.get(key);
        if (!isOwner(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.TRUST_LIST, key);
        Inventory inv = create(h, 54, "<dark_green><bold>Trusted players");
        int slot = 0;
        for (UUID id : c.trusted) {
            if (slot >= 45) {
                break;
            }
            set(inv, h, slot++, head(Bukkit.getOfflinePlayer(id), "<green>" + esc(nameOf(id)),
                    "<red>Click to remove"), "untrust:" + id);
        }
        set(inv, h, 49, item(Material.ARROW, "<yellow>Back"), "trust_menu");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------- unclaim, my claims

    public void openUnclaim(Player p, ChunkKey key) {
        Claim c = plugin.claims().get(key);
        if (!isOwner(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.UNCLAIM, key);
        Inventory inv = create(h, 27, "<dark_red><bold>Unclaim this chunk?");
        set(inv, h, 11, item(Material.RED_CONCRETE, "<red>Yes, unclaim",
                "<gray>Chunk " + key.x() + ", " + key.z(), "<gray>Remaining upkeep is lost."), "unclaim_confirm");
        set(inv, h, 15, item(Material.LIME_CONCRETE, "<green>No, keep it"), "main");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    public void openMyClaims(Player p) {
        ClaimManager cm = plugin.claims();
        MenuHolder h = new MenuHolder(Type.MY_CLAIMS, null);
        Inventory inv = create(h, 54, "<dark_green><bold>My claims");
        List<Claim> list = cm.byOwner(p.getUniqueId());
        long warn = (long) (plugin.getConfig().getDouble("warn-days", 3) * 86_400_000L);
        int slot = 0;
        for (Claim c : list) {
            if (slot >= 45) {
                break;
            }
            long left = cm.remainingMs(c);
            Material icon = left > warn ? Material.LIME_CONCRETE : left > 0 ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE;
            set(inv, h, slot++, item(icon, "<gold>Chunk " + c.key.x() + ", " + c.key.z(),
                    "<gray>World: <white>" + esc(cm.worldName(c.key)),
                    "<gray>Blocks: <white>" + (c.key.x() * 16) + ", " + (c.key.z() * 16),
                    "<gray>Status: " + cm.statusLine(c),
                    "<gray>Trusted: <white>" + c.trusted.size()), null);
        }
        set(inv, h, 49, item(Material.ARROW, "<yellow>Back"), "main");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    // ----------------------------------------------------------------- borders

    public void showBorders(Player p, int cx, int cz) {
        final double minX = cx * 16.0;
        final double maxX = minX + 16.0;
        final double minZ = cz * 16.0;
        final double maxZ = minZ + 16.0;
        new BukkitRunnable() {
            int runs = 0;

            @Override
            public void run() {
                if (!p.isOnline() || runs++ >= 16) {
                    cancel();
                    return;
                }
                double y = p.getLocation().getY();
                for (double d = 0; d <= 16; d += 1) {
                    for (double dy = 0; dy <= 2; dy += 1) {
                        p.spawnParticle(Particle.END_ROD, minX + d, y + dy, minZ, 1, 0, 0, 0, 0);
                        p.spawnParticle(Particle.END_ROD, minX + d, y + dy, maxZ, 1, 0, 0, 0, 0);
                        p.spawnParticle(Particle.END_ROD, minX, y + dy, minZ + d, 1, 0, 0, 0, 0);
                        p.spawnParticle(Particle.END_ROD, maxX, y + dy, minZ + d, 1, 0, 0, 0, 0);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder h)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) {
            return;
        }
        String action = h.actions.get(e.getRawSlot());
        if (action != null) {
            handle(p, h, action);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) {
            e.setCancelled(true);
        }
    }

    private void handle(Player p, MenuHolder h, String action) {
        ClaimManager cm = plugin.claims();
        String[] parts = action.split(":", 2);
        String arg = parts.length > 1 ? parts[1] : "";
        ChunkKey key = h.key;

        switch (parts[0]) {
            case "close" -> p.closeInventory();
            case "main" -> later(() -> openMain(p));
            case "claim" -> {
                cm.claimHere(p);
                later(() -> openMain(p));
            }
            case "pay_menu" -> later(() -> openPay(p, key));
            case "pay" -> {
                double hours = arg.equals("max") ? 0 : Double.parseDouble(arg);
                cm.payHere(p, key, hours);
                later(() -> openPay(p, key));
            }
            case "choose" -> {
                if (arg.equals("AUTO")) {
                    plugin.payments().setChoice(p, null);
                } else {
                    plugin.payments().setChoice(p, Material.matchMaterial(arg));
                }
                later(() -> {
                    if (h.type == Type.PAY) {
                        openPay(p, key);
                    }
                });
            }
            case "trust_menu" -> later(() -> openTrustAdd(p, key));
            case "trust_list" -> later(() -> openTrustList(p, key));
            case "trust" -> {
                UUID id = UUID.fromString(arg);
                cm.trust(p, key, id, nameOf(id));
                later(() -> openTrustAdd(p, key));
            }
            case "untrust" -> {
                UUID id = UUID.fromString(arg);
                cm.untrust(p, key, id, nameOf(id));
                later(() -> openTrustList(p, key));
            }
            case "unclaim_menu" -> later(() -> openUnclaim(p, key));
            case "unclaim_confirm" -> {
                cm.unclaim(p, key);
                p.closeInventory();
            }
            case "borders" -> {
                p.closeInventory();
                showBorders(p, key.x(), key.z());
            }
            case "my_claims" -> later(() -> openMyClaims(p));
            default -> {
            }
        }
    }
}
