package dev.keepclaims;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Trust levels for a territory.
 *
 * Visitor (0) is not stored: it is what everyone who is not on the list gets.
 * Builder (1) can place and break blocks. Manager (2) can also change free toggles
 * and pay upkeep.
 */
public final class TrustList {

    public enum Level {
        VISITOR(0, "visitor"),
        BUILDER(1, "builder"),
        MANAGER(2, "manager");

        public final int weight;
        public final String id;

        Level(int weight, String id) {
            this.weight = weight;
            this.id = id;
        }

        public static Level parse(String raw) {
            if (raw == null) {
                return BUILDER;
            }
            String s = raw.trim().toUpperCase(Locale.ROOT);
            return switch (s) {
                case "VISITOR", "0" -> VISITOR;
                case "MANAGER", "2" -> MANAGER;
                default -> BUILDER;
            };
        }

        public static Level of(int weight) {
            for (Level l : values()) {
                if (l.weight == weight) {
                    return l;
                }
            }
            return BUILDER;
        }
    }

    private final Map<UUID, Level> levels = new LinkedHashMap<>();

    public Level levelOf(UUID id) {
        return levels.getOrDefault(id, Level.VISITOR);
    }

    public boolean has(UUID id) {
        return levels.containsKey(id);
    }

    /** Sets a level. VISITOR removes the entry. Returns the previous level. */
    public Level set(UUID id, Level level) {
        Level prev = levelOf(id);
        if (level == Level.VISITOR) {
            levels.remove(id);
        } else {
            levels.put(id, level);
        }
        return prev;
    }

    public Level remove(UUID id) {
        return levels.remove(id);
    }

    public int size() {
        return levels.size();
    }

    public Map<UUID, Level> entries() {
        return levels;
    }

    public List<UUID> ids() {
        return new ArrayList<>(levels.keySet());
    }

    /**
     * Reads the stored "trusted" value. Accepts both the old flat list of UUIDs
     * (treated as builders) and the new map of UUID to level name.
     */
    public void readStored(Object stored) {
        levels.clear();
        if (stored instanceof List<?> list) {
            for (Object o : list) {
                try {
                    levels.put(UUID.fromString(String.valueOf(o)), Level.BUILDER);
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entry
                }
            }
        } else if (stored instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                try {
                    UUID id = UUID.fromString(String.valueOf(e.getKey()));
                    Level l = Level.parse(String.valueOf(e.getValue()));
                    if (l != Level.VISITOR) {
                        levels.put(id, l);
                    }
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entry
                }
            }
        }
    }

    /** Stored form: a map of UUID to level name. */
    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Level> e : levels.entrySet()) {
            out.put(e.getKey().toString(), e.getValue().id);
        }
        return out;
    }
}
