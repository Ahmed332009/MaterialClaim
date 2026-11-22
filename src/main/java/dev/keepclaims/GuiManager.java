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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** All chest-style menus. Actions are stored per slot as short strings like "pay:24". */
public final class GuiManager implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    enum Type { MAIN, PAY, TRUST_ADD, TRUST_LIST, MY_CLAIMS, UNCLAIM, SETTINGS, PVP_SCOPE, MEMBER }

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

    /** Players waiting to type a territory name or message in chat. */
    private static final Map<UUID, PendingText> PENDING = new ConcurrentHashMap<>();

    record PendingText(ChunkKey key, SettingsService.TextInput field) {
    }

    public GuiManager(KeepClaims plugin) {
        this.plugin = plugin;
    }

    /** True when the player is currently expected to type a value in chat. */
    public static boolean hasPendingText(UUID id) {
        return PENDING.containsKey(id);
    }

    /** Called by the chat listener with the typed line. Returns false when nothing was pending. */
    public boolean completeText(Player p, String message) {
        PendingText pending = PENDING.remove(p.getUniqueId());
        if (pending == null) {
            return false;
        }
        if (message != null && message.trim().equalsIgnoreCase("cancel")) {
            plugin.send(p, "text-cancelled");
            return true;
        }
        Claim c = plugin.claims().get(pending.key());
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return true;
        }
        plugin.claims().setText(p, c, pending.field(), message);
        return true;
    }

    public void cancelText(UUID id) {
        PENDING.remove(id);
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

    /** Owner, manager or admin of a player territory: may pay upkeep. */
    private boolean mayPay(Player p, Claim c) {
        return c != null && !c.isServer() && plugin.settings().canManage(p, c);
    }

    /** Owner or admin of a player territory: may manage trusted players and unclaim. */
    private boolean mayAdmin(Player p, Claim c) {
        return c != null && !c.isServer() && plugin.settings().isOwnerOrAdmin(p, c);
    }

    private UUID parseId(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Claim claimAt(Player p, ChunkKey key) {
        return plugin.claims().get(key);
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
            info.add("<gray>Territory: <white>" + c.size() + " chunk(s)");
            info.add("<gray>Trusted players: <white>" + c.trusted.size());
        }
        set(inv, h, 4, item(Material.PAPER, "<gold>Chunk " + key.x() + ", " + key.z(), info), null);

        if (c == null) {
            Set<Claim> adjacent = cm.adjacentOwned(p.getUniqueId(), key);
            int sizeAfter = 1;
            for (Claim a : adjacent) {
                sizeAfter += a.size();
            }
            double raw = plugin.getConfig().getDouble("initial-hours", 96);
            Material choice = pay.choiceOf(p);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Territory size after: <white>" + sizeAfter + " chunk(s)");
            lore.add("<gray>Chunks used: <white>" + cm.ownedChunks(p.getUniqueId()) + "/" + cm.limitFor(p));
            lore.add("<gray>Paying with: <white>" + (choice == null ? "cheapest items first" : Util.pretty(choice)));
            lore.add("<gray>Price, pick one kind of item:");
            lore.addAll(pay.costLines(raw));
            lore.add("<yellow>Click to claim");
            String title = adjacent.isEmpty() ? "<green>Claim this chunk" : "<green>Add this chunk to your territory";
            set(inv, h, 10, item(Material.GRASS_BLOCK, title, lore), "claim");
        } else {
            SettingsService svc = plugin.settings();
            if (mayPay(p, c)) {
                set(inv, h, 10, item(Material.DIAMOND, "<aqua>Pay upkeep",
                        "<gray>Add time to this territory.", "<yellow>Click to open"), "pay_menu");
            }
            if (mayAdmin(p, c)) {
                set(inv, h, 11, item(Material.PLAYER_HEAD, "<green>Trusted players",
                        "<gray>Choose who can build or manage here.", "<yellow>Click to open"), "trust_menu");
                set(inv, h, 13, item(Material.TNT, "<red>Unclaim",
                        "<gray>Gives up this chunk.", "<gray>Upkeep is not refunded."), "unclaim_menu");
            }
            if (svc.canManage(p, c)) {
                set(inv, h, 15, item(Material.COMPARATOR, "<gold>Settings",
                        "<gray>Protections and visitor rules.",
                        "<gray>Paid protections: <white>" + c.settings.activePaidCount(),
                        "<yellow>Click to open"), "settings");
            }
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
        if (!mayPay(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        double mult = cm.rate(c);
        Material choice = pay.choiceOf(p);
        MenuHolder h = new MenuHolder(Type.PAY, key);
        Inventory inv = create(h, 36, "<dark_green><bold>Pay upkeep");

        set(inv, h, 4, item(Material.CLOCK, "<gold>Territory of " + c.size() + " chunk(s)",
                "<gray>Status: " + cm.statusLine(c),
                "<gray>Payment covers the whole territory.",
                "<gray>Paying with: <white>" + (choice == null ? "cheapest items first" : Util.pretty(choice))), null);

        double[] hours = {24, 72, 168, 336, 0};
        String[] names = {"+1 day", "+3 days", "+7 days", "+14 days", "Fill to maximum"};
        for (int i = 0; i < hours.length; i++) {
            List<String> lore = new ArrayList<>();
            if (hours[i] > 0 && choice != null) {
                int n = (int) Math.ceil(hours[i] * mult / pay.valueOf(choice) - 1e-9);
                lore.add("<gray>Cost: <white>" + Math.max(1, n) + "x " + Util.pretty(choice));
                lore.add("<gray>You have: <white>" + pay.count(p, choice));
            } else if (hours[i] > 0) {
                lore.add("<gray>Cost, pick one kind of item:");
                lore.addAll(pay.costLines(hours[i] * mult));
            } else {
                lore.add("<gray>Pays up to the maximum prepaid time.");
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
                    "<gray>Worth: <white>" + trim(e.getValue() / mult) + " hours <gray>for this territory",
                    "<gray>You have: <white>" + pay.count(p, m),
                    m == choice ? "<green>Selected" : "<yellow>Click to pay with this item");
            set(inv, h, slot++, m == choice ? glow(it) : it, "choose:" + m.name());
        }
        set(inv, h, 35, item(Material.ARROW, "<yellow>Back"), "main");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    private String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    // ------------------------------------------------------------- trust menus

    public void openTrustAdd(Player p, ChunkKey key) {
        ClaimManager cm = plugin.claims();
        Claim c = cm.get(key);
        if (!mayAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.TRUST_ADD, key);
        Inventory inv = create(h, 54, "<dark_green><bold>Trust a player");
        int slot = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(p.getUniqueId()) || c.trusted.has(o.getUniqueId())) {
                continue;
            }
            if (slot >= 45) {
                break;
            }
            set(inv, h, slot++, head(o, "<green>" + esc(o.getName()),
                    "<gray>Click to trust as Builder",
                    "<gray>They can build and break blocks."),
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
        if (!mayAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.TRUST_LIST, key);
        Inventory inv = create(h, 54, "<dark_green><bold>Trusted players");
        int slot = 0;
        for (UUID id : c.trusted.ids()) {
            if (slot >= 45) {
                break;
            }
            TrustList.Level level = c.trusted.levelOf(id);
            set(inv, h, slot++, head(Bukkit.getOfflinePlayer(id),
                    "<green>" + esc(nameOf(id)),
                    "<gray>Level: <white>" + level.id,
                    "<gray>Builder: build and use everything.",
                    "<gray>Manager: also pays upkeep, buys protections",
                    "<gray>and changes free settings.",
                    "<yellow>Click to change level or remove"), "member:" + id);
        }
        set(inv, h, 49, item(Material.ARROW, "<yellow>Back"), "trust_menu");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    /** Sub-menu for one trusted player: pick Builder or Manager, or remove them. */
    public void openMember(Player p, ChunkKey key, UUID id) {
        Claim c = plugin.claims().get(key);
        if (!mayAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        TrustList.Level current = c.trusted.levelOf(id);
        MenuHolder h = new MenuHolder(Type.MEMBER, key);
        Inventory inv = create(h, 27, "<dark_green><bold>" + esc(nameOf(id)));
        set(inv, h, 4, head(Bukkit.getOfflinePlayer(id), "<green>" + esc(nameOf(id)),
                "<gray>Level: <white>" + current.id), null);
        ItemStack builder = item(Material.IRON_PICKAXE, "<white>Builder",
                "<gray>Can build, break and use everything here.",
                current == TrustList.Level.BUILDER ? "<green>Selected" : "<yellow>Click to select");
        set(inv, h, 11, current == TrustList.Level.BUILDER ? glow(builder) : builder,
                "setlevel:" + id + ":BUILDER");
        ItemStack manager = item(Material.WRITABLE_BOOK, "<white>Manager",
                "<gray>Builder, plus: pays upkeep, buys paid protections",
                "<gray>and changes free settings. Cannot change who is trusted.",
                current == TrustList.Level.MANAGER ? "<green>Selected" : "<yellow>Click to select");
        set(inv, h, 13, current == TrustList.Level.MANAGER ? glow(manager) : manager,
                "setlevel:" + id + ":MANAGER");
        set(inv, h, 15, item(Material.BARRIER, "<red>Remove",
                "<gray>They lose all access to this territory."), "removemember:" + id);
        set(inv, h, 22, item(Material.ARROW, "<yellow>Back"), "trust_list");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------- unclaim, my claims

    public void openUnclaim(Player p, ChunkKey key) {
        Claim c = plugin.claims().get(key);
        if (!mayAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.UNCLAIM, key);
        Inventory inv = create(h, 27, "<dark_red><bold>Unclaim this chunk?");
        set(inv, h, 11, item(Material.RED_CONCRETE, "<red>Yes, unclaim",
                "<gray>Chunk " + key.x() + ", " + key.z(), "<gray>Blocked if it would split your territory.",
                "<gray>No refund is given."), "unclaim_confirm");
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
            ChunkKey first = c.chunks.iterator().next();
            long left = cm.remainingMs(c);
            Material icon = left > warn ? Material.LIME_CONCRETE : left > 0 ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE;
            set(inv, h, slot++, item(icon, "<gold>Territory of " + c.size() + " chunk(s)",
                    "<gray>World: <white>" + esc(cm.worldName(first)),
                    "<gray>Includes chunk: <white>" + first.x() + ", " + first.z()
                            + " <gray>(blocks " + (first.x() * 16) + ", " + (first.z() * 16) + ")",
                    "<gray>Status: " + cm.statusLine(c),
                    "<gray>Trusted: <white>" + c.trusted.size()), null);
        }
        set(inv, h, 49, item(Material.ARROW, "<yellow>Back"), "main");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    // ---------------------------------------------------------------- settings

    /** The Settings button on the main territory menu. */
    public void openSettings(Player p, ChunkKey key) {
        ClaimManager cm = plugin.claims();
        SettingsService svc = plugin.settings();
        Claim c = cm.get(key);
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        if (!svc.canManage(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.SETTINGS, key);
        Inventory inv = create(h, 54, "<dark_green><bold>Territory Settings");

        List<String> head = new ArrayList<>();
        head.add("<gray>Territory: <white>" + esc(c.displayName()));
        head.add("<gray>Chunks: <white>" + c.size());
        head.add("<gray>Paid protections ON: <white>" + c.settings.activePaidCount() + "<gray> of 4");
        head.add("<gray>Upkeep rate: <white>" + trim(cm.rate(c)) + " chunk-hours/h");
        set(inv, h, 4, item(Material.PAPER, "<gold>Settings", head), null);

        set(inv, h, 0, item(Material.RED_STAINED_GLASS_PANE, "<dark_red>Paid protections",
                "<gray>Buy once with items, then toggle for free.",
                "<gray>While ON, each one adds extra upkeep every cycle."), null);

        int slot = 9;
        for (SettingsService.Flag f : new SettingsService.Flag[]{
                SettingsService.Flag.EXPLOSIONS, SettingsService.Flag.PVP,
                SettingsService.Flag.MOB_SPAWNING, SettingsService.Flag.FIRE_SPREAD}) {
            set(inv, h, slot++, f == SettingsService.Flag.PVP ? pvpItem(p, c) : paidItem(p, c, f),
                    f == SettingsService.Flag.PVP ? "pvp_scope" : "flag:" + f.id);
        }

        set(inv, h, 18, item(Material.LIME_STAINED_GLASS_PANE, "<green>Free toggles",
                "<gray>No cost. Managers may change these."), null);
        slot = 19;
        for (SettingsService.Flag f : new SettingsService.Flag[]{
                SettingsService.Flag.VISITOR_DOORS, SettingsService.Flag.VISITOR_CONTAINERS,
                SettingsService.Flag.VISITOR_ANIMALS, SettingsService.Flag.MOB_GRIEFING,
                SettingsService.Flag.FLUID_FLOW, SettingsService.Flag.CROP_TRAMPLE}) {
            set(inv, h, slot++, freeItem(p, c, f), "flag:" + f.id);
        }

        set(inv, h, 45, item(Material.NAME_TAG, "<yellow>Territory name",
                "<gray>Current: <white>" + esc(c.settings.customName.isBlank() ? "(none)" : c.settings.customName),
                "<yellow>Click, then type the new name in chat"), "text:NAME");
        set(inv, h, 46, item(Material.OAK_SIGN, "<yellow>Entry message",
                "<gray>Current: <white>" + esc(c.settings.entryMessage.isBlank() ? "(none)" : c.settings.entryMessage),
                "<gray>Placeholders: {territory} {player}",
                "<yellow>Click, then type it in chat"), "text:ENTRY_MESSAGE");
        set(inv, h, 47, item(Material.OAK_SIGN, "<yellow>Leave message",
                "<gray>Current: <white>" + esc(c.settings.leaveMessage.isBlank() ? "(none)" : c.settings.leaveMessage),
                "<gray>Placeholders: {territory} {player}",
                "<yellow>Click, then type it in chat"), "text:LEAVE_MESSAGE");
        set(inv, h, 49, item(Material.ARROW, "<yellow>Back"), "main");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    /**
     * One paid protection button. The lore is rebuilt from scratch on every render and is
     * driven only by (purchased, enabled), so it can never show a stale buy prompt or an
     * outdated status after a click.
     */
    private ItemStack paidItem(Player p, Claim c, SettingsService.Flag f) {
        SettingsService svc = plugin.settings();
        boolean owned = c.settings.isPurchased(f.id);
        boolean on = c.settings.isEnabled(f.id);
        boolean locked = svc.isLocked(f) && !p.hasPermission("materialclaim.admin");

        Material icon = locked ? Material.BARRIER : on ? Material.RED_DYE : Material.LIME_DYE;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + f.description);
        lore.add("");
        if (!owned) {
            lore.add("<gray>Status: <yellow>Not purchased <gray>(off - vanilla behaviour)");
            if (svc.activationFee() <= 0) {
                lore.add("<gray>Cost: <green>free");
            } else {
                lore.add("<gray>Cost: <white>" + trim(svc.activationFee())
                        + " hours of upkeep items <gray>(one-time)");
            }
            lore.add("<gray>Extra upkeep: <white>+" + Math.round(svc.upkeepMultiplier() * 100)
                    + "% per cycle");
            lore.add("<yellow>Click to purchase and enable");
        } else if (on) {
            lore.add("<gray>Status: <green>ON (protecting)");
            lore.add("<gray>Purchased <gray>(free to toggle, non-refundable)");
            lore.add("<gray>Extra upkeep: <white>+" + Math.round(svc.upkeepMultiplier() * 100)
                    + "% per cycle");
            lore.add("<yellow>Click to turn OFF");
        } else {
            lore.add("<gray>Status: <yellow>OFF <gray>(vanilla behaviour)");
            lore.add("<gray>Purchased <gray>(free to toggle, non-refundable)");
            lore.add("<yellow>Click to turn ON");
        }
        if (locked) {
            lore.add("<red>Locked by the server admin.");
        }
        return item(icon, (on ? "<green>" : "<white>") + f.title, lore);
    }

    /** The PvP button: choose who may fight. Everyone means no protection. */
    private ItemStack pvpItem(Player p, Claim c) {
        SettingsService svc = plugin.settings();
        SettingsService.Flag f = SettingsService.Flag.PVP;
        boolean owned = c.settings.isPurchased(f.id);
        boolean on = c.settings.isEnabled(f.id);
        boolean locked = svc.isLocked(f) && !p.hasPermission("materialclaim.admin");

        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + f.description);
        lore.add("");
        if (!owned) {
            lore.add("<gray>Status: <yellow>Not purchased <gray>(everyone may fight)");
            if (svc.activationFee() <= 0) {
                lore.add("<gray>Cost: <green>free");
            } else {
                lore.add("<gray>Cost: <white>" + trim(svc.activationFee())
                        + " hours of upkeep items <gray>(one-time)");
            }
            lore.add("<gray>Extra upkeep: <white>+" + Math.round(svc.upkeepMultiplier() * 100)
                    + "% per cycle");
        } else if (on) {
            lore.add("<gray>Status: <green>ON (protecting) <gray>- may fight: <white>"
                    + c.settings.pvpScope.label());
            lore.add("<gray>Purchased <gray>(free to toggle, non-refundable)");
        } else {
            lore.add("<gray>Status: <yellow>OFF <gray>(everyone may fight)");
            lore.add("<gray>Purchased <gray>(free to toggle, non-refundable)");
        }
        lore.add("<yellow>Click to choose who may fight");
        if (locked) {
            lore.add("<red>Locked by the server admin.");
        }
        return item(on ? Material.RED_DYE : Material.LIME_DYE,
                (on ? "<green>" : "<white>") + f.title, lore);
    }

    private ItemStack freeItem(Player p, Claim c, SettingsService.Flag f) {
        SettingsService svc = plugin.settings();
        boolean on = svc.get(c, f);
        boolean locked = svc.isLocked(f) && !p.hasPermission("materialclaim.admin");
        Material icon = locked ? Material.BARRIER : on ? Material.LIME_DYE : Material.GRAY_DYE;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + f.description);
        lore.add("<gray>Status: <white>" + (on ? "Allowed" : "Blocked"));
        lore.add("<yellow>Click to toggle");
        if (locked) {
            lore.add("<red>Locked by the server admin.");
        }
        return item(icon, "<white>" + f.title, lore);
    }

    /** PvP scope sub-menu, only meaningful while PvP protection is active. */
    public void openPvpScope(Player p, ChunkKey key) {
        Claim c = plugin.claims().get(key);
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        if (!plugin.settings().canManage(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.PVP_SCOPE, key);
        Inventory inv = create(h, 27, "<dark_green><bold>PvP scope");
        boolean pvpOn = c.settings.isEnabled(SettingsService.Flag.PVP.id);
        set(inv, h, 4, item(Material.IRON_SWORD, "<gold>Who may PvP here",
                "<gray>Everyone = no protection (free).",
                "<gray>Anything else turns PvP protection on.",
                "<gray>Current: <white>"
                        + (pvpOn ? c.settings.pvpScope.label() : "everyone (no protection)")), null);
        int slot = 10;
        for (TerritorySettings.PvpScope scope : TerritorySettings.PvpScope.values()) {
            boolean cur = scope == TerritorySettings.PvpScope.EVERYONE
                    ? !pvpOn
                    : pvpOn && c.settings.pvpScope == scope;
            String name = scope == TerritorySettings.PvpScope.EVERYONE ? "everyone (no protection)" : scope.label();
            set(inv, h, slot++, item(cur ? Material.LIME_DYE : Material.GRAY_DYE,
                    (cur ? "<green>" : "<white>") + name,
                    cur ? "<green>Selected" : "<yellow>Click to select"), "scope:" + scope.name());
        }
        set(inv, h, 22, item(Material.ARROW, "<yellow>Back"), "settings");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    // ----------------------------------------------------------------- borders

    /** Shows the outline of the whole territory (or just this chunk if it is unclaimed). */
    public void showBorders(Player p, ChunkKey key) {
        Claim c = plugin.claims().get(key);
        Set<ChunkKey> set = c == null ? Set.of(key) : c.chunks;
        UUID worldId = p.getWorld().getUID();
        final List<double[]> edges = new ArrayList<>();
        for (ChunkKey k : set) {
            if (!k.world().equals(worldId)) {
                continue;
            }
            double x1 = k.x() * 16.0;
            double z1 = k.z() * 16.0;
            double x2 = x1 + 16.0;
            double z2 = z1 + 16.0;
            if (!set.contains(new ChunkKey(k.world(), k.x(), k.z() - 1))) {
                edges.add(new double[]{x1, z1, x2, z1});
            }
            if (!set.contains(new ChunkKey(k.world(), k.x(), k.z() + 1))) {
                edges.add(new double[]{x1, z2, x2, z2});
            }
            if (!set.contains(new ChunkKey(k.world(), k.x() - 1, k.z()))) {
                edges.add(new double[]{x1, z1, x1, z2});
            }
            if (!set.contains(new ChunkKey(k.world(), k.x() + 1, k.z()))) {
                edges.add(new double[]{x2, z1, x2, z2});
            }
        }
        new BukkitRunnable() {
            int runs = 0;

            @Override
            public void run() {
                if (!p.isOnline() || runs++ >= 16) {
                    cancel();
                    return;
                }
                double px = p.getLocation().getX();
                double py = p.getLocation().getY();
                double pz = p.getLocation().getZ();
                for (double[] e : edges) {
                    double cx = (e[0] + e[2]) / 2.0 - px;
                    double cz = (e[1] + e[3]) / 2.0 - pz;
                    if (cx * cx + cz * cz > 96.0 * 96.0) {
                        continue;
                    }
                    for (int i = 0; i <= 8; i++) {
                        double x = e[0] + (e[2] - e[0]) * i / 8.0;
                        double z = e[1] + (e[3] - e[1]) * i / 8.0;
                        for (double dy = 0; dy <= 2; dy += 1) {
                            p.spawnParticle(Particle.END_ROD, x, py + dy, z, 1, 0, 0, 0, 0);
                        }
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
            case "member" -> {
                UUID id = parseId(arg);
                if (id != null) {
                    later(() -> openMember(p, key, id));
                }
            }
            case "setlevel" -> {
                String[] bits = arg.split(":");
                UUID id = bits.length == 2 ? parseId(bits[0]) : null;
                if (id != null) {
                    cm.trust(p, key, id, nameOf(id), TrustList.Level.parse(bits[1]));
                    later(() -> openMember(p, key, id));
                }
            }
            case "removemember" -> {
                UUID id = parseId(arg);
                if (id != null) {
                    cm.untrust(p, key, id, nameOf(id));
                    later(() -> openTrustList(p, key));
                }
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
                showBorders(p, key);
            }
            case "my_claims" -> later(() -> openMyClaims(p));
            case "settings" -> later(() -> openSettings(p, key));
            case "pvp_scope" -> later(() -> openPvpScope(p, key));
            case "flag" -> {
                SettingsService.Flag flag = SettingsService.Flag.parse(arg);
                if (flag == null) {
                    return;
                }
                // PvP has its own sub-menu with every choice.
                if (flag == SettingsService.Flag.PVP) {
                    later(() -> openPvpScope(p, key));
                    return;
                }
                Claim c = cm.get(key);
                if (c == null) {
                    return;
                }
                // State first: flip the authoritative value, then persist it right away.
                // changeFlag() charges the one-time fee if needed and reports exactly this
                // same boolean, so chat and the re-rendered menu always agree.
                boolean newState = !plugin.settings().get(c, flag);
                if (cm.changeFlag(p, c, flag, newState)) {
                    cm.saveNow();
                }
                later(() -> openSettings(p, key));
            }
            case "scope" -> {
                Claim c = cm.get(key);
                if (c == null) {
                    return;
                }
                if (cm.setPvpScope(p, c, TerritorySettings.PvpScope.parse(arg))) {
                    cm.saveNow();
                }
                later(() -> openPvpScope(p, key));
            }
            case "text" -> {
                SettingsService.TextInput field;
                try {
                    field = SettingsService.TextInput.valueOf(arg);
                } catch (IllegalArgumentException ex) {
                    return;
                }
                Claim c = cm.get(key);
                if (c == null) {
                    return;
                }
                if (!plugin.settings().canManage(p, c)) {
                    plugin.send(p, "not-your-claim");
                    return;
                }
                PENDING.put(p.getUniqueId(), new PendingText(key, field));
                p.closeInventory();
                plugin.send(p, "text-prompt");
            }
            default -> {
            }
        }
    }
}
