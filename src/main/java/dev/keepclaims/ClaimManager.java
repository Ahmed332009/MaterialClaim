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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds every territory (group of connected chunks), saves them to claims.yml and runs the
 * claim, pay, trust and unclaim operations.
 *
 * Upkeep model: a territory of n chunks drains upkeep at n * perChunkMultiplier(n)
 * "chunk-hours" per hour. An item worth v hours adds v chunk-hours to the pool.
 * Remaining time = pool / rate. When the size changes the pool is kept and the time is recomputed.
 */
public final class ClaimManager {
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    private final KeepClaims plugin;
    private final Map<ChunkKey, Claim> byChunk = new ConcurrentHashMap<>();
    private final Set<Claim> territories = ConcurrentHashMap.newKeySet();
    private final File file;
    private BukkitTask pendingSave;
    private List<double[]> tiers = new ArrayList<>();

    public ClaimManager(KeepClaims plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "claims.yml");
    }

    // ---------------------------------------------------------------- settings

    public void reloadSettings() {
        List<double[]> list = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("group-upkeep")) {
            Object upTo = m.get("up-to");
            Object mult = m.get("multiplier");
            if (upTo instanceof Number a && mult instanceof Number b) {
                list.add(new double[]{a.doubleValue(), b.doubleValue()});
            }
        }
        list.sort(Comparator.comparingDouble(t -> t[0]));
        if (list.isEmpty()) {
            list.add(new double[]{Integer.MAX_VALUE, 1.0});
        }
        tiers = list;
    }

    /** Upkeep multiplier per chunk for a territory of the given size. */
    public double perChunkMultiplier(int size) {
        for (double[] t : tiers) {
            if (size <= t[0]) {
                return Math.max(0.01, t[1]);
            }
        }
        return Math.max(0.01, tiers.get(tiers.size() - 1)[1]);
    }

    /** Chunk-hours drained per hour by this territory, including paid protections. */
    public double rate(Claim c) {
        int n = Math.max(1, c.size());
        return n * perChunkMultiplier(n) * plugin.settings().upkeepFactor(c);
    }

    /** Chunk-hours drained per hour by this territory before paid protections. */
    public double baseRate(Claim c) {
        int n = Math.max(1, c.size());
        return n * perChunkMultiplier(n);
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

    /** Maximum total chunks a player may own. */
    public int limitFor(Player p) {
        int limit = plugin.getConfig().getInt("max-claims-default", 25);
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("materialclaim.limit.")) {
                try {
                    limit = Math.max(limit, Integer.parseInt(perm.substring("materialclaim.limit.".length())));
                } catch (NumberFormatException ignored) {
                    // not a number, skip
                }
            }
        }
        return limit;
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

    public int ownedChunks(UUID owner) {
        int total = 0;
        for (Claim c : territories) {
            if (owner.equals(c.owner)) {
                total += c.size();
            }
        }
        return total;
    }

    // ----------------------------------------------------------------- storage

    private void register(Claim c) {
        for (ChunkKey k : c.chunks) {
            byChunk.put(k, c);
        }
        territories.add(c);
    }

    private void removeTerritory(Claim c) {
        for (ChunkKey k : c.chunks) {
            byChunk.remove(k, c);
        }
        territories.remove(c);
    }

    private static String keyString(ChunkKey k) {
        return k.world() + ";" + k.x() + ";" + k.z();
    }

    private static ChunkKey parseKey(String s) {
        String[] p = s.split(";");
        return new ChunkKey(UUID.fromString(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    public void load() {
        byChunk.clear();
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
                    // Server claims (spawn, shops) are protected by default. Claims saved by an
                    // older version have no marker, so they get the protected defaults once.
                    boolean marked = stored instanceof Map<?, ?> sm2 && Boolean.TRUE.equals(sm2.get("server-protected"));
                    if (owner == null && !marked) {
                        c.settings.applyServerDefaults();
                        upgraded = true;
                    }
                    c.trusted.readStored(m.get("trusted"));
                    if (m.get("chunks") instanceof List<?> cl) {
                        for (Object o : cl) {
                            c.chunks.add(parseKey(String.valueOf(o)));
                        }
                    }
                    if (!c.chunks.isEmpty()) {
                        register(c);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Skipping a bad territory entry: " + ex.getMessage());
                }
            }
        } else if (y.isList("claims")) {
            migrateOld(y);
        }
        if (upgraded) {
            markDirty();
        }
        plugin.getLogger().info("Loaded " + territories.size() + " territories (" + byChunk.size() + " chunks).");
    }

    private static final class Old {
        ChunkKey key;
        UUID owner;
        String name;
        long created;
        long paid;
        final TrustList trusted = new TrustList();
    }

    /** Converts the old one-claim-per-chunk file format into connected territories. */
    private void migrateOld(YamlConfiguration y) {
        List<Old> olds = new ArrayList<>();
        for (Map<?, ?> m : y.getMapList("claims")) {
            try {
                Old o = new Old();
                o.key = new ChunkKey(UUID.fromString(String.valueOf(m.get("world"))),
                        ((Number) m.get("x")).intValue(), ((Number) m.get("z")).intValue());
                String ownerText = String.valueOf(m.get("owner"));
                o.owner = ownerText.equals("SERVER") ? null : UUID.fromString(ownerText);
                o.name = m.get("owner-name") == null ? "Server" : String.valueOf(m.get("owner-name"));
                o.created = ((Number) m.get("created")).longValue();
                o.paid = ((Number) m.get("paid-until")).longValue();
                if (m.get("trusted") instanceof List<?> tl) {
                    for (Object t : tl) {
                        o.trusted.set(UUID.fromString(String.valueOf(t)), TrustList.Level.BUILDER);
                    }
                }
                olds.add(o);
            } catch (Exception ex) {
                plugin.getLogger().warning("Skipping a bad old claim entry: " + ex.getMessage());
            }
        }
        try {
            Files.copy(file.toPath(), new File(plugin.getDataFolder(), "claims-old-format.yml").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not back up the old claims file: " + e.getMessage());
        }
        long now = System.currentTimeMillis();
        Map<UUID, Map<ChunkKey, Old>> perOwner = new HashMap<>();
        for (Old o : olds) {
            if (o.owner == null) {
                Claim c = new Claim(null, "Server", o.created, Long.MAX_VALUE);
                c.settings.applyServerDefaults();
                c.chunks.add(o.key);
                register(c);
            } else {
                perOwner.computeIfAbsent(o.owner, k -> new LinkedHashMap<>()).put(o.key, o);
            }
        }
        for (Map.Entry<UUID, Map<ChunkKey, Old>> e : perOwner.entrySet()) {
            Map<ChunkKey, Old> map = e.getValue();
            Set<ChunkKey> seen = new HashSet<>();
            for (Old start : new ArrayList<>(map.values())) {
                if (seen.contains(start.key)) {
                    continue;
                }
                List<Old> group = new ArrayList<>();
                Deque<ChunkKey> queue = new ArrayDeque<>();
                queue.add(start.key);
                seen.add(start.key);
                while (!queue.isEmpty()) {
                    ChunkKey k = queue.poll();
                    group.add(map.get(k));
                    for (ChunkKey n : neighbors(k)) {
                        if (map.containsKey(n) && seen.add(n)) {
                            queue.add(n);
                        }
                    }
                }
                double pool = 0;
                long created = Long.MAX_VALUE;
                long latestPaid = 0;
                Claim c = new Claim(e.getKey(), start.name, now, now);
                for (Old o : group) {
                    pool += Math.max(0, o.paid - now) / (double) HOUR_MS;
                    created = Math.min(created, o.created);
                    latestPaid = Math.max(latestPaid, o.paid);
                    for (var te : o.trusted.entries().entrySet()) {
                        c.trusted.set(te.getKey(), te.getValue());
                    }
                    c.chunks.add(o.key);
                }
                c.created = created;
                if (pool > 0) {
                    long remaining = (long) (pool / rate(c) * HOUR_MS);
                    c.paidUntil = now + Math.min(remaining, maxPrepaidMs());
                } else {
                    c.paidUntil = latestPaid;
                }
                register(c);
            }
        }
        plugin.getLogger().info("Converted " + olds.size() + " old claims. Backup saved as claims-old-format.yml.");
        markDirty();
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
            m.put("chunks", c.chunks.stream().map(ClaimManager::keyString).toList());
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

    // ----------------------------------------------------------------- lookups

    public ChunkKey keyOf(Location l) {
        return new ChunkKey(l.getWorld().getUID(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }

    public Claim at(Location l) {
        return l.getWorld() == null ? null : byChunk.get(keyOf(l));
    }

    public Claim at(Block b) {
        return byChunk.get(new ChunkKey(b.getWorld().getUID(), b.getX() >> 4, b.getZ() >> 4));
    }

    public Claim get(ChunkKey key) {
        return byChunk.get(key);
    }

    /** The player's territories, oldest first. */
    /** All territories, for admin listings. */
    public Set<Claim> all() {
        return territories;
    }

    /**
     * Finds a territory by its custom name, the owner's name (case insensitive), or a
     * "x,z" chunk id as reported by /claims list. Used by the optional [territory]
     * argument so admins can target a claim they are not standing in.
     */
    public Claim findTerritory(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim();
        String lower = q.toLowerCase(java.util.Locale.ROOT);
        // "x,z" id
        if (q.contains(",")) {
            String[] parts = q.split(",");
            if (parts.length == 2) {
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int z = Integer.parseInt(parts[1].trim());
                    for (Claim c : territories) {
                        for (ChunkKey k : c.chunks) {
                            if (k.x() == x && k.z() == z) {
                                return c;
                            }
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
                .thenComparingInt((Claim c) -> c.chunks.iterator().next().x())
                .thenComparingInt((Claim c) -> c.chunks.iterator().next().z()));
        return list;
    }

    /** Chunks next to this one (sides only, or sides and corners if connect-diagonals is true). */
    public List<ChunkKey> neighbors(ChunkKey k) {
        boolean diagonals = plugin.getConfig().getBoolean("connect-diagonals", false);
        List<ChunkKey> out = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!diagonals && dx != 0 && dz != 0) {
                    continue;
                }
                out.add(new ChunkKey(k.world(), k.x() + dx, k.z() + dz));
            }
        }
        return out;
    }

    /** The owner's territories that touch this chunk. */
    public Set<Claim> adjacentOwned(UUID owner, ChunkKey key) {
        Set<Claim> out = new LinkedHashSet<>();
        for (ChunkKey n : neighbors(key)) {
            Claim c = byChunk.get(n);
            if (c != null && owner.equals(c.owner)) {
                out.add(c);
            }
        }
        return out;
    }

    private boolean connected(Set<ChunkKey> set) {
        if (set.isEmpty()) {
            return true;
        }
        Set<ChunkKey> seen = new HashSet<>();
        Deque<ChunkKey> queue = new ArrayDeque<>();
        ChunkKey first = set.iterator().next();
        queue.add(first);
        seen.add(first);
        while (!queue.isEmpty()) {
            ChunkKey k = queue.poll();
            for (ChunkKey n : neighbors(k)) {
                if (set.contains(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return seen.size() == set.size();
    }

    public String worldName(ChunkKey key) {
        World w = Bukkit.getWorld(key.world());
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
            // timeLeft() formats (expiry - now) in epoch milliseconds; no tick/second mix.
            return "<green>Active <gray>- <white>" + Util.timeLeft(c.paidUntil) + " <gray>left";
        }
        if (c.paidUntil + graceMs() > System.currentTimeMillis()) {
            return "<red>Overdue <gray>- removed in <white>"
                    + Util.timeLeft(c.paidUntil + graceMs());
        }
        return "<dark_red>Expired";
    }

    // -------------------------------------------------------------- operations

    public void claimHere(Player p) {
        Location l = p.getLocation();
        if (!worldEnabled(l.getWorld())) {
            plugin.send(p, "world-disabled");
            return;
        }
        ChunkKey key = keyOf(l);
        Claim existing = byChunk.get(key);
        if (existing != null) {
            if (p.getUniqueId().equals(existing.owner)) {
                plugin.send(p, "already-yours");
            } else {
                plugin.send(p, "already-claimed", "owner", existing.isServer() ? "the server" : existing.ownerName);
            }
            return;
        }
        int limit = limitFor(p);
        if (ownedChunks(p.getUniqueId()) >= limit) {
            plugin.send(p, "limit-reached", "limit", String.valueOf(limit));
            return;
        }
        Set<Claim> adjacent = adjacentOwned(p.getUniqueId(), key);
        int newSize = 1;
        Claim base = null;
        for (Claim a : adjacent) {
            newSize += a.size();
            if (base == null || a.created < base.created) {
                base = a;
            }
        }
        // Settings of the merged territory: a paid protection stays on if any part had it.
        TerritorySettings merged = base == null ? plugin.settings().defaults() : base.settings.copy();
        for (Claim a : adjacent) {
            if (a != base) {
                merged.absorb(a.settings);
            }
        }
        // The merged territory pays for every protection any part of it had bought.
        double factor = 1.0 + plugin.settings().upkeepMultiplier() * merged.purchasedPaidCount();
        double f = newSize * perChunkMultiplier(newSize) * factor;
        double price = plugin.getConfig().getDouble("initial-hours", 96);
        long now = System.currentTimeMillis();
        // Upkeep already banked in the territories this chunk joins.
        double carried = 0;
        for (Claim a : adjacent) {
            long left = a.paidUntil - now;
            if (left > 0) {
                carried += (left / (double) HOUR_MS) * rate(a);
            }
        }
        // Hours this territory can still hold: the cap minus whatever is already carried.
        double roomHours = Math.max(0, maxPrepaidMs() / (double) HOUR_MS - carried / f);
        if (roomHours <= 0) {
            plugin.send(p, "max-prepaid");
            return;
        }
        double wantHours = Math.min(price / f, roomHours);
        // Raw item value needed is exactly the claim price, whatever the territory size.
        // The room ceiling stops whole-item rounding from crediting time that gets truncated.
        PaymentService.Result r = plugin.payments().charge(p, wantHours, f,
                plugin.payments().choiceOf(p), roomHours);
        if (!r.ok()) {
            plugin.sendMini(p, r.error());
            return;
        }
        double paidRaw = r.credited() * f;
        double pool = paidRaw + carried;
        long remaining = Math.min((long) (pool / f * HOUR_MS), maxPrepaidMs());

        Claim target;
        if (base == null) {
            target = new Claim(p.getUniqueId(), p.getName(), now, now + remaining);
            copySettings(plugin.settings().defaults(), target.settings);
        } else {
            target = base;
            for (Claim other : adjacent) {
                if (other == base) {
                    continue;
                }
                target.chunks.addAll(other.chunks);
                for (var e : other.trusted.entries().entrySet()) {
                    TrustList.Level current = target.trusted.levelOf(e.getKey());
                    if (e.getValue().weight > current.weight) {
                        target.trusted.set(e.getKey(), e.getValue());
                    }
                }
                target.created = Math.min(target.created, other.created);
                target.settings.absorb(other.settings);
                territories.remove(other);
            }
        }
        target.chunks.add(key);
        target.ownerName = p.getName();
        target.paidUntil = now + remaining;
        register(target);
        markDirty();
        plugin.send(p, "claim-success", "time", Util.duration(remaining), "size", String.valueOf(target.size()));
    }

    /** hours <= 0 means "fill up to the maximum". Pays for the whole territory. */
    public void payHere(Player p, ChunkKey key, double hours) {
        Claim c = byChunk.get(key);
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
        // Pass the remaining room as a hard ceiling so whole-item rounding cannot credit
        // more upkeep than the territory can hold, which would discard paid-for items.
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

    public void unclaim(Player p, ChunkKey key) {
        Claim c = byChunk.get(key);
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        if (!plugin.settings().isOwnerOrAdmin(p, c)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        if (c.size() == 1) {
            removeTerritory(c);
            markDirty();
            plugin.send(p, "unclaim-success");
            return;
        }
        Set<ChunkKey> rest = new LinkedHashSet<>(c.chunks);
        rest.remove(key);
        if (!connected(rest)) {
            plugin.send(p, "unclaim-split");
            return;
        }
        long now = System.currentTimeMillis();
        boolean active = c.paidUntil > now;
        double pool = active ? ((c.paidUntil - now) / (double) HOUR_MS) * rate(c) : 0;
        c.chunks.remove(key);
        byChunk.remove(key, c);
        if (active) {
            long remaining = (long) (pool / rate(c) * HOUR_MS);
            c.paidUntil = now + Math.min(remaining, maxPrepaidMs());
        }
        markDirty();
        plugin.send(p, "unclaim-success");
    }

    public void trust(Player p, ChunkKey key, UUID target, String name) {
        trust(p, key, target, name, null);
    }

    /** Grants (or upgrades) trust. A null level means Builder. */
    public void trust(Player p, ChunkKey key, UUID target, String name, TrustList.Level level) {
        Claim c = byChunk.get(key);
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

    public void untrust(Player p, ChunkKey key, UUID target, String name) {
        Claim c = byChunk.get(key);
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
     *       clock and is refused outright when less than 24 hours remain. The purchase also
     *       raises the upkeep cost permanently, which is why the same items buy less time
     *       from then on. The 24 hours are the whole price: the cost rise is not also
     *       charged against the banked time, so the clock loses 24 hours and no more.</li>
     *   <li><b>Toggling</b> a bought protection is free: no hours are taken, none are
     *       given back, and the upkeep cost does not move. Only whether the listeners
     *       enforce it changes.</li>
     * </ul>
     *
     * Returns true when the change was applied.
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
        boolean admin = svc.roleOf(p, c) == SettingsService.Role.ADMIN;
        // Buying happens once: only when switching a protection that is not owned yet ON.
        // Admins switch protections without paying, and are not marked as buyers.
        boolean buying = flag.isPaid() && enabled && !admin && !c.settings.isPurchased(flag.id);
        if (buying) {
            if (remainingMs(c) < PROTECTION_COST_MS) {
                plugin.send(p, "flag-need-upkeep", "flag", flag.title,
                        "time", Util.duration(remainingMs(c)));
                return false;
            }
            // The purchase both owns the protection and switches it on, so the cost change
            // is part of the same step.
            c.settings.markPurchased(flag.id);
            if (flag == SettingsService.Flag.PVP
                    && c.settings.pvpScope == TerritorySettings.PvpScope.EVERYONE) {
                c.settings.pvpScope = TerritorySettings.PvpScope.NOBODY;
            }
            svc.set(c, flag, true);
            // Take exactly 24 hours off the clock. The new higher upkeep rate is NOT
            // re-priced into the banked time here, or the purchase would cost 24 hours plus
            // whatever the cost rise eats. The rise already bites on every future payment,
            // because the same items now buy less time at the higher rate.
            long now = System.currentTimeMillis();
            c.paidUntil = Math.max(now, c.paidUntil - PROTECTION_COST_MS);
            markDirty();
            // One message only: it already says the protection is now on.
            plugin.send(p, "flag-purchased", "flag", flag.title, "hours", "24");
            return true;
        }
        if (flag == SettingsService.Flag.PVP && enabled
                && c.settings.pvpScope == TerritorySettings.PvpScope.EVERYONE) {
            // "Everyone may fight" is no protection at all, so protecting starts at "nobody".
            c.settings.pvpScope = TerritorySettings.PvpScope.NOBODY;
        }
        // Free toggle: switch it, take nothing, give nothing back. No rebase here, because
        // the upkeep rate is driven by ownership, which this does not change.
        svc.set(c, flag, enabled);
        markDirty();
        plugin.send(p, "flag-changed", "flag", flag.title, "value", enabled ? "on" : "off");
        return true;
    }

    /**
     * The single PvP control. EVERYONE means no protection. Any other choice protects,
     * which costs 24 hours of upkeep the first time only.
     *
     * Once PvP is bought, moving between scopes is free in every direction, including
     * back to EVERYONE and in again: the purchase and its cost increase are permanent.
     */
    public boolean setPvpScope(Player p, Claim c, TerritorySettings.PvpScope scope) {
        SettingsService svc = plugin.settings();
        String refuse = svc.refusal(p, c, SettingsService.Flag.PVP);
        if (refuse != null) {
            plugin.sendMini(p, refuse);
            return false;
        }
        if (scope == TerritorySettings.PvpScope.EVERYONE) {
            // No protection at all: switch it off (free, keeps the purchase and the cost).
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
        plugin.send(p, "flag-changed", "flag", field.name().toLowerCase(java.util.Locale.ROOT),
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
        // Switch the protections off but keep them purchased, so paying upkeep restores
        // them for free. No rebase: the upkeep cost is locked in by ownership, so dropping
        // these must not hand the owner back any hours.
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

    public void serverClaim(Player p, int radius) {
        ChunkKey center = keyOf(p.getLocation());
        long now = System.currentTimeMillis();
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkKey k = new ChunkKey(center.world(), center.x() + dx, center.z() + dz);
                if (byChunk.containsKey(k)) {
                    continue;
                }
                Claim c = new Claim(null, "Server", now, Long.MAX_VALUE);
                c.settings.applyServerDefaults();
                c.chunks.add(k);
                register(c);
                count++;
            }
        }
        markDirty();
        plugin.send(p, "server-claimed", "count", String.valueOf(count));
    }

    /** Removes the whole territory the admin is standing in. */
    public void adminUnclaim(Player p) {
        Claim c = byChunk.get(keyOf(p.getLocation()));
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        removeTerritory(c);
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
            // Overdue: paid protections switch off at once (they are not being paid for).
            // They are remembered as bought, so they can be switched back on for free.
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
            ChunkKey first = c.chunks.iterator().next();
            removeTerritory(c);
            log.append(Instant.now()).append(" expired owner=").append(c.ownerName)
                    .append(" world=").append(worldName(first))
                    .append(" chunks=").append(c.size())
                    .append(" first=").append(first.x()).append(',').append(first.z()).append('\n');
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner != null) {
                plugin.send(owner, "claim-expired", "x", String.valueOf(first.x()), "z", String.valueOf(first.z()),
                        "size", String.valueOf(c.size()));
            }
        }
        markDirty();
        try {
            plugin.getDataFolder().mkdirs();
            Files.writeString(new File(plugin.getDataFolder(), "expired-claims.log").toPath(), log.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
