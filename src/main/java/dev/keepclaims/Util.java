package dev.keepclaims;

import org.bukkit.Material;

final class Util {
    private Util() {
    }

    /** 90061000 ms becomes "1d 1h". */
    static String duration(long ms) {
        if (ms <= 0) {
            return "0m";
        }
        long minutes = ms / 60000L;
        long days = minutes / 1440L;
        minutes %= 1440L;
        long hours = minutes / 60L;
        minutes %= 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (days == 0 && minutes > 0) {
            sb.append(minutes).append("m ");
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "<1m" : out;
    }

    static String pretty(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    /** One day in epoch milliseconds, written out so no unit maths can drift. */
    static final long DAY_MS = 86_400_000L;

    /** Exactly 24 hours of upkeep time: the one-time protection fee, in milliseconds. */
    static final long PROTECTION_FEE_MS = 24L * 60L * 60L * 1000L;

    /**
     * Adds one protection purchase (exactly 24 hours) to a territory clock. The clock is
     * always in epoch milliseconds and starts from whichever is later: now, or the time
     * already banked, so buying early never throws away paid time.
     */
    static long extendByProtectionFee(long currentExpiryMillis) {
        return Math.max(System.currentTimeMillis(), currentExpiryMillis) + PROTECTION_FEE_MS;
    }

    /**
     * Whole hours left until a clock expires, in hours and minutes. Both sides are epoch
     * milliseconds, so this is plain division with no ticks/seconds mix-up.
     */
    static String timeLeft(long expiryMillis) {
        return duration(expiryMillis - System.currentTimeMillis());
    }

    /** 24.0 becomes "24", 24.5 becomes "24.5". */
    static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }
}
