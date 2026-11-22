package dev.keepclaims;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knows which items pay for upkeep and what each is worth (in hours of upkeep for one
 * chunk at multiplier 1.0), and takes items out of a player's inventory.
 */
public final class PaymentService {
    private static final long HOUR_MS = 3_600_000L;

    public record Result(boolean ok, double credited, String error) {
        static Result fail(String error) {
            return new Result(false, 0, error);
        }

        static Result success(double credited) {
            return new Result(true, credited, null);
        }
    }

    private final KeepClaims plugin;
    /** Sorted by value, cheapest first. */
    private final LinkedHashMap<Material, Double> values = new LinkedHashMap<>();
    /** The material each player chose to pay with. Missing = automatic (cheapest first). */
    private final Map<UUID, Material> choice = new HashMap<>();

    public PaymentService(KeepClaims plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        values.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("materials");
        if (section == null) {
            plugin.getLogger().warning("No 'materials' section in config.yml, nothing can pay for claims.");
            return;
        }
        List<Map.Entry<Material, Double>> list = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            double v = section.getDouble(key);
            if (m == null) {
                plugin.getLogger().warning("Unknown material in config: " + key);
                continue;
            }
            if (v <= 0) {
                continue;
            }
            list.add(Map.entry(m, v));
        }
        list.sort(Map.Entry.comparingByValue());
        for (Map.Entry<Material, Double> e : list) {
            values.put(e.getKey(), e.getValue());
        }
        choice.keySet().removeIf(id -> !values.containsKey(choice.get(id)));
    }

    public Map<Material, Double> values() {
        return values;
    }

    public double valueOf(Material m) {
        return values.getOrDefault(m, 0.0);
    }

    public Material choiceOf(Player p) {
        Material m = choice.get(p.getUniqueId());
        return m != null && values.containsKey(m) ? m : null;
    }

    public void setChoice(Player p, Material m) {
        if (m == null) {
            choice.remove(p.getUniqueId());
        } else {
            choice.put(p.getUniqueId(), m);
        }
    }

    public int count(Player p, Material m) {
        int total = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.getType() == m && !s.hasItemMeta()) {
                total += s.getAmount();
            }
        }
        return total;
    }

    private void remove(Player p, Material m, int amount) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != m || s.hasItemMeta()) {
                continue;
            }
            int take = Math.min(amount, s.getAmount());
            if (take == s.getAmount()) {
                contents[i] = null;
            } else {
                s.setAmount(s.getAmount() - take);
            }
            amount -= take;
        }
        p.getInventory().setStorageContents(contents);
    }

    /** Lines like "2x diamond" for the cost of rawHours, one per non-block material. */
    public List<String> costLines(double rawHours) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Material, Double> e : values.entrySet()) {
            if (e.getKey().name().endsWith("_BLOCK")) {
                continue;
            }
            int n = (int) Math.ceil(rawHours / e.getValue() - 1e-9);
            out.add("<gray>- <white>" + Math.max(1, n) + "x " + Util.pretty(e.getKey()));
        }
        return out;
    }

    /**
     * Takes items worth enough to give creditHours of upkeep at the given multiplier.
     * With only == null the cheapest accepted items are used first. Surplus value from
     * rounding up to whole items is returned as extra credit, never lost.
     */
    public Result charge(Player p, double creditHours, double multiplier, Material only) {
        return charge(p, creditHours, multiplier, only, Double.MAX_VALUE);
    }

    /**
     * Same as {@link #charge(Player, double, double, Material)}, but never credits more
     * than maxCreditHours of upkeep.
     *
     * Items come in whole units, so a payment can overshoot the requested hours. That
     * overshoot is normally granted as extra credit. When it would exceed the claim's
     * maximum prepaid time the surplus used to be discarded *after* the items had already
     * been taken, silently destroying player value. Instead the payment is now shrunk to
     * the largest whole-item amount that fits, and refused outright only when not even one
     * item fits. Nothing is ever removed from the inventory in the refusal case.
     */
    public Result charge(Player p, double creditHours, double multiplier, Material only,
                         double maxCreditHours) {
        if (maxCreditHours <= 0) {
            return tooMuchForCap(p, maxCreditHours);
        }
        if (creditHours <= 0 || multiplier <= 0) {
            return Result.fail(plugin.message("nothing-paid"));
        }
        double maxRaw = maxCreditHours * multiplier;
        double needRaw = Math.min(creditHours * multiplier, maxRaw);
        if (only != null && !values.containsKey(only)) {
            only = null;
        }
        double paidRaw;
        if (only != null) {
            double v = values.get(only);
            int n = (int) Math.ceil(needRaw / v - 1e-9);
            // Shrink to the largest number of whole items that still fits under the cap.
            // n * v > maxRaw only happens when whole-item rounding overshoots.
            if (n * v > maxRaw + 1e-6) {
                n = (int) Math.floor(maxRaw / v + 1e-9);
                if (n <= 0) {
                    return tooMuchForCap(p, maxCreditHours);
                }
            }
            int have = count(p, only);
            if (have < n) {
                return Result.fail("<red>You need " + n + "x " + Util.pretty(only)
                        + " but only have " + have + ".");
            }
            remove(p, only, n);
            paidRaw = n * v;
        } else {
            // Build the cheapest-first plan without worrying about the cap yet, so that a
            // genuine item shortage is still reported as such.
            Map<Material, Integer> plan = new LinkedHashMap<>();
            double remaining = needRaw;
            paidRaw = 0;
            for (Map.Entry<Material, Double> e : values.entrySet()) {
                if (remaining <= 1e-9) {
                    break;
                }
                int have = count(p, e.getKey());
                if (have == 0) {
                    continue;
                }
                int use = (int) Math.min(have, Math.ceil(remaining / e.getValue() - 1e-9));
                plan.put(e.getKey(), use);
                remaining -= use * e.getValue();
                paidRaw += use * e.getValue();
            }
            if (remaining > 1e-9) {
                return Result.fail("<red>You do not have enough accepted items. Open the menu to see what is accepted.");
            }
            // Whole-item rounding can push the total past the cap. Trim surplus items from
            // the most recently added (most expensive) end until it fits. The remaining
            // shortfall is pure item granularity, so it is credited, not refunded.
            double excess = paidRaw - maxRaw;
            if (excess > 1e-6) {
                List<Material> order = new ArrayList<>(plan.keySet());
                for (int i = order.size() - 1; i >= 0 && excess > 1e-6; i--) {
                    Material m = order.get(i);
                    double v = values.get(m);
                    int have = plan.get(m);
                    if (have <= 0) {
                        continue;
                    }
                    int drop = (int) Math.min(have, Math.ceil(excess / v - 1e-9));
                    plan.put(m, have - drop);
                    paidRaw -= drop * v;
                    excess -= drop * v;
                }
                plan.values().removeIf(n -> n <= 0);
                if (paidRaw <= 1e-6) {
                    return tooMuchForCap(p, maxCreditHours);
                }
            }
            for (Map.Entry<Material, Integer> e : plan.entrySet()) {
                remove(p, e.getKey(), e.getValue());
            }
        }
        return Result.success(paidRaw / multiplier);
    }

    /**
     * Refusal used when a payment would be worth more upkeep than the claim can hold.
     * Nothing has been removed from the inventory when this is returned.
     */
    private Result tooMuchForCap(Player p, double maxCreditHours) {
        if (maxCreditHours <= 0) {
            // The claim has no room at all; the caller normally reports this itself.
            return Result.fail(plugin.message("max-prepaid"));
        }
        long room = Math.max(0L, (long) (maxCreditHours * HOUR_MS));
        return Result.fail(plugin.message("over-cap", "room", Util.duration(room)));
    }
}
