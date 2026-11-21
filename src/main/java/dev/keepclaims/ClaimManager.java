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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Holds every claim, saves them to claims.yml and runs the claim/pay/trust operations. */
public final class ClaimManager {
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    private final KeepClaims plugin;
    private final Map<ChunkKey, Claim> claims = new ConcurrentHashMap<>();
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
        for (Map<?, ?> m : plugin.getConfig().getMapList("cost-tiers")) {
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

    public double multiplierFor(int ordinal) {
        for (double[] t : tiers) {
            if (ordinal <= t[0]) {
                return Math.max(0.01, t[1]);
            }
        }
        return Math.max(0.01, tiers.get(tiers.size() - 1)[1]);
    }

    private long graceMs() {
        return (long) (plugin.getConfig().getDouble("grace-days", 7) * DAY_MS);
    }

    private long maxPrepaidMs() {
        return (long) (plugin.getConfig().getDouble("max-prepaid-days", 30) * DAY_MS);
    }

    /** The maximum prepaid upkeep in hours, i.e. the cap a single claim can hold. */
    public double maxPrepaidHours() {
        return maxPrepaidMs() / (double) HOUR_MS;
    }

    /**
     * How many hours of upkeep this claim can still take before hitting the prepaid cap.
     * Never negative.
     */
    public double roomHours(Claim c) {
        if (c == null || c.isServer()) {
            return 0.0;
        }
        long now = System.currentTimeMillis();
        long base = Math.max(now, c.paidUntil);
        return Math.max(0.0, (now + maxPrepaidMs() - base) / (double) HOUR_MS);
    }

    public boolean worldEnabled(World w) {
        List<String> list = plugin.getConfig().getStringList("enabled-worlds");
        return list.isEmpty() || list.contains(w.getName());
    }

    public int limitFor(Player p) {
        int limit = plugin.getConfig().getInt("max-claims-default", 25);
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("keepclaims.limit.")) {
                try {
                    limit = Math.max(limit, Integer.parseInt(perm.substring("keepclaims.limit.".length())));
                } catch (NumberFormatException ignored) {
                    // not a number, skip
                }
            }
        }
        return limit;
    }

    // ----------------------------------------------------------------- storage

    public void load() {
        claims.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : y.getMapList("claims")) {
            try {
                UUID world = UUID.fromString(String.valueOf(m.get("world")));
                int x = ((Number) m.get("x")).intValue();
                int z = ((Number) m.get("z")).intValue();
                String ownerText = String.valueOf(m.get("owner"));
                UUID owner = ownerText.equals("SERVER") ? null : UUID.fromString(ownerText);
                Object name = m.get("owner-name");
                long created = ((Number) m.get("created")).longValue();
                long paid = ((Number) m.get("paid-until")).longValue();
                Claim c = new Claim(new ChunkKey(world, x, z), owner,
                        name == null ? "Server" : String.valueOf(name), created, paid);
                Object trusted = m.get("trusted");
                if (trusted instanceof List<?> list) {
                    for (Object t : list) {
                        c.trusted.add(UUID.fromString(String.valueOf(t)));
                    }
                }
                claims.put(c.key, c);
            } catch (Exception ex) {
                plugin.getLogger().warning("Skipping a bad claim entry: " + ex.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + claims.size() + " claims.");
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
        for (Claim c : claims.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", c.key.world().toString());
            m.put("x", c.key.x());
            m.put("z", c.key.z());
            m.put("owner", c.owner == null ? "SERVER" : c.owner.toString());
            m.put("owner-name", c.ownerName);
            m.put("created", c.created);
            m.put("paid-until", c.paidUntil);
            m.put("trusted", c.trusted.stream().map(UUID::toString).toList());
            out.add(m);
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("claims", out);
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
        return l.getWorld() == null ? null : claims.get(keyOf(l));
    }

    public Claim at(Block b) {
        return claims.get(new ChunkKey(b.getWorld().getUID(), b.getX() >> 4, b.getZ() >> 4));
    }

    public Claim get(ChunkKey key) {
        return claims.get(key);
    }

    public List<Claim> byOwner(UUID owner) {
        List<Claim> list = new ArrayList<>();
        for (Claim c : claims.values()) {
            if (owner.equals(c.owner)) {
                list.add(c);
            }
        }
        list.sort(Comparator.comparingLong((Claim c) -> c.created)
                .thenComparingInt((Claim c) -> c.key.x())
                .thenComparingInt((Claim c) -> c.key.z()));
        return list;
    }

    public int ordinalOf(Claim c) {
        List<Claim> list = byOwner(c.owner);
        int i = list.indexOf(c);
        return i < 0 ? list.size() : i + 1;
    }

    public String worldName(ChunkKey key) {
        World w = Bukkit.getWorld(key.world());
        return w == null ? "unknown" : w.getName();
    }

    public boolean sameOwner(Claim a, Claim b) {
        return a != null && b != null && java.util.Objects.equals(a.owner, b.owner);
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
        UUID id = p.getUniqueId();
        return id.equals(c.owner) || c.trusted.contains(id);
    }

    public long remainingMs(Claim c) {
        return c.paidUntil - System.currentTimeMillis();
    }

    /** MiniMessage text describing the upkeep state of a claim. */
    public String statusLine(Claim c) {
        if (c.isServer()) {
            return "<aqua>Server protected";
        }
        long left = remainingMs(c);
        if (left > 0) {
            return "<green>Active <gray>- <white>" + Util.duration(left) + " <gray>left";
        }
        long graceLeft = c.paidUntil + graceMs() - System.currentTimeMillis();
        if (graceLeft > 0) {
            return "<red>Overdue <gray>- removed in <white>" + Util.duration(graceLeft);
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
        Claim existing = claims.get(key);
        if (existing != null) {
            if (p.getUniqueId().equals(existing.owner)) {
                plugin.send(p, "already-yours");
            } else {
                plugin.send(p, "already-claimed", "owner", existing.isServer() ? "the server" : existing.ownerName);
            }
            return;
        }
        List<Claim> mine = byOwner(p.getUniqueId());
        int limit = limitFor(p);
        if (mine.size() >= limit) {
            plugin.send(p, "limit-reached", "limit", String.valueOf(limit));
            return;
        }
        double mult = multiplierFor(mine.size() + 1);
        // Never ask for more upkeep than the claim can hold; charge() would clamp it
        // anyway, and the player would be quoted a price we would not honour.
        double maxInitialHours = maxPrepaidHours();
        double hours = Math.min(plugin.getConfig().getDouble("initial-hours", 168), maxInitialHours);
        PaymentService.Result r = plugin.payments().charge(p, hours, mult,
                plugin.payments().choiceOf(p), maxInitialHours);
        if (!r.ok()) {
            plugin.sendMini(p, r.error());
            return;
        }
        long now = System.currentTimeMillis();
        long until = now + Math.min((long) (r.credited() * HOUR_MS), maxPrepaidMs());
        Claim c = new Claim(key, p.getUniqueId(), p.getName(), now, until);
        claims.put(key, c);
        markDirty();
        plugin.send(p, "claim-success", "time", Util.duration(until - now));
    }

    /** hours <= 0 means "fill up to the maximum". */
    public void payHere(Player p, ChunkKey key, double hours) {
        Claim c = claims.get(key);
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        if (!p.getUniqueId().equals(c.owner)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        long now = System.currentTimeMillis();
        long base = Math.max(now, c.paidUntil);
        long cap = now + maxPrepaidMs();
        double allowedHours = roomHours(c);
        if (allowedHours <= 0 || base >= cap - 60_000L) {
            plugin.send(p, "max-prepaid");
            return;
        }
        if (hours <= 0 || hours > allowedHours) {
            hours = allowedHours;
        }
        double mult = multiplierFor(ordinalOf(c));
        // Pass the remaining room as a hard ceiling: charge() shrinks the payment to the
        // largest whole-item amount that fits, so no paid-for value is ever discarded.
        PaymentService.Result r = plugin.payments().charge(p, hours, mult,
                plugin.payments().choiceOf(p), allowedHours);
        if (!r.ok()) {
            plugin.sendMini(p, r.error());
            return;
        }
        long newUntil = base + (long) (r.credited() * HOUR_MS);
        if (newUntil > cap) {
            // charge() is ceiling-aware, so this should be unreachable; clamp defensively
            // and say so rather than silently dropping value.
            newUntil = cap;
            plugin.getLogger().warning("Upkeep clamp triggered for " + p.getName()
                    + " despite a ceiling-aware charge; credited=" + r.credited());
        }
        c.paidUntil = newUntil;
        markDirty();
        plugin.send(p, "pay-success", "time", Util.duration(newUntil - now));
    }

    public void unclaim(Player p, ChunkKey key) {
        Claim c = claims.get(key);
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        if (!p.getUniqueId().equals(c.owner)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        claims.remove(key);
        markDirty();
        plugin.send(p, "unclaim-success");
    }

    public void trust(Player p, ChunkKey key, UUID target, String name) {
        Claim c = claims.get(key);
        if (c == null || !p.getUniqueId().equals(c.owner)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        if (target.equals(p.getUniqueId())) {
            plugin.send(p, "trust-self");
            return;
        }
        if (!c.trusted.add(target)) {
            plugin.send(p, "already-trusted", "name", name);
            return;
        }
        markDirty();
        plugin.send(p, "trust-added", "name", name);
    }

    public void untrust(Player p, ChunkKey key, UUID target, String name) {
        Claim c = claims.get(key);
        if (c == null || !p.getUniqueId().equals(c.owner)) {
            plugin.send(p, "not-your-claim");
            return;
        }
        if (c.trusted.remove(target)) {
            markDirty();
            plugin.send(p, "trust-removed", "name", name);
        }
    }

    // ------------------------------------------------------------------- admin

    public void serverClaim(Player p, int radius) {
        ChunkKey center = keyOf(p.getLocation());
        long now = System.currentTimeMillis();
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkKey k = new ChunkKey(center.world(), center.x() + dx, center.z() + dz);
                if (claims.containsKey(k)) {
                    continue;
                }
                claims.put(k, new Claim(k, null, "Server", now, Long.MAX_VALUE));
                count++;
            }
        }
        markDirty();
        plugin.send(p, "server-claimed", "count", String.valueOf(count));
    }

    public void adminUnclaim(Player p) {
        Claim c = claims.remove(keyOf(p.getLocation()));
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        markDirty();
        plugin.send(p, "admin-unclaimed");
    }

    // ------------------------------------------------------------------ expiry

    public void expireCheck() {
        long now = System.currentTimeMillis();
        long grace = graceMs();
        List<Claim> dead = new ArrayList<>();
        for (Claim c : claims.values()) {
            if (!c.isServer() && now > c.paidUntil + grace) {
                dead.add(c);
            }
        }
        if (dead.isEmpty()) {
            return;
        }
        StringBuilder log = new StringBuilder();
        for (Claim c : dead) {
            claims.remove(c.key);
            log.append(Instant.now()).append(" expired owner=").append(c.ownerName)
                    .append(" world=").append(worldName(c.key))
                    .append(" chunk=").append(c.key.x()).append(',').append(c.key.z()).append('\n');
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner != null) {
                plugin.send(owner, "claim-expired", "x", String.valueOf(c.key.x()), "z", String.valueOf(c.key.z()));
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

    /** Number of the player's claims with less than warn-days of upkeep left. */
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
