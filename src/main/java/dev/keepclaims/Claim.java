package dev.keepclaims;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A territory: one or more connected block rectangles owned by one player that share one
 * upkeep clock, one trust list and one set of settings.
 *
 * Land is stored as a set of non-overlapping rectangles covering exactly the claimed
 * blocks, so a claim can be any shape built from rectangles. owner == null means a server
 * claim (free, never expires).
 */
public final class Claim {
    public UUID owner;
    public String ownerName;
    public long created;
    public long paidUntil;
    public final TrustList trusted = new TrustList();
    /** Non-overlapping rectangles covering exactly this territory's blocks. */
    public final List<Area> areas = new ArrayList<>();
    public final TerritorySettings settings = new TerritorySettings();

    public Claim(UUID owner, String ownerName, long created, long paidUntil) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.created = created;
        this.paidUntil = paidUntil;
    }

    public boolean isServer() {
        return owner == null;
    }

    /** Total claimed blocks, counted once each. */
    public long blocks() {
        return Area.totalBlocks(areas);
    }

    /** Kept for call sites that only need a size for messages; this is blocks, not chunks. */
    public long size() {
        return blocks();
    }

    /** True when the block at x,z belongs to this territory. */
    public boolean contains(int x, int z) {
        for (Area a : areas) {
            if (a.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /** True when this territory holds any block inside the rectangle. */
    public boolean overlaps(Area probe) {
        for (Area a : areas) {
            if (a.intersects(probe)) {
                return true;
            }
        }
        return false;
    }

    /** The rectangle in `world` that best represents this territory, for messages. */
    public Area bounds() {
        return Area.bounds(areas);
    }

    /** A short id like "x,z" for listings: the minimum corner of the bounding box. */
    public String shortId() {
        Area b = bounds();
        return b == null ? "?,?" : b.minX() + "," + b.minZ();
    }

    /** World of this territory, or null when it has no land. */
    public UUID world() {
        return areas.isEmpty() ? null : areas.get(0).world();
    }

    /** Adds land, merging rectangles and never double counting overlaps. */
    public void addArea(Area area) {
        List<Area> merged = Area.addAll(areas, area);
        areas.clear();
        areas.addAll(merged);
    }

    /** Removes land, splitting rectangles as needed. */
    public void removeArea(Area area) {
        List<Area> rest = new ArrayList<>();
        for (Area a : areas) {
            rest.addAll(Area.subtract(a, area));
        }
        areas.clear();
        areas.addAll(rest);
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(owner) * 31 + created;
    }

    /** Name shown to players: the custom territory name if set, else the owner name. */
    public String displayName() {
        return settings.displayName(ownerName);
    }

    /** True when this player may build here (owner, builder or manager). */
    public boolean mayBuild(UUID id) {
        if (isServer()) {
            return false;
        }
        if (id.equals(owner)) {
            return true;
        }
        return trusted.levelOf(id).weight >= TrustList.Level.BUILDER.weight;
    }

    /** True when this player may change free settings and pay upkeep. */
    public boolean isManager(UUID id) {
        if (isServer()) {
            return false;
        }
        if (id.equals(owner)) {
            return true;
        }
        return trusted.levelOf(id).weight >= TrustList.Level.MANAGER.weight;
    }
}
