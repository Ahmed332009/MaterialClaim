package dev.keepclaims;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalogue of territory flags plus the rules for who may change them and what
 * it costs.
 *
 * Paid protections are the four entries in {@link Flag#paid}. Turning one of those
 * OFF (protecting the territory) costs an activation fee and adds a permanent
 * percentage to the territory's upkeep. Turning it back ON is free and refunds nothing.
 */
public final class SettingsService {

    public enum Kind { PAID, FREE }

    /** Where a text value like a territory name or message is edited. */
    public enum TextInput { NONE, NAME, ENTRY_MESSAGE, LEAVE_MESSAGE }

    public enum Flag {
        EXPLOSIONS("explosions", "explosion-damage", Kind.PAID,
                "Explosion Damage Protection", "TNT, creeper and wither block damage"),
        PVP("pvp", "pvp", Kind.PAID, "PvP Protection", "Player versus player combat"),
        MOB_SPAWNING("mob-spawning", "mob-spawning", Kind.PAID,
                "Mob Spawning Protection", "Hostile mob spawning inside the territory"),
        FIRE_SPREAD("fire-spread", "fire-spread", Kind.PAID,
                "Fire Spread Protection", "Fire spreading to or from territory blocks"),

        VISITOR_DOORS("visitor-doors", "visitor-doors", Kind.FREE,
                "Visitor Doors, Buttons and Levers", "Visitors may use doors, buttons and levers"),
        VISITOR_CONTAINERS("visitor-containers", "visitor-containers", Kind.FREE,
                "Visitor Containers", "Visitors may open chests, barrels and similar"),
        VISITOR_ANIMALS("visitor-animals", "visitor-animals", Kind.FREE,
                "Visitor Animals and Vehicles", "Visitors may harm, leash or ride animals"),
        MOB_GRIEFING("mob-griefing", "mob-griefing", Kind.FREE,
                "Mob Griefing", "Endermen, withers and similar may break blocks"),
        FLUID_FLOW("fluid-flow", "fluid-flow", Kind.FREE,
                "Fluid Flow", "Fluids from neighbouring chunks may flow in"),
        CROP_TRAMPLE("crop-trample", "crop-trample", Kind.FREE,
                "Crop Trampling", "Visitors may trample crops");

        public final String id;
        public final String configKey;
        public final Kind kind;
        public final String title;
        public final String description;

        Flag(String id, String configKey, Kind kind, String title, String description) {
            this.id = id;
            this.configKey = configKey;
            this.kind = kind;
            this.title = title;
            this.description = description;
        }

        public boolean isPaid() {
            return kind == Kind.PAID;
        }

        public static Flag parse(String raw) {
            if (raw == null) {
                return null;
            }
            String s = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Flag f : values()) {
                if (f.id.equals(s)) {
                    return f;
                }
            }
            return null;
        }
    }

    private final KeepClaims plugin;
    private double upkeepMultiplier = 0.25;
    private double activationFee = 100.0;
    private TerritorySettings defaults = new TerritorySettings();
    private final List<String> locked = new ArrayList<>();

    public SettingsService(KeepClaims plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("territory-settings");
        if (sec == null) {
            upkeepMultiplier = 0.25;
            activationFee = 100.0;
            defaults = new TerritorySettings();
            locked.clear();
            return;
        }
        upkeepMultiplier = sec.getDouble("paid-protection-upkeep-multiplier", 0.25);
        activationFee = sec.getDouble("paid-protection-activation-fee", 100.0);
        defaults = TerritorySettings.fromConfig(sec.getConfigurationSection("defaults"));
        locked.clear();
        // "locked" may be written as a map of flag: true (preferred) or a plain list.
        ConfigurationSection lockSec = sec.getConfigurationSection("locked");
        if (lockSec != null) {
            for (String key : lockSec.getKeys(false)) {
                Flag f = Flag.parse(key);
                if (f != null && lockSec.getBoolean(key, false)) {
                    locked.add(f.id);
                }
            }
        } else {
            for (String entry : sec.getStringList("locked")) {
                Flag f = Flag.parse(entry);
                if (f != null) {
                    locked.add(f.id);
                }
            }
        }
    }

    public double upkeepMultiplier() {
        return upkeepMultiplier;
    }

    public double activationFee() {
        return activationFee;
    }

    /** Fresh settings from the config defaults, for a brand new territory. */
    public TerritorySettings defaults() {
        return defaults.copy();
    }

    /**
     * The upkeep multiplier for a territory including its paid protections.
     * One active protection adds paid-protection-upkeep-multiplier, two add twice
     * that, and so on.
     */
    public double upkeepFactor(Claim c) {
        if (c == null) {
            return 1.0;
        }
        return 1.0 + upkeepMultiplier * c.settings.activePaidCount();
    }

    public double activationFeeFor(Claim c) {
        return activationFee;
    }

    // ----------------------------------------------------------------- reading

    public boolean isLocked(Flag f) {
        return locked.contains(f.id);
    }

    public boolean get(Claim c, Flag f) {
        TerritorySettings s = c.settings;
        return switch (f) {
            case EXPLOSIONS -> s.explosionDamage;
            case PVP -> s.pvp;
            case MOB_SPAWNING -> s.mobSpawning;
            case FIRE_SPREAD -> s.fireSpread;
            case VISITOR_DOORS -> s.visitorDoors;
            case VISITOR_CONTAINERS -> s.visitorContainers;
            case VISITOR_ANIMALS -> s.visitorAnimals;
            case MOB_GRIEFING -> s.mobGriefing;
            case FLUID_FLOW -> s.fluidFlow;
            case CROP_TRAMPLE -> s.cropTrample;
        };
    }

    public void set(Claim c, Flag f, boolean value) {
        TerritorySettings s = c.settings;
        switch (f) {
            case EXPLOSIONS -> s.explosionDamage = value;
            case PVP -> s.pvp = value;
            case MOB_SPAWNING -> s.mobSpawning = value;
            case FIRE_SPREAD -> s.fireSpread = value;
            case VISITOR_DOORS -> s.visitorDoors = value;
            case VISITOR_CONTAINERS -> s.visitorContainers = value;
            case VISITOR_ANIMALS -> s.visitorAnimals = value;
            case MOB_GRIEFING -> s.mobGriefing = value;
            case FLUID_FLOW -> s.fluidFlow = value;
            case CROP_TRAMPLE -> s.cropTrample = value;
        }
    }

    /** True when the paid protection is active (the flag is off). */
    public boolean isProtected(Claim c, Flag f) {
        return f.isPaid() && !get(c, f);
    }

    // ------------------------------------------------------------- permissions

    public enum Role { VISITOR, BUILDER, MANAGER, OWNER, ADMIN }

    public Role roleOf(Player p, Claim c) {
        if (p.hasPermission("materialclaim.admin")) {
            return Role.ADMIN;
        }
        if (c == null || c.isServer()) {
            return Role.VISITOR;
        }
        if (p.getUniqueId().equals(c.owner)) {
            return Role.OWNER;
        }
        TrustList.Level l = c.trusted.levelOf(p.getUniqueId());
        return switch (l) {
            case MANAGER -> Role.MANAGER;
            case BUILDER -> Role.BUILDER;
            default -> Role.VISITOR;
        };
    }

    /** May this player change this flag at all, ignoring money? */
    public boolean mayChange(Player p, Claim c, Flag f) {
        Role role = roleOf(p, c);
        if (role == Role.ADMIN || role == Role.OWNER) {
            return true;
        }
        // Managers may only touch free toggles.
        return role == Role.MANAGER && !f.isPaid();
    }

    /** The reason a change is refused, or null when it is allowed. */
    public String refusal(Player p, Claim c, Flag f) {
        if (c == null) {
            return plugin.message("no-claim-here");
        }
        if (isLocked(f) && !p.hasPermission("materialclaim.admin")) {
            return plugin.message("flag-locked");
        }
        if (!mayChange(p, c, f)) {
            if (f.isPaid() && roleOf(p, c) == Role.MANAGER) {
                return plugin.message("flag-manager-paid");
            }
            return plugin.message("not-your-claim");
        }
        return null;
    }

    // ------------------------------------------------------------ persistence

    public Map<String, Object> toMap(TerritorySettings s) {
        return s.toMap();
    }

    public void readInto(TerritorySettings s, org.bukkit.configuration.ConfigurationSection sec) {
        s.readSection(sec);
    }
}
