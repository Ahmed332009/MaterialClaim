package dev.keepclaims;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A horizontal land area: an axis-aligned rectangle of blocks in one world.
 *
 * Coordinates are inclusive block coordinates, so a 1x1 selection has minX == maxX.
 * Claims cover the whole vertical column, so no Y is stored: like GriefPrevention, a
 * claim owns its land from bedrock to build limit.
 */
public record Area(UUID world, int minX, int minZ, int maxX, int maxZ) {

    public Area {
        // Normalise so callers may pass corners in any order.
        if (minX > maxX) {
            int t = minX;
            minX = maxX;
            maxX = t;
        }
        if (minZ > maxZ) {
            int t = minZ;
            minZ = maxZ;
            maxZ = t;
        }
    }

    /** Builds a rectangle from two arbitrary corners. */
    public static Area of(UUID world, int x1, int z1, int x2, int z2) {
        return new Area(world, Math.min(x1, x2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(z1, z2));
    }

    /** Width in blocks along X. */
    public int width() {
        return maxX - minX + 1;
    }

    /** Depth in blocks along Z. */
    public int depth() {
        return maxZ - minZ + 1;
    }

    /** Number of blocks in the column footprint. */
    public long blocks() {
        return (long) width() * (long) depth();
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** True when the two rectangles share at least one block. */
    public boolean intersects(Area o) {
        return world.equals(o.world)
                && minX <= o.maxX && maxX >= o.minX
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    /** True when they share a side, i.e. touching but not overlapping. */
    public boolean touches(Area o) {
        if (!world.equals(o.world)) {
            return false;
        }
        boolean xOverlap = minX <= o.maxX && maxX >= o.minX;
        boolean zOverlap = minZ <= o.maxZ && maxZ >= o.minZ;
        boolean sideBySide = (maxX + 1 == o.minX || o.maxX + 1 == minX) && zOverlap;
        boolean endToEnd = (maxZ + 1 == o.minZ || o.maxZ + 1 == minZ) && xOverlap;
        return sideBySide || endToEnd;
    }

    /** True when they overlap or touch: the union is one connected piece of land. */
    public boolean connectedTo(Area o) {
        return intersects(o) || touches(o);
    }

    /** `a` minus `b`, as up to four rectangles that cover exactly the leftover blocks. */
    public static List<Area> subtract(Area a, Area b) {
        List<Area> out = new ArrayList<>(4);
        if (!a.intersects(b)) {
            out.add(a);
            return out;
        }
        // Strip the part above, below, left and right of the overlap.
        if (a.minZ < b.minZ) {
            out.add(new Area(a.world, a.minX, a.minZ, a.maxX, b.minZ - 1));
        }
        if (a.maxZ > b.maxZ) {
            out.add(new Area(a.world, a.minX, b.maxZ + 1, a.maxX, a.maxZ));
        }
        int zFrom = Math.max(a.minZ, b.minZ);
        int zTo = Math.min(a.maxZ, b.maxZ);
        if (a.minX < b.minX) {
            out.add(new Area(a.world, a.minX, zFrom, b.minX - 1, zTo));
        }
        if (a.maxX > b.maxX) {
            out.add(new Area(a.world, b.maxX + 1, zFrom, a.maxX, zTo));
        }
        return out;
    }

    /** Two rectangles side by side or end to end that form a single larger rectangle. */
    private static Area joinable(Area a, Area b) {
        if (!a.world.equals(b.world)) {
            return null;
        }
        boolean sameX = a.minX == b.minX && a.maxX == b.maxX;
        boolean sameZ = a.minZ == b.minZ && a.maxZ == b.maxZ;
        if (sameX) {
            if (a.maxZ + 1 == b.minZ || b.maxZ + 1 == a.minZ) {
                return new Area(a.world, a.minX, Math.min(a.minZ, b.minZ),
                        a.maxX, Math.max(a.maxZ, b.maxZ));
            }
        }
        if (sameZ) {
            if (a.maxX + 1 == b.minX || b.maxX + 1 == a.minX) {
                return new Area(a.world, Math.min(a.minX, b.minX), a.minZ,
                        Math.max(a.maxX, b.maxX), a.maxZ);
            }
        }
        return null;
    }

    /**
     * Adds `add` to a list of non-overlapping rectangles, merging every rectangle that can
     * become one bigger rectangle. The result covers exactly the union, so block counts
     * never double-count overlapping land.
     */
    public static List<Area> addAll(List<Area> existing, Area add) {
        List<Area> work = new ArrayList<>();
        for (Area a : existing) {
            work.addAll(subtract(a, add));
        }
        work.add(add);
        return normalise(work);
    }

    /** Repeatedly merges pairs that form a single rectangle, until nothing changes. */
    private static List<Area> normalise(List<Area> in) {
        List<Area> work = new ArrayList<>(in);
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < work.size(); i++) {
                for (int j = i + 1; j < work.size(); j++) {
                    Area merged = joinable(work.get(i), work.get(j));
                    if (merged != null) {
                        work.remove(j);
                        work.remove(i);
                        work.add(merged);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
        work.removeIf(a -> a.blocks() <= 0);
        return work;
    }

    /** Sum of the block footprints of a set of non-overlapping rectangles. */
    public static long totalBlocks(List<Area> areas) {
        long n = 0;
        for (Area a : areas) {
            n += a.blocks();
        }
        return n;
    }

    /** The smallest rectangle covering every area in the list. */
    public static Area bounds(List<Area> areas) {
        if (areas.isEmpty()) {
            return null;
        }
        Area first = areas.get(0);
        int minX = first.minX;
        int minZ = first.minZ;
        int maxX = first.maxX;
        int maxZ = first.maxZ;
        for (Area a : areas) {
            minX = Math.min(minX, a.minX);
            minZ = Math.min(minZ, a.minZ);
            maxX = Math.max(maxX, a.maxX);
            maxZ = Math.max(maxZ, a.maxZ);
        }
        return new Area(first.world, minX, minZ, maxX, maxZ);
    }

    /** "x1,z1 to x2,z2" for messages. */
    public String describe() {
        return minX + "," + minZ + " to " + maxX + "," + maxZ;
    }
}
