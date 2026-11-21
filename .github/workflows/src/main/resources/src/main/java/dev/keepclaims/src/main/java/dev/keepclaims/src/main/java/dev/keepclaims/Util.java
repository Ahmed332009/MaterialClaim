package dev.keepclaims;

import org.bukkit.Material;

final class Util {
    private Util() {}

    static String duration(long ms) {
        if (ms <= 0) return "0m";
        long minutes = ms / 60000L;
        long days = minutes / 1440L;
        minutes %= 1440L;
        long hours = minutes / 60L;
        minutes %= 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (days == 0 && minutes > 0) sb.append(minutes).append("m ");
        String out = sb.toString().trim();
        return out.isEmpty() ? "<1m" : out;
    }

    static String pretty(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }
}
