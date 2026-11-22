package dev.keepclaims;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Works out what a selection of land costs, in hours of upkeep.
 *
 * Everything is driven by config.yml so the economy can be tuned without code changes:
 *
 * <ul>
 *   <li><b>flat</b> (default) - the price is strictly proportional to area. One 16x16
 *       chunk is worth `claim-price-per-chunk-hours`, so a single block is that price
 *       divided by 256. Prices are exactly as they were before claims became block based.</li>
 *   <li><b>tiered</b> - the per-block price is multiplied by a factor taken from
 *       `claim-price-tiers`, chosen by how many blocks are being claimed. Bigger claims
 *       can therefore be cheaper per block, or more expensive, purely by config.</li>
 * </ul>
 *
 * The same tier table is also used for the ongoing upkeep rate (`upkeep-tiers`), so size
 * can affect both the one-off claim price and the hourly drain independently.
 */
public final class ClaimPricing {
    private static final long HOUR_MS = 3_600_000L;
    /** One old chunk, the unit the historical prices were defined in. */
    public static final int BLOCKS_PER_CHUNK = 256;

    public enum Mode {
        FLAT("flat"), TIERED("tiered");

        final String id;

        Mode(String id) {
            this.id = id;
        }

        static Mode parse(String raw) {
            if (raw != null && raw.trim().equalsIgnoreCase("tiered")) {
                return TIERED;
            }
            return FLAT;
        }

        public String id() {
            return id;
        }
    }

    private final KeepClaims plugin;

    private Mode mode = Mode.FLAT;
    private double perChunkHours = 96.0;
    private double minimumHours = 0.0;
    private int minimumBlocks = 100;
    private int maxBlocks = 0;
    private final List<double[]> priceTiers = new ArrayList<>();
    private final List<double[]> upkeepTiers = new ArrayList<>();

    public ClaimPricing(KeepClaims plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        var cfg = plugin.getConfig();
        mode = Mode.parse(cfg.getString("claim-price.mode", "flat"));
        perChunkHours = cfg.getDouble("claim-price.per-chunk-hours", 96.0);
        if (perChunkHours <= 0) {
            perChunkHours = 96.0;
        }
        minimumHours = Math.max(0.0, cfg.getDouble("claim-price.minimum-hours", 0.0));
        minimumBlocks = Math.max(1, cfg.getInt("claim-price.minimum-blocks", 100));
        maxBlocks = Math.max(0, cfg.getInt("claim-price.maximum-blocks", 0));
        readTiers(cfg.getMapList("claim-price.tiers"), priceTiers);
        readTiers(cfg.getMapList("upkeep-tiers"), upkeepTiers);
        if (upkeepTiers.isEmpty()) {
            // Backwards compatibility: the old chunk-based group-upkeep table still works.
            // Its "up-to" is in chunks, so it is converted to blocks.
            for (Map<?, ?> m : cfg.getMapList("group-upkeep")) {
                Object upTo = m.get("up-to");
                Object mult = m.get("multiplier");
                if (upTo instanceof Number a && mult instanceof Number b) {
                    upkeepTiers.add(new double[]{a.doubleValue() * BLOCKS_PER_CHUNK, b.doubleValue()});
                }
            }
            upkeepTiers.sort(Comparator.comparingDouble(t -> t[0]));
        }
    }

    private void readTiers(List<Map<?, ?>> raw, List<double[]> out) {
        out.clear();
        for (Map<?, ?> m : raw) {
            Object upTo = m.get("up-to-blocks");
            Object mult = m.get("multiplier");
            if (upTo instanceof Number a && mult instanceof Number b) {
                out.add(new double[]{a.doubleValue(), b.doubleValue()});
            }
        }
        out.sort(Comparator.comparingDouble(t -> t[0]));
    }

    public Mode mode() {
        return mode;
    }

    public int minimumBlocks() {
        return minimumBlocks;
    }

    /** 0 means no limit. */
    public int maxBlocks() {
        return maxBlocks;
    }

    /** The per-block hour price before any tier discount. */
    public double perBlockHours() {
        return perChunkHours / BLOCKS_PER_CHUNK;
    }

    /** Multiplier for a given area size from a tier table; 1.0 when the table is empty. */
    private double tierFactor(List<double[]> table, long blocks) {
        if (table.isEmpty() || mode == Mode.FLAT) {
            // In flat mode the price must stay strictly proportional, so tiers never bite
            // for pricing. Upkeep tiers are handled separately by ClaimManager.
            return 1.0;
        }
        for (double[] t : table) {
            if (blocks <= t[0]) {
                return Math.max(0.01, t[1]);
            }
        }
        return Math.max(0.01, table.get(table.size() - 1)[1]);
    }

    /** The claim price in hours for an area of the given block count. */
    public double priceHours(long blocks) {
        double raw = blocks * perBlockHours() * tierFactor(priceTiers, blocks);
        return Math.max(minimumHours, raw);
    }

    /** The claim price for a whole selection, in hours. */
    public double priceHours(Area area) {
        return priceHours(area.blocks());
    }

    /**
     * The upkeep multiplier for a territory of this many blocks. Mirrors the old per-chunk
     * group-upkeep table but is driven by block count, and is separate from the paid
     * protection multiplier applied by SettingsService.
     */
    public double upkeepGroupFactor(long blocks) {
        if (upkeepTiers.isEmpty()) {
            // No table: one old chunk's worth of blocks is one "unit", matching the legacy
            // behaviour where every chunk cost the same.
            return 1.0;
        }
        for (double[] t : upkeepTiers) {
            if (blocks <= t[0]) {
                return Math.max(0.01, t[1]);
            }
        }
        return Math.max(0.01, upkeepTiers.get(upkeepTiers.size() - 1)[1]);
    }

    /** Human readable pricing description for the menu. */
    public String describeMode() {
        if (mode == Mode.TIERED && !priceTiers.isEmpty()) {
            return "tiered by size";
        }
        return "flat per block";
    }

    /**
     * Hours of upkeep drained per hour by a territory of this many blocks, before paid
     * protections. One old chunk (256 blocks) drains 1.0 hour per hour, exactly as before:
     * a 96 hour claim on one chunk lasts 96 hours. The price per block and the drain per
     * block are deliberately different numbers.
     */
    public double upkeepRate(long blocks) {
        if (blocks <= 0) {
            return 0;
        }
        return (blocks / (double) BLOCKS_PER_CHUNK) * upkeepGroupFactor(blocks);
    }

    public long toMillis(double hours) {
        return (long) (hours * HOUR_MS);
    }
}
