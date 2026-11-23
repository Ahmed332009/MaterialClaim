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
import java.util.concurrent.ConcurrentHashMap;

/** All chest-style menus. Actions are stored per slot as short strings like "pay:24". */
public final class GuiManager implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    enum Type { MAIN, PAY, TRUST_ADD, TRUST_LIST, MY_CLAIMS, UNCLAIM, SETTINGS, PVP_SCOPE, MEMBER,
        CONFIRM_BUY, SELECTION, CONFIRM_REMOVE }

    static final class MenuHolder implements InventoryHolder {
        final Type type;
        final Claim claim;
        /** Selection menus work on land that is not a territory yet. */
        final Area selection;
        final Map<Integer, String> actions = new HashMap<>();
        Inventory inv;

        MenuHolder(Type type, Claim claim) {
            this(type, claim, null);
        }

        MenuHolder(Type type, Claim claim, Area selection) {
            this.type = type;
            this.claim = claim;
            this.selection = selection;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final KeepClaims plugin;

    /** Players waiting to type a territory name or message in chat. */
    private static final Map<UUID, PendingText> PENDING = new ConcurrentHashMap<>();

    record PendingText(Claim claim, SettingsService.TextInput field) {
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
        plugin.claims().setText(p, pending.claim(), pending.field(), message);
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
        return MM.escapeTags(s == null ? "" : s);
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

    private UUID parseId(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /** Owner, manager or admin of a player territory: may pay upkeep. */
    private boolean mayPay(Player p, Claim c) {
        return c != null && !c.isServer() && plugin.settings().canManage(p, c);
    }

    /** Owner or admin of a player territory: may manage trusted players and unclaim. */
    private boolean mayAdmin(Player p, Claim c) {
        return c != null && !c.isServer() && plugin.settings().isOwnerOrAdmin(p, c);
    }

    private String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    // ------------------------------------------------------------ selection menu

    /**
     * The menu that opens automatically when the second shovel corner is clicked. It shows
     * the adjusted price for exactly the blocks that were selected, and what the territory
     * will become, before anything is charged.
     */
    public void openSelection(Player p, Area area) {
        ClaimManager cm = plugin.claims();
        ClaimManager.Quote q = cm.quote(p, area);
        MenuHolder h = new MenuHolder(Type.SELECTION, null, area);
        Inventory inv = create(h, 27, "<dark_green><bold>Claim this land?");

        Material choose = plugin.payments().choiceOf(p);
        List<String> info = new ArrayList<>();
        info.add("<gray>Selection: <white>" + area.width() + " x " + area.depth()
                + " <gray>blocks");
        info.add("<gray>Coordinates: <white>" + area.describe());
        info.add("<gray>Total blocks: <white>" + q.blocks());
        if (q.newBlocks() != q.blocks()) {
            info.add("<gray>Already yours: <white>" + (q.blocks() - q.newBlocks())
                    + " <gray>blocks (free)");
            info.add("<gray>New blocks: </gray><white>" + q.newBlocks());
        }
        info.add("<gray>Territory after: <white>" + q.blocksAfter() + " blocks");
        info.add("<gray>Upkeep rate: <white>"
                + trim(cm.rateAfter(q)) + " hours/h");
        set(inv, h, 4, item(Material.GRASS_BLOCK, "<green>Your selection", info), null);

        List<String> price = new ArrayList<>();
        price.add("<gray>Price: <white>" + trim(q.hours()) + " hours <gray>of upkeep");
        if (q.newBlocks() == 0) {
            price.add("<red>You already own all of this land.");
        } else {
            price.add("<gray>Paying with: <white>"
                    + (choose == null ? "cheapest items first" : Util.pretty(choose)));
            price.add("<gray>Pick one kind of item:");
            price.addAll(plugin.payments().costLines(q.hours()));
            price.add("<gray>Mode: <white>" + plugin.pricing().describeMode());
        }
        Material priceIcon = q.ok() ? Material.GOLD_INGOT : Material.BARRIER;
        set(inv, h, 11, item(priceIcon, q.ok() ? "<gold>Cost" : "<red>Cannot claim here", price), null);

        if (q.ok() && q.newBlocks() > 0) {
            set(inv, h, 15, item(Material.LIME_CONCRETE, "<green>Confirm claim",
                    "<gray>Charges the items listed and claims",
                    "<gray>the whole vertical column here.",
                    "<gray>No refunds, ever."), "sel_confirm");
        }
        set(inv, h, 22, item(Material.RED_CONCRETE, "<red>Cancel",
                "<gray>Nothing is charged."), "close");
        fillEmpty(inv);
        // Remember the quote so confirming cannot re-price against a changed inventory.
        QUOTES.put(p.getUniqueId(), q);
        p.openInventory(inv);
    }


    // ------------------------------------------------------------- remove menu

    /**
     * Confirmation for shrinking a territory with the shovel. Removing land is free and
     * never refunds anything; the upkeep clock is re-priced at the smaller size, so the
     * remaining time can only stay the same or grow slightly, never shrink.
     */
    public void openRemove(Player p, Claim c, Area area) {
        ClaimManager cm = plugin.claims();
        if (!mayAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        // Only the part of the rectangle that is actually this territory can go.
        List<Area> hit = new ArrayList<>();
        for (Area a : c.areas) {
            if (a.intersects(area)) {
                hit.add(a);
            }
        }
        if (hit.isEmpty()) {
            plugin.send(p, "no-claim-here");
            return;
        }
        MenuHolder h = new MenuHolder(Type.CONFIRM_REMOVE, c, area);
        Inventory inv = create(h, 27, "<dark_red><bold>Remove this land?");
        List<String> info = new ArrayList<>();
        info.add("<gray>Box: <white>" + area.width() + " x " + area.depth()
                + " (" + area.blocks() + " blocks)");
        info.add("<gray>Coordinates: <white>" + area.describe());
        info.add("<gray>Territory now: <white>" + cm.sizeLine(c));
        info.add("<gray>Upkeep left: <white>" + Util.duration(cm.remainingMs(c)));
        info.add("");
        info.add("<gray>Removing land is <white>free<gray>.");
        info.add("<red>No refund. Hours already paid are not returned.");
        set(inv, h, 4, item(Material.GRASS_BLOCK, "<red>Removing land", info), null);
        set(inv, h, 11, item(Material.RED_CONCRETE, "<red>Yes, remove this land",
                "<gray>Those blocks become unclaimed.",
                "<gray>Nothing is refunded."), "remove_confirm");
        set(inv, h, 15, item(Material.LIME_CONCRETE, "<green>No, keep it",
                "<gray>Nothing changes."), "close");
        fillEmpty(inv);
        p.openInventory(inv);
    }
    private static final Map<UUID, ClaimManager.Quote> QUOTES = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- main menu

    public void openMain(Player p) {
        ClaimManager cm = plugin.claims();
        Claim c = cm.at(p.getLocation());
        if (c == null) {
            openNoClaim(p);
            return;
        }
        openClaim(p, c);
    }

    /** Shown when the player stands on unclaimed land: explains the shovel. */
    public void openNoClaim(Player p) {
        ClaimManager cm = plugin.claims();
        MenuHolder h = new MenuHolder(Type.MAIN, null);
        Inventory inv = create(h, 27, "<dark_green><bold>Land Claims");
        List<String> info = new ArrayList<>();
        info.add("<green>Unclaimed land");
        info.add("");
        info.add("<gray>Claim land with a <white>Golden Shovel<gray>:");
        info.add("<gray> - <white>Left click<gray> a block for the first corner");
        info.add("<gray> - <white>Right click<gray> a block for the second corner");
        info.add("<gray>The price menu opens automatically.");
        info.add("");
        info.add("<gray>Right click with a <white>Stick<gray> to inspect any land.");
        set(inv, h, 4, item(Material.GRASS_BLOCK, "<gold>How to claim", info), null);
        set(inv, h, 11, item(Material.GOLDEN_SHOVEL, "<yellow>Get a golden shovel",
                "<gray>Click to put one in your inventory."), "give_shovel");
        set(inv, h, 13, item(Material.STICK, "<yellow>Get a stick",
                "<gray>Click to put one in your inventory."), "give_stick");
        long used = cm.ownedBlocks(p.getUniqueId());
        set(inv, h, 15, item(Material.BOOK, "<gold>My claims",
                "<gray>Blocks used: <white>" + used + "/" + cm.limitFor(p),
                "<yellow>Click to open"), "my_claims");
        set(inv, h, 22, item(Material.BARRIER, "<red>Close"), "close");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    /** The management menu for one territory. */
    public void openClaim(Player p, Claim c) {
        ClaimManager cm = plugin.claims();
        SettingsService svc = plugin.settings();
        MenuHolder h = new MenuHolder(Type.MAIN, c);
        Inventory inv = create(h, 27, "<dark_green><bold>Territory Settings");

        List<String> info = new ArrayList<>();
        info.add("<gray>Owner: <white>" + esc(c.isServer() ? "Server" : c.ownerName));
        info.add("<gray>Name: <white>" + esc(c.displayName()));
        info.add("<gray>Size: <white>" + cm.sizeLine(c));
        info.add("<gray>World: <white>" + esc(cm.worldName(c)));
        info.add("<gray>Status: " + cm.statusLine(c));
        info.add("<gray>Trusted players: <white>" + c.trusted.size());
        set(inv, h, 4, item(Material.PAPER, "<gold>" + esc(c.displayName()), info), null);

        if (mayPay(p, c)) {
            set(inv, h, 10, item(Material.DIAMOND, "<aqua>Pay upkeep",
                    "<gray>Rate: <white>" + trim(cm.rate(c)) + " hours/h",
                    "<gray>Add time to this territory.", "<yellow>Click to open"), "pay_menu");
        }
        if (mayAdmin(p, c)) {
            set(inv, h, 11, item(Material.PLAYER_HEAD, "<green>Trusted players",
                    "<gray>Choose who can build or manage here.", "<yellow>Click to open"), "trust_menu");
            set(inv, h, 13, item(Material.TNT, "<red>Unclaim territory",
                    "<gray>Gives up all of this territory's land.",
                    "<gray>Upkeep is not refunded."), "unclaim_menu");
        }
        if (svc.canManage(p, c)) {
            set(inv, h, 15, item(Material.COMPARATOR, "<gold>Settings",
                    "<gray>Protections and visitor rules.",
                    "<gray>Paid protections: <white>" + c.settings.activePaidCount(),
                    "<yellow>Click to open"), "settings");
        }
        set(inv, h, 12, item(Material.SPYGLASS, "<yellow>Show borders",
                "<gray>Particles outline this territory."), "borders");
        set(inv, h, 14, item(Material.BOOK, "<gold>My claims",
                "<gray>See all your claims and their time."), "my_claims");
        set(inv, h, 22, item(Material.BARRIER, "<red>Close"), "close");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    // ----------------------------------------------------------------- pay menu

    public void openPay(Player p, Claim c) {
        ClaimManager cm = plugin.claims();
        PaymentService pay = plugin.payments();
        if (!mayPay(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        double mult = cm.rate(c);
        Material choice = pay.choiceOf(p);
        MenuHolder h = new MenuHolder(Type.PAY, c);
        Inventory inv = create(h, 36, "<dark_green><bold>Pay upkeep");

        set(inv, h, 4, item(Material.CLOCK, "<gold>" + cm.sizeLine(c),
                "<gray>Status: " + cm.statusLine(c),
                "<gray>Rate: <white>" + trim(mult) + " hours/h",
                "<gray>Payment covers the whole territory.",
                "<gray>Paying with: <white>"
                        + (choice == null ? "cheapest items first" : Util.pretty(choice))), null);

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
                "<gray>Uses your cheapest accepted items first.",
                choice == null ? "<green>Selected" : "<yellow>Click to select");
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

    // ------------------------------------------------------------- trust menus

    public void openTrustAdd(Player p, Claim c) {
        if (!mayAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.TRUST_ADD, c);
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

    public void openTrustList(Player p, Claim c) {
        if (!mayAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.TRUST_LIST, c);
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
    public void openMember(Player p, Claim c, UUID id) {
        if (!mayAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        TrustList.Level current = c.trusted.levelOf(id);
        MenuHolder h = new MenuHolder(Type.MEMBER, c);
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

    public void openUnclaim(Player p, Claim c) {
        ClaimManager cm = plugin.claims();
        if (!mayAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.UNCLAIM, c);
        Inventory inv = create(h, 27, "<dark_red><bold>Unclaim territory?");
        set(inv, h, 11, item(Material.RED_CONCRETE, "<red>Yes, unclaim everything",
                "<gray>Size: <white>" + cm.sizeLine(c),
                "<gray>All of this territory's land is released.",
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
            long left = cm.remainingMs(c);
            Material icon = left > warn ? Material.LIME_CONCRETE
                    : left > 0 ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE;
            set(inv, h, slot++, item(icon, "<gold>" + esc(c.displayName()),
                            "<gray>Size: <white>" + cm.sizeLine(c),
                            "<gray>World: <white>" + esc(cm.worldName(c)),
                            "<gray>At: <white>" + c.shortId(),
                            "<gray>Status: " + cm.statusLine(c),
                            "<gray>Trusted: <white>" + c.trusted.size(),
                            "<yellow>Click to manage"),
                    "openclaim:" + list.indexOf(c));
        }
        if (list.isEmpty()) {
            set(inv, h, 22, item(Material.GOLDEN_SHOVEL, "<yellow>No claims yet",
                    "<gray>Use a golden shovel: left click one corner,",
                    "<gray>right click the other."), "close");
        }
        set(inv, h, 49, item(Material.ARROW, "<yellow>Back"), "main");
        fillEmpty(inv);
        CLAIM_LISTS.put(p.getUniqueId(), list);
        p.openInventory(inv);
    }

    private static final Map<UUID, List<Claim>> CLAIM_LISTS = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- settings

    public void openSettings(Player p, Claim c) {
        ClaimManager cm = plugin.claims();
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        if (!plugin.settings().canManage(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.SETTINGS, c);
        Inventory inv = create(h, 54, "<dark_green><bold>Territory Settings");

        List<String> head = new ArrayList<>();
        head.add("<gray>Territory: <white>" + esc(c.displayName()));
        head.add("<gray>Size: <white>" + cm.sizeLine(c));
        head.add("<gray>Protections bought: <white>" + c.settings.purchasedPaidCount() + "<gray> of 4"
                + " <gray>(<white>" + c.settings.activePaidCount() + " ON<gray>)");
        head.add("<gray>Upkeep rate: <white>" + trim(cm.rate(c)) + " hours/h");
        // Show the clock on this screen too, so a purchase visibly costs 24 hours.
        head.add("<gray>Upkeep left: <white>" + Util.duration(cm.remainingMs(c)));
        set(inv, h, 4, item(Material.PAPER, "<gold>Settings", head), null);

        set(inv, h, 0, item(Material.RED_STAINED_GLASS_PANE, "<dark_red>Paid protections",
                "<gray>Buy once for 24h of upkeep, then toggle for free.",
                "<gray>Each purchase raises upkeep cost permanently."), null);

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
     * Confirmation screen shown before an unpurchased protection is bought. It spells out
     * the three things a player can regret: the 24 hour cost, the permanent upkeep
     * increase, and that nothing is ever refunded.
     */
    public void openConfirmBuy(Player p, Claim c, SettingsService.Flag f) {
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        SettingsService svc = plugin.settings();
        if (!svc.canManage(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        if (c.settings.isPurchased(f.id)) {
            later(() -> openSettings(p, c));
            return;
        }
        long left = plugin.claims().remainingMs(c);
        MenuHolder h = new MenuHolder(Type.CONFIRM_BUY, c);
        Inventory inv = create(h, 27, "<dark_green><bold>Buy " + esc(f.title) + "?");

        int owned = c.settings.purchasedPaidCount();
        double nowFactor = 1.0 + svc.upkeepMultiplier() * owned;
        double nextFactor = 1.0 + svc.upkeepMultiplier() * (owned + 1);
        int pct = (int) Math.round(svc.upkeepMultiplier() * 100);
        List<String> cost = new ArrayList<>();
        cost.add("<gray>" + f.description);
        cost.add("");
        cost.add("<gray>Cost: <white>24 hours <gray>of this territory's upkeep");
        cost.add("<gray>Upkeep left now: <white>" + Util.duration(left));
        cost.add("<gray>After buying: <white>"
                + Util.duration(Math.max(0L, left - ClaimManager.PROTECTION_COST_MS)));
        cost.add("");
        cost.add("<gray>Permanent: upkeep cost <white>+" + pct + "%<gray> ("
                + trim(nowFactor) + "x -> <white>" + trim(nextFactor) + "x<gray>)");
        cost.add("<gray>Costs stay higher even while this is switched OFF.");
        cost.add("");
        cost.add("<red>No refunds. 24 hours is gone for good, and");
        cost.add("<red>toggling it off later gives nothing back.");
        set(inv, h, 4, item(Material.REDSTONE_BLOCK, "<red>" + f.title, cost), null);

        set(inv, h, 11, item(Material.LIME_CONCRETE, "<green>Yes, buy for 24 hours",
                "<gray>Deducts 24 hours and switches it ON.",
                "<gray>You keep it and can toggle it free from now on."),
                "buy_confirm:" + f.id);
        set(inv, h, 15, item(Material.RED_CONCRETE, "<red>No, go back",
                "<gray>Nothing is charged."), "settings");
        fillEmpty(inv);
        p.openInventory(inv);
    }

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
            lore.add("<gray>Cost: <white>24 hours <gray>of territory upkeep (one-time)");
            lore.add("<gray>Upkeep left: <white>" + Util.duration(plugin.claims().remainingMs(c)));
            lore.add("<gray>Permanent upkeep cost: <white>+"
                    + Math.round(svc.upkeepMultiplier() * 100) + "%");
            lore.add("<red>No refunds once bought.");
            lore.add("<yellow>Click to buy");
        } else if (on) {
            lore.add("<gray>Status: <green>ON (protecting)");
            lore.add("<gray>Purchased <gray>(free to toggle, non-refundable)");
            lore.add("<gray>Upkeep cost locked in: <white>+"
                    + Math.round(svc.upkeepMultiplier() * 100) + "%");
            lore.add("<yellow>Click to turn OFF");
        } else {
            lore.add("<gray>Status: <yellow>OFF <gray>(vanilla behaviour)");
            lore.add("<gray>Purchased <gray>(free to toggle, non-refundable)");
            lore.add("<gray>Upkeep cost stays locked in: <white>+"
                    + Math.round(svc.upkeepMultiplier() * 100) + "%");
            lore.add("<yellow>Click to turn ON");
        }
        if (locked) {
            lore.add("<red>Locked by the server admin.");
        }
        return item(icon, (on ? "<green>" : "<white>") + f.title, lore);
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
            lore.add("<gray>Cost: <white>24 hours <gray>of territory upkeep (one-time)");
            lore.add("<gray>Upkeep left: <white>" + Util.duration(plugin.claims().remainingMs(c)));
            lore.add("<gray>Permanent upkeep cost: <white>+"
                    + Math.round(svc.upkeepMultiplier() * 100) + "%");
            lore.add("<red>No refunds once bought.");
            lore.add("<gray>After buying, every PvP mode change is free.");
        } else if (on) {
            lore.add("<gray>Status: <green>ON (protecting) <gray>- may fight: <white>"
                    + c.settings.pvpScope.label());
            lore.add("<gray>Purchased <gray>(free to toggle, non-refundable)");
            lore.add("<gray>Changing mode is free.");
        } else {
            lore.add("<gray>Status: <yellow>OFF <gray>(everyone may fight)");
            lore.add("<gray>Purchased <gray>(free to toggle, non-refundable)");
            lore.add("<gray>Changing mode is free.");
        }
        if (locked) {
            lore.add("<red>Locked by the server admin.");
        }
        return item(locked ? Material.BARRIER : on ? Material.RED_DYE : Material.LIME_DYE,
                (on ? "<green>" : "<white>") + f.title, lore);
    }

    /** PvP scope sub-menu, only meaningful while PvP protection is active. */
    public void openPvpScope(Player p, Claim c) {
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        if (!plugin.settings().canManage(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        MenuHolder h = new MenuHolder(Type.PVP_SCOPE, c);
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
            String name = scope == TerritorySettings.PvpScope.EVERYONE
                    ? "everyone (no protection)" : scope.label();
            set(inv, h, slot++, item(cur ? Material.LIME_DYE : Material.GRAY_DYE,
                    (cur ? "<green>" : "<white>") + name,
                    cur ? "<green>Selected" : "<yellow>Click to select"), "scope:" + scope.name());
        }
        set(inv, h, 22, item(Material.ARROW, "<yellow>Back"), "settings");
        fillEmpty(inv);
        p.openInventory(inv);
    }

    // ----------------------------------------------------------------- borders

    /** Shows the outline of the territory the player is standing in, or of this claim. */
    public void showBorders(Player p, Claim c) {
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        List<Area> areas = new ArrayList<>(c.areas);
        new BukkitRunnable() {
            int runs = 0;

            @Override
            public void run() {
                if (!p.isOnline() || runs++ >= 16) {
                    cancel();
                    return;
                }
                double px = p.getLocation().getX();
                double pz = p.getLocation().getZ();
                for (Area a : areas) {
                    if (!a.world().equals(p.getWorld().getUID())) {
                        continue;
                    }
                    // Only outline rectangles near the player, to keep particles cheap.
                    double dx = Math.max(0, Math.max(a.minX() - px, px - a.maxX()));
                    double dz = Math.max(0, Math.max(a.minZ() - pz, pz - a.maxZ()));
                    if (dx * dx + dz * dz > 96.0 * 96.0) {
                        continue;
                    }
                    drawArea(p, a);
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void drawArea(Player p, Area a) {
        double py = p.getLocation().getY();
        List<double[]> edges = new ArrayList<>();
        edges.add(new double[]{a.minX(), a.minZ(), a.maxX(), a.minZ()});
        edges.add(new double[]{a.minX(), a.maxZ(), a.maxX(), a.maxZ()});
        edges.add(new double[]{a.minX(), a.minZ(), a.minX(), a.maxZ()});
        edges.add(new double[]{a.maxX(), a.minZ(), a.maxX(), a.maxZ()});
        for (double[] e : edges) {
            int steps = Math.max(2, (int) Math.round(Math.max(
                    Math.abs(e[2] - e[0]), Math.abs(e[3] - e[1]))));
            for (int i = 0; i <= steps; i++) {
                double x = e[0] + (e[2] - e[0]) * i / steps;
                double z = e[1] + (e[3] - e[1]) * i / steps;
                for (double dy = 0; dy <= 2; dy += 1) {
                    p.spawnParticle(Particle.END_ROD, x, py + dy, z, 1, 0, 0, 0, 0);
                }
            }
        }
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
        Claim c = h.claim;

        switch (parts[0]) {
            case "close" -> p.closeInventory();
            case "main" -> {
                if (c == null) {
                    later(() -> openMain(p));
                } else {
                    later(() -> openClaim(p, c));
                }
            }
            case "give_shovel" -> {
                p.getInventory().addItem(new ItemStack(Material.GOLDEN_SHOVEL));
                plugin.send(p, "tools-given", "tool", "Golden Shovel");
                p.closeInventory();
            }
            case "give_stick" -> {
                p.getInventory().addItem(new ItemStack(Material.STICK));
                plugin.send(p, "tools-given", "tool", "Stick");
                p.closeInventory();
            }
            case "sel_confirm" -> {
                ClaimManager.Quote q = QUOTES.remove(p.getUniqueId());
                // The quote can be missing if the player reopened the land and confirmed a
                // stale menu. Re-price from the rectangle the menu was built for instead of
                // silently doing nothing.
                if (q == null && h.selection != null) {
                    q = cm.quote(p, h.selection);
                }
                p.closeInventory();
                if (q == null) {
                    plugin.send(p, "no-claim-here");
                    return;
                }
                if (!q.ok()) {
                    plugin.sendMini(p, q.error());
                    return;
                }
                if (cm.purchase(p, q)) {
                    cm.saveNow();
                    Claim made = cm.ownedClaimAt(p.getUniqueId(), q.area());
                    if (made != null) {
                        later(() -> openClaim(p, made));
                    }
                }
            }
            case "pay_menu" -> later(() -> openPay(p, c));
            case "pay" -> {
                double hours = arg.equals("max") ? 0 : Double.parseDouble(arg);
                cm.payHere(p, c, hours);
                later(() -> openPay(p, c));
            }
            case "choose" -> {
                if (arg.equals("AUTO")) {
                    plugin.payments().setChoice(p, null);
                } else {
                    plugin.payments().setChoice(p, Material.matchMaterial(arg));
                }
                later(() -> {
                    if (h.type == Type.PAY) {
                        openPay(p, c);
                    }
                });
            }
            case "trust_menu" -> later(() -> openTrustAdd(p, c));
            case "trust_list" -> later(() -> openTrustList(p, c));
            case "trust" -> {
                UUID id = parseId(arg);
                if (id != null) {
                    cm.trust(p, c, id, nameOf(id));
                    later(() -> openTrustAdd(p, c));
                }
            }
            case "member" -> {
                UUID id = parseId(arg);
                if (id != null) {
                    later(() -> openMember(p, c, id));
                }
            }
            case "setlevel" -> {
                String[] bits = arg.split(":");
                UUID id = bits.length == 2 ? parseId(bits[0]) : null;
                if (id != null) {
                    cm.trust(p, c, id, nameOf(id), TrustList.Level.parse(bits[1]));
                    later(() -> openMember(p, c, id));
                }
            }
            case "removemember" -> {
                UUID id = parseId(arg);
                if (id != null) {
                    cm.untrust(p, c, id, nameOf(id));
                    later(() -> openTrustList(p, c));
                }
            }
            case "remove_confirm" -> {
                if (c != null && h.selection != null) {
                    p.closeInventory();
                    if (cm.shrink(p, c, h.selection)) {
                        cm.saveNow();
                        Claim still = cm.at(p.getLocation());
                        later(() -> {
                            if (still != null && !still.isServer()
                                    && p.getUniqueId().equals(still.owner)) {
                                openClaim(p, still);
                            } else {
                                openNoClaim(p);
                            }
                        });
                    }
                }
            }
            case "unclaim_menu" -> later(() -> openUnclaim(p, c));
            case "unclaim_confirm" -> {
                cm.unclaim(p, c);
                p.closeInventory();
            }
            case "openclaim" -> {
                List<Claim> list = CLAIM_LISTS.get(p.getUniqueId());
                if (list != null) {
                    try {
                        Claim picked = list.get(Integer.parseInt(arg));
                        if (picked != null && p.getUniqueId().equals(picked.owner)) {
                            later(() -> openClaim(p, picked));
                        }
                    } catch (IndexOutOfBoundsException | NumberFormatException ignored) {
                        later(() -> openMyClaims(p));
                    }
                }
            }
            case "buy_confirm" -> {
                SettingsService.Flag flag = SettingsService.Flag.parse(arg);
                if (flag == null || c == null) {
                    return;
                }
                if (cm.changeFlag(p, c, flag, true)) {
                    cm.saveNow();
                }
                later(() -> openSettings(p, c));
            }
            case "borders" -> {
                p.closeInventory();
                showBorders(p, c);
            }
            case "my_claims" -> later(() -> openMyClaims(p));
            case "settings" -> later(() -> openSettings(p, c));
            case "pvp_scope" -> later(() -> openPvpScope(p, c));
            case "flag" -> {
                SettingsService.Flag flag = SettingsService.Flag.parse(arg);
                if (flag == null || c == null) {
                    return;
                }
                if (flag == SettingsService.Flag.PVP) {
                    later(() -> openPvpScope(p, c));
                    return;
                }
                boolean owned = c.settings.isPurchased(flag.id);
                // Only PAID protections can be bought. Free toggles are never "owned", so
                // without this check switching a free toggle ON opened a bogus "Buy ... for
                // 24 hours?" screen.
                if (flag.isPaid() && !owned && !plugin.settings().get(c, flag)) {
                    if (cm.remainingMs(c) < ClaimManager.PROTECTION_COST_MS) {
                        plugin.send(p, "flag-need-upkeep", "flag", flag.title,
                                "time", Util.duration(cm.remainingMs(c)));
                        return;
                    }
                    later(() -> openConfirmBuy(p, c, flag));
                    return;
                }
                boolean newState = !plugin.settings().get(c, flag);
                if (cm.changeFlag(p, c, flag, newState)) {
                    cm.saveNow();
                }
                later(() -> openSettings(p, c));
            }
            case "scope" -> {
                if (c == null) {
                    return;
                }
                TerritorySettings.PvpScope scope = TerritorySettings.PvpScope.parse(arg);
                boolean pvpOwned = c.settings.isPurchased(SettingsService.Flag.PVP.id);
                if (!pvpOwned && scope != TerritorySettings.PvpScope.EVERYONE) {
                    if (cm.remainingMs(c) < ClaimManager.PROTECTION_COST_MS) {
                        plugin.send(p, "flag-need-upkeep", "flag", SettingsService.Flag.PVP.title,
                                "time", Util.duration(cm.remainingMs(c)));
                        return;
                    }
                    later(() -> openConfirmBuy(p, c, SettingsService.Flag.PVP));
                    return;
                }
                if (cm.setPvpScope(p, c, scope)) {
                    cm.saveNow();
                }
                later(() -> openPvpScope(p, c));
            }
            case "text" -> {
                SettingsService.TextInput field;
                try {
                    field = SettingsService.TextInput.valueOf(arg);
                } catch (IllegalArgumentException ex) {
                    return;
                }
                if (c == null || !plugin.settings().canManage(p, c)) {
                    plugin.send(p, "not-your-claim");
                    return;
                }
                PENDING.put(p.getUniqueId(), new PendingText(c, field));
                p.closeInventory();
                plugin.send(p, "text-prompt");
            }
            default -> {
            }
        }
    }
}
