package dev.keepclaims;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-territory settings.
 *
 * Two kinds of flag:
 *
 * Paid protections start ON in the sense of "vanilla behaviour still happens"
 * (a value of true). Turning one OFF protects the territory and costs the owner
 * an activation fee plus a permanent percentage increase in upkeep. So a paid
 * protection is "active" when its flag is false.
 *
 * Free toggles never cost anything. A value of true means visitors are allowed
 * to do that thing.
 */
public final class TerritorySettings {

    /** Who may still PvP inside a territory once PvP protection is active. */
    public enum PvpScope {
        EVERYONE, VISITORS_ONLY, TRUSTED_ONLY, NOBODY;

        public static PvpScope parse(String raw) {
            if (raw == null) {
                return EVERYONE;
            }
            String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return switch (s) {
                case "VISITORS", "VISITOR_ONLY", "VISITORS_ONLY" -> VISITORS_ONLY;
                case "TRUSTED", "TRUSTED_ONLY", "MEMBERS_ONLY" -> TRUSTED_ONLY;
                case "NOBODY", "NONE", "NO_ONE" -> NOBODY;
                default -> EVERYONE;
            };
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    // --- paid protections (true = vanilla behaviour, false = protected and paid for)
    public boolean explosionDamage = true;
    public boolean pvp = true;
    public boolean mobSpawning = true;
    public boolean fireSpread = true;

    // --- free toggles (true = allowed)
    public boolean visitorDoors = false;
    public boolean visitorContainers = false;
    public boolean visitorAnimals = false;
    // These start blocked (false) so a new territory is safe by default.
    public boolean mobGriefing = false;
    public boolean fluidFlow = false;
    public boolean cropTrample = false;

    // --- extra
    /**
     * Ids of paid protections this territory has already bought (e.g. "explosions").
     * The activation fee is charged once per protection; after that the flag may be
     * turned on and off as often as the owner likes at no extra cost.
     */
    public final Set<String> purchased = new LinkedHashSet<>();
    /** Who may still fight once PvP protection is active. Starts at NOBODY so buying it works at once. */
    public PvpScope pvpScope = PvpScope.NOBODY;
    public String customName = "";
    public String entryMessage = "";
    public String leaveMessage = "";

    public TerritorySettings() {
    }

    /** Builds settings from the config defaults section, falling back to the built-in values. */
    public static TerritorySettings fromConfig(ConfigurationSection defaults) {
        TerritorySettings s = new TerritorySettings();
        if (defaults == null) {
            return s;
        }
        s.explosionDamage = defaults.getBoolean("explosion-damage", s.explosionDamage);
        s.pvp = defaults.getBoolean("pvp", s.pvp);
        s.mobSpawning = defaults.getBoolean("mob-spawning", s.mobSpawning);
        s.fireSpread = defaults.getBoolean("fire-spread", s.fireSpread);
        s.visitorDoors = defaults.getBoolean("visitor-doors", s.visitorDoors);
        s.visitorContainers = defaults.getBoolean("visitor-containers", s.visitorContainers);
        s.visitorAnimals = defaults.getBoolean("visitor-animals", s.visitorAnimals);
        s.mobGriefing = defaults.getBoolean("mob-griefing", s.mobGriefing);
        s.fluidFlow = defaults.getBoolean("fluid-flow", s.fluidFlow);
        s.cropTrample = defaults.getBoolean("crop-trample", s.cropTrample);
        return s;
    }

    /** Server claims (spawn, shops) start fully protected. */
    public void applyServerDefaults() {
        explosionDamage = false;
        pvp = false;
        pvpScope = PvpScope.NOBODY;
        mobSpawning = false;
        fireSpread = false;
    }

    /**
     * Folds the settings of a territory that is being merged into this one. A paid
     * protection stays on if either territory had it, and purchases are kept, so
     * merging never silently throws away something the owner paid for.
     */
    public void absorb(TerritorySettings o) {
        if (!o.explosionDamage) {
            explosionDamage = false;
        }
        if (!o.pvp) {
            if (pvp) {
                pvpScope = o.pvpScope;
            }
            pvp = false;
        }
        if (!o.mobSpawning) {
            mobSpawning = false;
        }
        if (!o.fireSpread) {
            fireSpread = false;
        }
        purchased.addAll(o.purchased);
        if (customName == null || customName.isBlank()) {
            customName = o.customName;
        }
        if (entryMessage == null || entryMessage.isBlank()) {
            entryMessage = o.entryMessage;
        }
        if (leaveMessage == null || leaveMessage.isBlank()) {
            leaveMessage = o.leaveMessage;
        }
    }

    public TerritorySettings copy() {
        TerritorySettings s = new TerritorySettings();
        s.explosionDamage = explosionDamage;
        s.pvp = pvp;
        s.mobSpawning = mobSpawning;
        s.fireSpread = fireSpread;
        s.visitorDoors = visitorDoors;
        s.visitorContainers = visitorContainers;
        s.visitorAnimals = visitorAnimals;
        s.mobGriefing = mobGriefing;
        s.fluidFlow = fluidFlow;
        s.cropTrample = cropTrample;
        s.purchased.addAll(purchased);
        s.pvpScope = pvpScope;
        s.customName = customName;
        s.entryMessage = entryMessage;
        s.leaveMessage = leaveMessage;
        return s;
    }

    /** How many paid protections are currently active (i.e. flag turned off). */
    public int activePaidCount() {
        int n = 0;
        if (!explosionDamage) n++;
        if (!pvp) n++;
        if (!mobSpawning) n++;
        if (!fireSpread) n++;
        return n;
    }

    /** True when this paid protection was already bought, so toggling it is free. */
    public boolean isPurchased(String id) {
        return purchased.contains(id);
    }

    /** Records a paid protection as bought. Returns false if it already was. */
    public boolean markPurchased(String id) {
        return purchased.add(id);
    }

    /**
     * Reads the stored "purchased" list. A missing key means nothing has been bought
     * yet, which is also the case for territories created before the fee existed: they
     * pay the one-time fee on their next activation like any other territory.
     */
    private void readPurchased(Object raw) {
        purchased.clear();
        if (raw == null) {
            return;
        }
        Collection<?> list = raw instanceof Collection<?> c ? c : List.of(raw);
        for (Object o : list) {
            SettingsService.Flag f = SettingsService.Flag.parse(String.valueOf(o));
            if (f != null) {
                purchased.add(f.id);
            }
        }
    }

    // ------------------------------------------------------------- persistence

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("explosion-damage", explosionDamage);
        m.put("pvp", pvp);
        m.put("mob-spawning", mobSpawning);
        m.put("fire-spread", fireSpread);
        m.put("visitor-doors", visitorDoors);
        m.put("visitor-containers", visitorContainers);
        m.put("visitor-animals", visitorAnimals);
        m.put("mob-griefing", mobGriefing);
        m.put("fluid-flow", fluidFlow);
        m.put("crop-trample", cropTrample);
        m.put("purchased", new ArrayList<>(purchased));
        m.put("pvp-scope", pvpScope.name());
        m.put("name", customName);
        m.put("entry-message", entryMessage);
        m.put("leave-message", leaveMessage);
        return m;
    }

    /** Reads settings from a stored map, keeping current values for anything absent. */
    public void readFrom(Map<?, ?> m) {
        if (m == null) {
            return;
        }
        explosionDamage = bool(m, "explosion-damage", explosionDamage);
        pvp = bool(m, "pvp", pvp);
        mobSpawning = bool(m, "mob-spawning", mobSpawning);
        fireSpread = bool(m, "fire-spread", fireSpread);
        visitorDoors = bool(m, "visitor-doors", visitorDoors);
        visitorContainers = bool(m, "visitor-containers", visitorContainers);
        visitorAnimals = bool(m, "visitor-animals", visitorAnimals);
        mobGriefing = bool(m, "mob-griefing", mobGriefing);
        fluidFlow = bool(m, "fluid-flow", fluidFlow);
        cropTrample = bool(m, "crop-trample", cropTrample);
        readPurchased(m.get("purchased"));
        if (m.get("pvp-scope") != null) {
            pvpScope = PvpScope.parse(String.valueOf(m.get("pvp-scope")));
        }
        customName = str(m, "name", customName);
        entryMessage = str(m, "entry-message", entryMessage);
        leaveMessage = str(m, "leave-message", leaveMessage);
    }

    /** Reads settings from a YAML ConfigurationSection (as written by saveNow). */
    public void readSection(ConfigurationSection sec) {
        if (sec == null) {
            return;
        }
        explosionDamage = sec.getBoolean("explosion-damage", explosionDamage);
        pvp = sec.getBoolean("pvp", pvp);
        mobSpawning = sec.getBoolean("mob-spawning", mobSpawning);
        fireSpread = sec.getBoolean("fire-spread", fireSpread);
        visitorDoors = sec.getBoolean("visitor-doors", visitorDoors);
        visitorContainers = sec.getBoolean("visitor-containers", visitorContainers);
        visitorAnimals = sec.getBoolean("visitor-animals", visitorAnimals);
        mobGriefing = sec.getBoolean("mob-griefing", mobGriefing);
        fluidFlow = sec.getBoolean("fluid-flow", fluidFlow);
        cropTrample = sec.getBoolean("crop-trample", cropTrample);
        readPurchased(sec.get("purchased"));
        pvpScope = PvpScope.parse(sec.getString("pvp-scope", pvpScope.name()));
        customName = sec.getString("name", customName);
        entryMessage = sec.getString("entry-message", entryMessage);
        leaveMessage = sec.getString("leave-message", leaveMessage);
    }

    private static boolean bool(Map<?, ?> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean b ? b : def;
    }

    private static String str(Map<?, ?> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    /** The name to show players: the custom name if set, otherwise the owner name. */
    public String displayName(String fallback) {
        return customName == null || customName.isBlank() ? fallback : customName;
    }
}
