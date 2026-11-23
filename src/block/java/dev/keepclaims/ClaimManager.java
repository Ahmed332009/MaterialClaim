package dev.keepclaims;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds every territory, saves them to claims.yml and runs the claim, pay, trust and
 * unclaim operations.
 *
 * <p>Land is measured in blocks. A territory is a set of non-overlapping rectangles, so it
 * can be any size and any shape built from rectangles, and it covers the whole vertical
 * column from bedrock to build limit (like GriefPrevention).
 *
 * <p>Upkeep model: an item worth v hours is worth v hours of upkeep for a territory whose
 * rate is 1.0. The rate is {@code blocks * perBlockHours * groupFactor(blocks) *
 * protectionFactor}. Remaining time = banked hours / rate, so the same items buy less time
 * as a territory grows or buys protections. The banked hours are in "hour units", so
 * resizing recomputes the time from the pool rather than losing it.
 */
public final class ClaimManager {
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;
    /** How far the spatial index groups blocks. Must be a power of two. */
    private static final int CELL_SHIFT = 6;
    private static final int CELL = 1 << CELL_SHIFT;

    private final KeepClaims plugin;
    /** Spatial index: cell key -> territory holding some of that cell. */
    private final Map<Long, Set<Claim>> index = new ConcurrentHashMap<>();
    private final Set<Claim> territories = ConcurrentHashMap.newKeySet();
    private final File file;
    private BukkitTask pendingSave;

    public ClaimManager(KeepClaims plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "claims.yml");
    }

    public void reloadSettings() {
        plugin.pricing().reload();
    }

    // ------------------------------------------------------------------- rates

    /** Hours of upkeep drained per hour by this territory, including paid protections. */
    public double rate(Claim c) {
        return baseRate(c) * plugin.settings().upkeepFactor(c);
    }

    /** Hours of upkeep drained per hour before paid protections. */
    public double baseRate(Claim c) {
        long blocks = Math.max(1L, c.blocks());
        return plugin.pricing().upkeepRate(blocks);
    }

    /**
     * The upkeep multiplier for a territory, i.e. how many item-hours one hour of upkeep
     * costs. Kept for the payment code, which speaks in "raw item hours".
     */
    public double multiplier(Claim c) {
        return rate(c);
    }

    private long graceMs() {
        return (long) (plugin.getConfig().getDouble("grace-days", 7) * DAY_MS);
    }

    private long maxPrepaidMs() {
        return (long) (plugin.getConfig().getDouble("max-prepaid-days", 14) * DAY_MS);
    }

    public boolean worldEnabled(World w) {
        List<String> list = plugin.getConfig().getStringList("enabled-worlds");
        return list.isEmpty() || list.contains(w.getName());
    }

    /** Maximum total blocks a player may own. */
    public long limitFor(Player p) {
        // Kept as a block budget: the old config counted chunks, so it is scaled up.
        int chunks = plugin.getConfig().getInt("max-claims-default", 25);
        long limit = (long) chunks * ClaimPricing.BLOCKS_PER_CHUNK;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("materialclaim.limit.")) {
                try {
                    long v = Long.parseLong(perm.substring("materialclaim.limit.".length()));
                    limit = Math.max(limit, v * ClaimPricing.BLOCKS_PER_CHUNK);
                } catch (NumberFormatException ignored) {
                    // not a number, skip
                }
            }
        }
        long absolute = plugin.getConfig().getLong("max-claim-blocks", 0L);
        return absolute > 0 ? Math.min(limit, absolute) : limit;
    }

    /** Copies every setting from src into dst (used when a claim gains defaults). */
    static void copySettings(TerritorySettings src, TerritorySettings dst) {
        TerritorySettings c = src.copy();
        dst.setEnabled("explosions", c.isEnabled("explosions"));
        dst.setEnabled("pvp", c.isEnabled("pvp"));
        dst.setEnabled("mob-spawning", c.isEnabled("mob-spawning"));
        dst.setEnabled("fire-spread", c.isEnabled("fire-spread"));
        dst.visitorDoors = c.visitorDoors;
        dst.visitorContainers = c.visitorContainers;
        dst.visitorAnimals = c.visitorAnimals;
        dst.mobGriefing = c.mobGriefing;
        dst.fluidFlow = c.fluidFlow;
        dst.cropTrample = c.cropTrample;
        dst.pvpScope = c.pvpScope;
        dst.purchased.clear();
        dst.purchased.addAll(c.purchased);
    }

    public long ownedBlocks(UUID owner) {
        long total = 0;
        for (Claim c : territories) {
            if (owner.equals(c.owner)) {
                total += c.blocks();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ index

    private static long cellKey(UUID world, int cx, int cz) {
        // Mix the world hash into the high bits so worlds never collide.
        return ((long) world.hashCode() << 40) ^ (((long) cx & 0xFFFFFL) << 20) ^ ((long) cz & 0xFFFFFL);
    }

    private void indexClaim(Claim c) {
        for (Area a : c.areas) {
            for (int cx = a.minX() >> CELL_SHIFT; cx <= a.maxX() >> CELL_SHIFT; cx++) {
                for (int cz = a.minZ() >> CELL_SHIFT; cz <= a.maxZ() >> CELL_SHIFT; cz++) {
                    index.computeIfAbsent(cellKey(a.world(), cx, cz),
                            k -> ConcurrentHashMap.newKeySet()).add(c);
                }
            }
        }
        territories.add(c);
    }

    private void unindexClaim(Claim c) {
        for (Area a : c.areas) {
            for (int cx = a.minX() >> CELL_SHIFT; cx <= a.maxX() >> CELL_SHIFT; cx++) {
                for (int cz = a.minZ() >> CELL_SHIFT; cz <= a.maxZ() >> CELL_SHIFT; cz++) {
                    Set<Claim> set = index.get(cellKey(a.world(), cx, cz));
                    if (set != null) {
                        set.remove(c);
                        if (set.isEmpty()) {
                            index.remove(cellKey(a.world(), cx, cz), set);
                        }
                    }
                }
            }
        }
        territories.remove(c);
    }

    /** Call after changing a territory's land, so the index matches the new shape. */
    public void reindex(Claim c) {
        unindexClaim(c);
        indexClaim(c);
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        index.clear();
        territories.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        boolean upgraded = false;
        if (y.isList("territories")) {
            for (Map<?, ?> m : y.getMapList("territories")) {
                try {
                    String ownerText = String.valueOf(m.get("owner"));
                    UUID owner = ownerText.equals("SERVER") ? null : UUID.fromString(ownerText);
                    Object name = m.get("owner-name");
                    long created = ((Number) m.get("created")).longValue();
                    long paid = ((Number) m.get("paid-until")).longValue();
                    Claim c = new Claim(owner, name == null ? "Server" : String.valueOf(name), created, paid);
                    // Existing claims get the configured defaults; stored settings override them.
                    copySettings(plugin.settings().defaults(), c.settings);
                    Object stored = m.get("settings");
                    if (stored instanceof Map<?, ?> sm) {
                        c.settings.readFrom(sm);
                    }
                    boolean marked = stored instanceof Map<?, ?> sm2 && Boolean.TRUE.equals(sm2.get("server-protected"));
                    if (owner == null && !marked) {
                        c.settings.applyServerDefaults();
                        upgraded = true;
                    }
                    c.trusted.readStored(m.get("trusted"));
                    // New format: explicit rectangles. Old format: chunk lists, converted.
                    if (m.get("areas") instanceof List<?> al) {
                        for (Object o : al) {
                            Area a = parseArea(String.valueOf(o));
                            if (a != null) {
                                c.addArea(a);
                            }
                        }
                    } else if (m.get("chunks") instanceof List<?> cl) {
                        for (Object o : cl) {
                            Area a = chunkArea(String.valueOf(o));
                            if (a != null) {
                                c.addArea(a);
                            }
                        }
                        upgraded = true;
                    }
                    if (c.areas.isEmpty()) {
                        continue;
                    }
                    indexClaim(c);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Skipping unreadable territory entry: " + ex.getMessage());
                }
            }
        }
        if (upgraded) {
            plugin.getLogger().info("Upgraded claim data to block areas (chunk claims became rectangles).");
            markDirty();
        }
    }

    /** Parses "world;minX;minZ;maxX;maxZ". */
    private static Area parseArea(String s) {
        String[] p = s.split(";");
        if (p.length != 5) {
            return null;
        }
        return new Area(UUID.fromString(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                Integer.parseInt(p[3]), Integer.parseInt(p[4]));
    }

    /** Converts a legacy "world;chunkX;chunkZ" entry into the 16x16 block rectangle. */
    private static Area chunkArea(String s) {
        String[] p = s.split(";");
        if (p.length != 3) {
            return null;
        }
        int x = Integer.parseInt(p[1]) << 4;
        int z = Integer.parseInt(p[2]) << 4;
        return new Area(UUID.fromString(p[0]), x, z, x + 15, z + 15);
    }

    private static String areaString(Area a) {
        return a.world() + ";" + a.minX() + ";" + a.minZ() + ";" + a.maxX() + ";" + a.maxZ();
    }

    public void markDirty() {
        if (pendingSave != null) {
            return;
        }
        pendingSave = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingSave = null;
            saveNow();
        }, 100L);
    }

    public void saveNow() {
        if (pendingSave != null) {
            pendingSave.cancel();
            pendingSave = null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Claim c : territories) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("owner", c.owner == null ? "SERVER" : c.owner.toString());
            m.put("owner-name", c.ownerName);
            m.put("created", c.created);
            m.put("paid-until", c.paidUntil);
            m.put("trusted", c.trusted.toMap());
            Map<String, Object> settingsMap = c.settings.toMap();
            if (c.isServer()) {
                settingsMap.put("server-protected", true);
            }
            m.put("settings", settingsMap);
            m.put("blocks", c.blocks());
            m.put("areas", c.areas.stream().map(ClaimManager::areaString).toList());
            out.add(m);
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("territories", out);
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(plugin.getDataFolder(), "claims.yml.tmp");
            y.save(tmp);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save claims: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- lookups

    public Claim at(Location l) {
        if (l.getWorld() == null) {
            return null;
        }
        return at(l.getWorld(), l.getBlockX(), l.getBlockZ());
    }

    public Claim at(Block b) {
        return at(b.getWorld(), b.getX(), b.getZ());
    }

    public Claim at(World w, int x, int z) {
        Set<Claim> set = index.get(cellKey(w.getUID(), x >> CELL_SHIFT, z >> CELL_SHIFT));
        if (set == null) {
            return null;
        }
        for (Claim c : set) {
            if (c.contains(x, z)) {
                return c;
            }
        }
        return null;
    }

    /** All territories, for admin listings. */
    public Set<Claim> all() {
        return territories;
    }

    /** Every territory that overlaps the rectangle (any owner). */
    public List<Claim> overlapping(Area probe) {
        List<Claim> out = new ArrayList<>();
        for (int cx = probe.minX() >> CELL_SHIFT; cx <= probe.maxX() >> CELL_SHIFT; cx++) {
            for (int cz = probe.minZ() >> CELL_SHIFT; cz <= probe.maxZ() >> CELL_SHIFT; cz++) {
                Set<Claim> set = index.get(cellKey(probe.world(), cx, cz));
                if (set == null) {
                    continue;
                }
                for (Claim c : set) {
                    if (!out.contains(c) && c.overlaps(probe)) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    /** Every territory touching or overlapping the probe, owned by the given player. */
    public Set<Claim> adjacentOwned(UUID owner, Area probe) {
        Area halo = new Area(probe.world(), probe.minX() - 1, probe.minZ() - 1,
                probe.maxX() + 1, probe.maxZ() + 1);
        Set<Claim> out = new LinkedHashSet<>();
        for (Claim c : overlapping(halo)) {
            if (owner.equals(c.owner)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Finds a territory by its custom name, the owner's name (case insensitive), a
     * "x,z" block id as reported by /claims list, or a world+coordinates lookup.
     */
    public Claim findTerritory(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim();
        String lower = q.toLowerCase(Locale.ROOT);
        if (q.contains(",")) {
            String[] parts = q.split(",");
            if (parts.length == 2) {
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int z = Integer.parseInt(parts[1].trim());
                    for (Claim c : territories) {
                        if (c.contains(x, z)) {
                            return c;
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to the name search
                }
            }
        }
        for (Claim c : territories) {
            if (c.settings.customName != null && c.settings.customName.equalsIgnoreCase(q)) {
                return c;
            }
        }
        for (Claim c : territories) {
            if (c.ownerName != null && c.ownerName.equalsIgnoreCase(q)) {
                return c;
            }
        }
        return null;
    }

    public List<Claim> byOwner(UUID owner) {
        List<Claim> list = new ArrayList<>();
        for (Claim c : territories) {
            if (owner.equals(c.owner)) {
                list.add(c);
            }
        }
        list.sort(Comparator.comparingLong((Claim c) -> c.created)
                .thenComparing(Claim::shortId));
        return list;
    }

    public String worldName(Claim c) {
        UUID id = c.world();
        if (id == null) {
            return "unknown";
        }
        World w = Bukkit.getWorld(id);
        return w == null ? "unknown" : w.getName();
    }

    public boolean sameOwner(Claim a, Claim b) {
        return a != null && b != null && Objects.equals(a.owner, b.owner);
    }

    public boolean allowed(Player p, Claim c) {
        if (c == null) {
            return true;
        }
        if (plugin.bypassing().contains(p.getUniqueId())) {
            return true;
        }
        if (c.isServer()) {
            return false;
        }
        return c.mayBuild(p.getUniqueId());
    }

    public long remainingMs(Claim c) {
        return c.paidUntil - System.currentTimeMillis();
    }

    /** MiniMessage text describing the upkeep state of a territory. */
    public String statusLine(Claim c) {
        if (c.isServer()) {
            return "<aqua>Server protected";
        }
        long left = remainingMs(c);
        if (left > 0) {
            return "<green>Active <gray>- <white>" + Util.timeLeft(c.paidUntil) + " <gray>left";
        }
        if (c.paidUntil + graceMs() > System.currentTimeMillis()) {
            return "<red>Overdue <gray>- removed in <white>"
                    + Util.timeLeft(c.paidUntil + graceMs());
        }
        return "<dark_red>Expired";
    }

    /** "64 x 64 (4096 blocks)" for menus and messages. */
    public String sizeLine(Claim c) {
        Area b = c.bounds();
        if (b == null) {
            return "0 blocks";
        }
        return b.width() + " x " + b.depth() + " (" + c.blocks() + " blocks)";
    }

    // ------------------------------------------------------------- selection

    /**
     * Extra land a selection would add: the part of the probe not already owned by this
     * player. Overlapping your own land is free, so a resize only pays for new blocks.
     */
    public long newBlocks(UUID owner, Area probe) {
        List<Area> remaining = new ArrayList<>();
        remaining.add(probe);
        for (Claim c : overlapping(probe)) {
            if (!owner.equals(c.owner)) {
                continue;
            }
            for (Area a : c.areas) {
                List<Area> next = new ArrayList<>();
                for (Area r : remaining) {
                    next.addAll(Area.subtract(r, a));
                }
                remaining = next;
            }
        }
        return Area.totalBlocks(remaining);
    }

    /** True when every block of the probe is already owned by this player. */
    public boolean fullyOwned(UUID owner, Area probe) {
        return newBlocks(owner, probe) == 0;
    }

    /** The territory of `owner` that the probe overlaps, or null. */
    public Claim ownedClaimAt(UUID owner, Area probe) {
        for (Claim c : overlapping(probe)) {
            if (owner.equals(c.owner)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ operations

    /**
     * The result of a shovel selection: what was created or grown, plus what it cost.
     * Returned so the GUI can show the price before anything is charged.
     */
    public record Quote(Area area, long blocks, long newBlocks, Claim mergeBase,
                        List<Claim> mergeOthers, long blocksAfter, double hours, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Prices a selection without changing anything. This is what the automatic menu shows:
     * the adjusted price for these many blocks, and how big the territory becomes.
     */
    public Quote quote(Player p, Area probe) {
        UUID id = p.getUniqueId();
        long total = probe.blocks();
        if (!worldEnabled(p.getWorld())) {
            return new Quote(probe, total, 0, null, List.of(), total,
                    0, plugin.message("world-disabled"));
        }
        if (total < plugin.pricing().minimumBlocks()) {
            return new Quote(probe, total, 0, null, List.of(), total, 0,
                    plugin.message("selection-too-small",
                            "min", String.valueOf(plugin.pricing().minimumBlocks()),
                            "side", String.valueOf(
                                    Math.max(1, (int) Math.round(Math.sqrt(plugin.pricing().minimumBlocks()))))));
        }
        int cap = plugin.pricing().maxBlocks();
        if (cap > 0 && total > cap) {
            return new Quote(probe, total, 0, null, List.of(), total, 0,
                    plugin.message("selection-too-big", "max", String.valueOf(cap)));
        }
        for (Claim c : overlapping(probe)) {
            if (!id.equals(c.owner)) {
                return new Quote(probe, total, 0, null, List.of(), total, 0,
                        plugin.message("overlaps-other",
                                "owner", c.isServer() ? "the server" : c.ownerName));
            }
        }
        long fresh = newBlocks(id, probe);
        List<Claim> owned = new ArrayList<>();
        for (Claim c : overlapping(probe)) {
            if (id.equals(c.owner)) {
                owned.add(c);
            }
        }
        // Everything the player already owns here plus the new blocks.
        long after = fresh;
        for (Claim c : owned) {
            after += c.blocks();
        }
        long budget = limitFor(p);
        if (ownedBlocks(id) + fresh > budget) {
            return new Quote(probe, total, fresh, null, List.of(), after, 0,
                    plugin.message("limit-reached", "limit", String.valueOf(budget)));
        }
        double hours = fresh == 0 ? 0 : plugin.pricing().priceHours(fresh);
        Claim base = null;
        for (Claim c : owned) {
            if (base == null || c.created < base.created) {
                base = c;
            }
        }
        List<Claim> others = new ArrayList<>();
        for (Claim c : owned) {
            if (c != base) {
                others.add(c);
            }
        }
        return new Quote(probe, total, fresh, base, others, after, hours, null);
    }

    /** The upkeep rate the territory will have after this selection is applied. */
    public double rateAfter(Quote q) {
        TerritorySettings merged = q.mergeBase() == null
                ? plugin.settings().defaults().copy() : q.mergeBase().settings.copy();
        for (Claim o : q.mergeOthers()) {
            merged.absorb(o.settings);
        }
        double factor = 1.0 + plugin.settings().upkeepMultiplier() * merged.purchasedPaidCount();
        return plugin.pricing().upkeepRate(q.blocksAfter()) * factor;
    }

    /** Two-decimal text for log lines. */
    private static String trimHours(double hours) {
        return hours == Math.rint(hours) ? String.valueOf((long) hours)
                : String.format(java.util.Locale.ROOT, "%.2f", hours);
    }

    /** Charges for a quoted selection and applies it. */
    public boolean purchase(Player p, Quote q) {
        if (!q.ok()) {
            plugin.sendMini(p, q.error());
            return false;
        }
        if (q.newBlocks() == 0) {
            plugin.send(p, "already-yours");
            return false;
        }
        long now = System.currentTimeMillis();
        Claim base = q.mergeBase();
        // Protection factor of the territory as it will be after merging.
        TerritorySettings merged = base == null ? plugin.settings().defaults() : base.settings.copy();
        for (Claim other : q.mergeOthers()) {
            merged.absorb(other.settings);
        }
        double factor = 1.0 + plugin.settings().upkeepMultiplier() * merged.purchasedPaidCount();
        double baseRate = plugin.pricing().upkeepRate(q.blocksAfter());
        double f = baseRate * factor;
        // Upkeep already banked in the territories this selection joins.
        double carried = 0;
        List<Claim> joined = new ArrayList<>(q.mergeOthers());
        if (base != null) {
            joined.add(base);
        }
        for (Claim a : joined) {
            long left = a.paidUntil - now;
            if (left > 0) {
                carried += (left / (double) HOUR_MS) * rate(a);
            }
        }
        double roomHours = Math.max(0, maxPrepaidMs() / (double) HOUR_MS - carried / f);
        if (roomHours <= 0) {
            plugin.send(p, "max-prepaid");
            return false;
        }
        // Raw item value needed is the claim price; the room ceiling stops whole-item
        // rounding from crediting more time than the territory can hold.
        double wantHours = Math.min(q.hours() / f, roomHours);
        if (q.hours() <= 0) {
            wantHours = 0;
        }
        double paidRaw = 0;
        if (wantHours > 0) {
            PaymentService.Result r = plugin.payments().charge(p, wantHours, f,
                    plugin.payments().choiceOf(p), roomHours);
            if (!r.ok()) {
                plugin.sendMini(p, r.error());
                return false;
            }
            paidRaw = r.credited() * f;
        }
        double pool = paidRaw + carried;
        long remaining = Math.min((long) (pool / f * HOUR_MS), maxPrepaidMs());

        Claim target;
        if (base == null) {
            target = new Claim(p.getUniqueId(), p.getName(), now, now + remaining);
            copySettings(plugin.settings().defaults(), target.settings);
        } else {
            target = base;
            for (Claim other : q.mergeOthers()) {
                for (Area a : other.areas) {
                    target.addArea(a);
                }
                for (var e : other.trusted.entries().entrySet()) {
                    TrustList.Level current = target.trusted.levelOf(e.getKey());
                    if (e.getValue().weight > current.weight) {
                        target.trusted.set(e.getKey(), e.getValue());
                    }
                }
                target.created = Math.min(target.created, other.created);
                target.settings.absorb(other.settings);
                unindexClaim(other);
            }
        }
        target.addArea(q.area());
        target.ownerName = p.getName();
        target.paidUntil = now + remaining;
        reindex(target);
        markDirty();
        // Console line so a server owner can confirm what actually happened, including which
        // rectangle, how many blocks and what was charged.
        plugin.getLogger().info("Claim by " + p.getName()
                + ": " + q.area().describe()
                + " (" + q.newBlocks() + " new blocks, " + target.blocks() + " total)"
                + " cost " + trimHours(q.hours()) + "h, "
                + Util.duration(remaining) + " left");
        plugin.send(p, "claim-success", "time", Util.duration(remaining),
                "size", String.valueOf(target.blocks()),
                "dims", sizeLine(target));
        return true;
    }

    /** Kept for the chat command: claims the chunk the player stands in as a rectangle. */
    public void claimHere(Player p) {
        Area probe = chunkAt(p.getLocation());
        Quote q = quote(p, probe);
        if (!q.ok()) {
            plugin.sendMini(p, q.error());
            return;
        }
        purchase(p, q);
    }

    /** The 16x16 block rectangle of the chunk containing this location. */
    public Area chunkAt(Location l) {
        int x = l.getBlockX() >> 4 << 4;
        int z = l.getBlockZ() >> 4 << 4;
        return new Area(l.getWorld().getUID(), x, z, x + 15, z + 15);
    }

    /** hours <= 0 means "fill up to the maximum". Pays for the whole territory. */
    public void payHere(Player p, Claim c, double hours) {
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        if (!plugin.settings().canManage(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        long now = System.currentTimeMillis();
        long base = Math.max(now, c.paidUntil);
        long cap = now + maxPrepaidMs();
        if (base >= cap - 60_000L) {
            plugin.send(p, "max-prepaid");
            return;
        }
        double allowedHours = (cap - base) / (double) HOUR_MS;
        if (hours <= 0 || hours > allowedHours) {
            hours = allowedHours;
        }
        PaymentService.Result r = plugin.payments().charge(p, hours, rate(c),
                plugin.payments().choiceOf(p), allowedHours);
        if (!r.ok()) {
            plugin.sendMini(p, r.error());
            return;
        }
        long newUntil = base + (long) (r.credited() * HOUR_MS);
        if (newUntil > cap) {
            newUntil = cap;
            plugin.getLogger().warning("Upkeep clamp triggered for " + p.getName()
                    + " despite a ceiling-aware charge; credited=" + r.credited());
        }
        c.paidUntil = newUntil;
        markDirty();
        plugin.send(p, "pay-success", "time", Util.duration(newUntil - now));
    }

    /** Removes a whole territory. No refund, ever. */
    public void unclaim(Player p, Claim c) {
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        if (!plugin.settings().isOwnerOrAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        unindexClaim(c);
        markDirty();
        plugin.send(p, "unclaim-success");
    }

    /**
     * Removes land from the player's territory, free-form. The upkeep pool is kept and the
     * remaining time recomputed at the smaller rate, so shrinking never creates time.
     */
    public boolean shrink(Player p, Claim c, Area area) {
        if (c == null || !plugin.settings().isOwnerOrAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return false;
        }
        long now = System.currentTimeMillis();
        boolean active = c.paidUntil > now;
        double pool = active ? ((c.paidUntil - now) / (double) HOUR_MS) * rate(c) : 0;
        unindexClaim(c);
        c.removeArea(area);
        if (c.areas.isEmpty()) {
            markDirty();
            plugin.send(p, "unclaim-success");
            return true;
        }
        if (active) {
            long remaining = (long) (pool / rate(c) * HOUR_MS);
            c.paidUntil = now + Math.min(remaining, maxPrepaidMs());
        }
        indexClaim(c);
        markDirty();
        plugin.send(p, "unclaim-success");
        return true;
    }

    public void trust(Player p, Claim c, UUID target, String name) {
        trust(p, c, target, name, null);
    }

    /** Grants (or upgrades) trust. A null level means Builder. */
    public void trust(Player p, Claim c, UUID target, String name, TrustList.Level level) {
        if (c == null || !plugin.settings().isOwnerOrAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        if (target.equals(p.getUniqueId()) || target.equals(c.owner)) {
            plugin.send(p, "trust-self");
            return;
        }
        TrustList.Level wanted = level == null ? TrustList.Level.BUILDER : level;
        TrustList.Level prev = c.trusted.set(target, wanted);
        if (prev == wanted) {
            plugin.send(p, "already-trusted", "name", name);
            return;
        }
        markDirty();
        plugin.send(p, "trust-added", "name", name, "level", wanted.id);
    }

    public void untrust(Player p, Claim c, UUID target, String name) {
        if (c == null || !plugin.settings().isOwnerOrAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        if (c.trusted.remove(target) != TrustList.Level.VISITOR) {
            markDirty();
            plugin.send(p, "trust-removed", "name", name);
        }
    }

    // --------------------------------------------------------------- settings

    /** A purchase needs at least this much upkeep left on the territory clock. */
    public static final long PROTECTION_COST_MS = 24L * 60L * 60L * 1000L;

    /**
     * Applies a flag change. Purchasing and toggling are deliberately different:
     *
     * <ul>
     *   <li><b>Buying</b> an unbought protection takes exactly 24 hours off the territory
     *       clock and is refused outright when less than 24 hours remain.</li>
     *   <li><b>Toggling</b> a bought protection is free: no hours taken, none given back,
     *       and the upkeep cost does not move.</li>
     * </ul>
     */
    public boolean changeFlag(Player p, Claim c, SettingsService.Flag flag, boolean enabled) {
        SettingsService svc = plugin.settings();
        String refuse = svc.refusal(p, c, flag);
        if (refuse != null) {
            plugin.sendMini(p, refuse);
            return false;
        }
        boolean current = svc.get(c, flag);
        if (current == enabled) {
            plugin.send(p, "flag-unchanged", "flag", flag.title,
                    "value", enabled ? "on" : "off");
            return true;
        }
        // Everyone pays, including admins and the owner. Server-owned territories start
        // fully bought, so they are never charged.
        boolean buying = flag.isPaid() && enabled && !c.settings.isPurchased(flag.id);
        if (buying) {
            if (remainingMs(c) < PROTECTION_COST_MS) {
                plugin.send(p, "flag-need-upkeep", "flag", flag.title,
                        "time", Util.duration(remainingMs(c)));
                return false;
            }
            c.settings.markPurchased(flag.id);
            if (flag == SettingsService.Flag.PVP
                    && c.settings.pvpScope == TerritorySettings.PvpScope.EVERYONE) {
                c.settings.pvpScope = TerritorySettings.PvpScope.NOBODY;
            }
            svc.set(c, flag, true);
            // Take exactly 24 hours off the clock. The new higher rate is not re-priced
            // into the banked time here, so the purchase costs 24 hours and no more.
            long now = System.currentTimeMillis();
            c.paidUntil = Math.max(now, c.paidUntil - PROTECTION_COST_MS);
            markDirty();
            plugin.send(p, "flag-purchased", "flag", flag.title, "hours", "24");
            return true;
        }
        if (flag == SettingsService.Flag.PVP && enabled
                && c.settings.pvpScope == TerritorySettings.PvpScope.EVERYONE) {
            c.settings.pvpScope = TerritorySettings.PvpScope.NOBODY;
        }
        // Free toggle: switch it, take nothing, give nothing back.
        svc.set(c, flag, enabled);
        markDirty();
        plugin.send(p, "flag-changed", "flag", flag.title, "value", enabled ? "on" : "off");
        return true;
    }

    /**
     * The single PvP control. EVERYONE means no protection. Any other choice protects,
     * which costs 24 hours of upkeep the first time only.
     */
    public boolean setPvpScope(Player p, Claim c, TerritorySettings.PvpScope scope) {
        SettingsService svc = plugin.settings();
        String refuse = svc.refusal(p, c, SettingsService.Flag.PVP);
        if (refuse != null) {
            plugin.sendMini(p, refuse);
            return false;
        }
        if (scope == TerritorySettings.PvpScope.EVERYONE) {
            if (c.settings.isEnabled(SettingsService.Flag.PVP.id)
                    && !changeFlag(p, c, SettingsService.Flag.PVP, false)) {
                return false;
            }
            c.settings.pvpScope = TerritorySettings.PvpScope.EVERYONE;
        } else {
            TerritorySettings.PvpScope previous = c.settings.pvpScope;
            c.settings.pvpScope = scope;
            if (!c.settings.isEnabled(SettingsService.Flag.PVP.id)
                    && !changeFlag(p, c, SettingsService.Flag.PVP, true)) {
                c.settings.pvpScope = previous;
                return false;
            }
        }
        markDirty();
        plugin.send(p, "flag-changed", "flag", "PvP", "value", c.settings.pvpScope.label());
        return true;
    }

    /** Sets a free text value (name or messages). Owner, managers and admins may do this. */
    public boolean setText(Player p, Claim c, SettingsService.TextInput field, String value) {
        SettingsService svc = plugin.settings();
        SettingsService.Role role = svc.roleOf(p, c);
        boolean admin = role == SettingsService.Role.ADMIN;
        if (!svc.canManage(p, c)) {
            plugin.send(p, "not-your-claim");
            return false;
        }
        if (!admin && !p.hasPermission("materialclaim.flags")) {
            plugin.send(p, "no-permission");
            return false;
        }
        String v = value == null ? "" : value.trim();
        if (v.equalsIgnoreCase("clear") || v.equalsIgnoreCase("none")) {
            v = "";
        }
        switch (field) {
            case NAME -> c.settings.customName = v;
            case ENTRY_MESSAGE -> c.settings.entryMessage = v;
            case LEAVE_MESSAGE -> c.settings.leaveMessage = v;
            default -> {
                return false;
            }
        }
        markDirty();
        plugin.send(p, "flag-changed", "flag", field.name().toLowerCase(Locale.ROOT),
                "value", v.isEmpty() ? "(none)" : v);
        return true;
    }

    /**
     * When upkeep runs out, paid protections are dropped first so the territory reverts
     * to the cheaper vanilla behaviour before it is ever unclaimed.
     * Returns true when at least one protection was dropped.
     */
    public boolean dropPaidProtections(Claim c) {
        TerritorySettings s = c.settings;
        boolean changed = false;
        for (SettingsService.Flag f : SettingsService.Flag.values()) {
            if (f.isPaid() && s.isEnabled(f.id)) {
                s.setEnabled(f.id, false);
                changed = true;
            }
        }
        if (changed) {
            markDirty();
        }
        return changed;
    }

    // ------------------------------------------------------------------- admin

    /** Claims a square of server land centred on the player, in chunks (16 blocks each). */
    public void serverClaim(Player p, int radius) {
        Location l = p.getLocation();
        int cx = l.getBlockX();
        int cz = l.getBlockZ();
        int r = Math.max(0, radius) * 16 + 15;
        Area area = new Area(l.getWorld().getUID(), cx - r, cz - r, cx + r, cz + r);
        List<Area> pieces = new ArrayList<>();
        pieces.add(area);
        // Cut away land that is already claimed, so nothing is taken from players.
        for (Claim c : overlapping(area)) {
            List<Area> next = new ArrayList<>();
            for (Area piece : pieces) {
                List<Area> rest = new ArrayList<>();
                for (Area a : c.areas) {
                    if (rest.isEmpty()) {
                        rest.addAll(Area.subtract(piece, a));
                    } else {
                        List<Area> split = new ArrayList<>();
                        for (Area x : rest) {
                            split.addAll(Area.subtract(x, a));
                        }
                        rest = split;
                    }
                }
                next.addAll(rest.isEmpty() ? List.of(piece) : rest);
            }
            pieces = next;
        }
        long count = 0;
        long now = System.currentTimeMillis();
        for (Area piece : pieces) {
            if (piece.blocks() <= 0) {
                continue;
            }
            Claim c = new Claim(null, "Server", now, Long.MAX_VALUE);
            c.settings.applyServerDefaults();
            c.addArea(piece);
            indexClaim(c);
            count += piece.blocks();
        }
        markDirty();
        plugin.send(p, "server-claimed", "count", String.valueOf(count));
    }

    /** Removes the whole territory the admin is standing in. */
    public void adminUnclaim(Player p) {
        Claim c = at(p.getLocation());
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        unindexClaim(c);
        markDirty();
        plugin.send(p, "admin-unclaimed");
    }

    // ------------------------------------------------------------------ expiry

    public void expireCheck() {
        long now = System.currentTimeMillis();
        long grace = graceMs();
        List<Claim> dead = new ArrayList<>();
        for (Claim c : territories) {
            if (c.isServer()) {
                continue;
            }
            if (c.paidUntil < now && dropPaidProtections(c)) {
                Player owner = Bukkit.getPlayer(c.owner);
                if (owner != null) {
                    plugin.send(owner, "protections-dropped", "name", c.displayName());
                }
            }
            if (c.paidUntil + grace < now) {
                dead.add(c);
            }
        }
        if (dead.isEmpty()) {
            return;
        }
        StringBuilder log = new StringBuilder();
        for (Claim c : dead) {
            Area b = c.bounds();
            unindexClaim(c);
            log.append(Instant.now()).append(" expired owner=").append(c.ownerName)
                    .append(" world=").append(worldName(c))
                    .append(" blocks=").append(c.blocks())
                    .append(" at=").append(b == null ? "?" : b.describe()).append('\n');
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner != null) {
                plugin.send(owner, "claim-expired",
                        "x", String.valueOf(b == null ? 0 : b.minX()),
                        "z", String.valueOf(b == null ? 0 : b.minZ()),
                        "size", String.valueOf(c.blocks()));
            }
        }
        markDirty();
        try {
            plugin.getDataFolder().mkdirs();
            Files.writeString(new File(plugin.getDataFolder(), "expired-claims.log").toPath(),
                    log.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write expired-claims.log: " + e.getMessage());
        }
    }

    /** Number of the player's territories with less than warn-days of upkeep left. */
    public int lowCount(UUID owner) {
        long warn = (long) (plugin.getConfig().getDouble("warn-days", 3) * DAY_MS);
        int n = 0;
        for (Claim c : byOwner(owner)) {
            if (remainingMs(c) < warn) {
                n++;
            }
        }
        return n;
    }
}
