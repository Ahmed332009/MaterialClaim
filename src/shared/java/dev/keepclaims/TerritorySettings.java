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
 * Two kinds of flag.
 *
 * Paid protections have two independent booleans each:
 *
 * <ul>
 *   <li>{@code purchased} - the one-time 24 hour fee has been paid at least once. It is
 *       never reset, so switching the protection off keeps the right to switch it back on
 *       for free.</li>
 *   <li>{@code enabled} - the protection is switched ON right now and is enforced.</li>
 * </ul>
 *
 * A paid protection only blocks anything when it is <b>both</b> purchased and enabled.
 * When it is not enabled vanilla behaviour applies. Neither flag is ever refunded.
 *
 * Free toggles never cost anything and have no purchase step. A value of true means
 * visitors are allowed to do that thing.
 *
 * On disk the enabled state is stored inverted under the historical keys
 * (explosion-damage, pvp, ...) where true means "vanilla behaviour", so the stored
 * format and the config defaults keep their old meaning. The two booleans are also
 * written explicitly under "enabled" so the intent is readable in claims.yml.
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

    // --- paid protections: enabled = the protection is switched on and enforced
    private boolean explosionsEnabled = false;
    private boolean pvpEnabled = false;
    private boolean mobSpawningEnabled = false;
    private boolean fireSpreadEnabled = false;

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
     * The fee is charged once per protection; after that the flag may be turned on and
     * off as often as the owner likes at no extra cost, and switching it off refunds
     * nothing.
     */
    public final Set<String> purchased = new LinkedHashSet<>();
    /** Who may still fight once PvP protection is active. */
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
        // The config keys keep their historical "true = vanilla" meaning.
        s.setEnabled("explosions", !defaults.getBoolean("explosion-damage", true));
        s.setEnabled("pvp", !defaults.getBoolean("pvp", true));
        s.setEnabled("mob-spawning", !defaults.getBoolean("mob-spawning", true));
        s.setEnabled("fire-spread", !defaults.getBoolean("fire-spread", true));
        s.visitorDoors = defaults.getBoolean("visitor-doors", s.visitorDoors);
        s.visitorContainers = defaults.getBoolean("visitor-containers", s.visitorContainers);
        s.visitorAnimals = defaults.getBoolean("visitor-animals", s.visitorAnimals);
        s.mobGriefing = defaults.getBoolean("mob-griefing", s.mobGriefing);
        s.fluidFlow = defaults.getBoolean("fluid-flow", s.fluidFlow);
        s.cropTrample = defaults.getBoolean("crop-trample", s.cropTrample);
        return s;
    }

    /** Server claims (spawn, shops) start fully protected and are bought by the server. */
    public void applyServerDefaults() {
        explosionsEnabled = true;
        pvpEnabled = true;
        mobSpawningEnabled = true;
        fireSpreadEnabled = true;
        pvpScope = PvpScope.NOBODY;
        purchased.addAll(List.of("explosions", "pvp", "mob-spawning", "fire-spread"));
    }

    /**
     * Folds the settings of a territory that is being merged into this one. A paid
     * protection stays on if either territory had it enabled, and every purchase is
     * kept, so merging never silently throws away something the owner paid for.
     */
    public void absorb(TerritorySettings o) {
        explosionsEnabled = explosionsEnabled || o.explosionsEnabled;
        mobSpawningEnabled = mobSpawningEnabled || o.mobSpawningEnabled;
        fireSpreadEnabled = fireSpreadEnabled || o.fireSpreadEnabled;
        if (o.pvpEnabled) {
            if (!pvpEnabled) {
                pvpScope = o.pvpScope;
            }
            pvpEnabled = true;
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
        s.explosionsEnabled = explosionsEnabled;
        s.pvpEnabled = pvpEnabled;
        s.mobSpawningEnabled = mobSpawningEnabled;
        s.fireSpreadEnabled = fireSpreadEnabled;
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

    /** How many paid protections are switched on and being enforced right now. */
    public int activePaidCount() {
        int n = 0;
        if (explosionsEnabled) n++;
        if (pvpEnabled) n++;
        if (mobSpawningEnabled) n++;
        if (fireSpreadEnabled) n++;
        return n;
    }

    /**
     * How many paid protections this territory owns. This is what drives the upkeep cost:
     * buying a protection permanently raises the cost, and switching one off later never
     * lowers it again. Counts the purchased set so it can only ever grow.
     */
    public int purchasedPaidCount() {
        return purchased.size();
    }

    // ------------------------------------------------- paid protection state

    /** True when this protection is switched on and is actually enforced. */
    public boolean isEnabled(String id) {
        return switch (id) {
            case "explosions" -> explosionsEnabled;
            case "pvp" -> pvpEnabled;
            case "mob-spawning" -> mobSpawningEnabled;
            case "fire-spread" -> fireSpreadEnabled;
            default -> false;
        };
    }

    public void setEnabled(String id, boolean enabled) {
        switch (id) {
            case "explosions" -> explosionsEnabled = enabled;
            case "pvp" -> pvpEnabled = enabled;
            case "mob-spawning" -> mobSpawningEnabled = enabled;
            case "fire-spread" -> fireSpreadEnabled = enabled;
            default -> {
            }
        }
        // Something being enforced implies it was bought. Payment is handled separately
        // by the manager, which charges before calling this.
        if (enabled) {
            purchased.add(id);
        }
    }

    /** True when this paid protection was already bought, so toggling it is free. */
    public boolean isPurchased(String id) {
        return purchased.contains(id);
    }

    /** Records a paid protection as bought. Returns false if it already was. */
    public boolean markPurchased(String id) {
        return purchased.add(id);
    }

    /** True when the protection should block anything: bought and switched on. */
    public boolean isProtecting(String id) {
        return isPurchased(id) && isEnabled(id);
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

    /**
     * Reads the enabled state for one paid protection from a stored map or section.
     *
     * The explicit "enabled" key wins. Otherwise the historical inverted key is used
     * (true = vanilla, so enabled = !stored). A map written before purchases existed has
     * no marker at all; if its flag showed the protection as on, it was already being
     * paid for under the old per-toggle fee, so it counts as bought.
     */
    private void readEnabled(Object enabledRaw, Object invertedRaw, String id, boolean def) {
        boolean enabled;
        if (enabledRaw instanceof Boolean b) {
            enabled = b;
        } else if (invertedRaw instanceof Boolean b) {
            enabled = !b;
        } else {
            enabled = def;
        }
        setEnabled(id, enabled);
        if (enabled && !(enabledRaw instanceof Boolean)) {
            markPurchased(id);
        }
    }

    // ------------------------------------------------------------- persistence

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        // Historical inverted keys: true = vanilla behaviour.
        m.put("explosion-damage", !explosionsEnabled);
        m.put("pvp", !pvpEnabled);
        m.put("mob-spawning", !mobSpawningEnabled);
        m.put("fire-spread", !fireSpreadEnabled);
        m.put("visitor-doors", visitorDoors);
        m.put("visitor-containers", visitorContainers);
        m.put("visitor-animals", visitorAnimals);
        m.put("mob-griefing", mobGriefing);
        m.put("fluid-flow", fluidFlow);
        m.put("crop-trample", cropTrample);
        m.put("purchased", new ArrayList<>(purchased));
        // Explicit, readable copy of the same state.
        Map<String, Object> enabled = new LinkedHashMap<>();
        enabled.put("explosions", explosionsEnabled);
        enabled.put("pvp", pvpEnabled);
        enabled.put("mob-spawning", mobSpawningEnabled);
        enabled.put("fire-spread", fireSpreadEnabled);
        m.put("enabled", enabled);
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
        // Purchases first: readEnabled() may mark a legacy territory as bought, and
        // readPurchased() clears the set, so the reverse order would discard that.
        readPurchased(m.get("purchased"));
        Object enabledRaw = m.get("enabled");
        Map<?, ?> enabledMap = enabledRaw instanceof Map<?, ?> em ? em : null;
        readEnabled(enabledMap == null ? null : enabledMap.get("explosions"),
                m.get("explosion-damage"), "explosions", explosionsEnabled);
        readEnabled(enabledMap == null ? null : enabledMap.get("pvp"),
                m.get("pvp"), "pvp", pvpEnabled);
        readEnabled(enabledMap == null ? null : enabledMap.get("mob-spawning"),
                m.get("mob-spawning"), "mob-spawning", mobSpawningEnabled);
        readEnabled(enabledMap == null ? null : enabledMap.get("fire-spread"),
                m.get("fire-spread"), "fire-spread", fireSpreadEnabled);
        visitorDoors = bool(m, "visitor-doors", visitorDoors);
        visitorContainers = bool(m, "visitor-containers", visitorContainers);
        visitorAnimals = bool(m, "visitor-animals", visitorAnimals);
        mobGriefing = bool(m, "mob-griefing", mobGriefing);
        fluidFlow = bool(m, "fluid-flow", fluidFlow);
        cropTrample = bool(m, "crop-trample", cropTrample);
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
        // Purchases first, for the same reason as readFrom().
        readPurchased(sec.get("purchased"));
        ConfigurationSection enabledSec = sec.getConfigurationSection("enabled");
        readEnabled(enabledSec == null ? null : enabledSec.get("explosions"),
                sec.get("explosion-damage"), "explosions", explosionsEnabled);
        readEnabled(enabledSec == null ? null : enabledSec.get("pvp"),
                sec.get("pvp"), "pvp", pvpEnabled);
        readEnabled(enabledSec == null ? null : enabledSec.get("mob-spawning"),
                sec.get("mob-spawning"), "mob-spawning", mobSpawningEnabled);
        readEnabled(enabledSec == null ? null : enabledSec.get("fire-spread"),
                sec.get("fire-spread"), "fire-spread", fireSpreadEnabled);
        visitorDoors = sec.getBoolean("visitor-doors", visitorDoors);
        visitorContainers = sec.getBoolean("visitor-containers", visitorContainers);
        visitorAnimals = sec.getBoolean("visitor-animals", visitorAnimals);
        mobGriefing = sec.getBoolean("mob-griefing", mobGriefing);
        fluidFlow = sec.getBoolean("fluid-flow", fluidFlow);
        cropTrample = sec.getBoolean("crop-trample", cropTrample);
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
