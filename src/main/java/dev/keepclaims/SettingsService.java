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
            defaults = new TerritorySettings();
            locked.clear();
            return;
        }
        upkeepMultiplier = sec.getDouble("paid-protection-upkeep-multiplier", 0.25);
        // The old paid-protection-activation-fee (an item fee) no longer applies: buying a
        // protection now costs a flat 24 hours off the territory clock. The key is ignored
        // if it is still present in an existing config.yml.
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

    /** Fresh settings from the config defaults, for a brand new territory. */
    public TerritorySettings defaults() {
        return defaults.copy();
    }

    /**
     * The upkeep multiplier for a territory including its paid protections.
     *
     * This is driven by how many protections the territory has <b>bought</b>, not by how
     * many are switched on: buying permanently locks in the higher cost, and switching one
     * off later never lowers it. Each bought protection adds paid-protection-upkeep-multiplier,
     * so owning all four is 1 + 4 * 0.25 = 2.0 and a diamond (12h) is worth 6h.
     */
    public double upkeepFactor(Claim c) {
        if (c == null) {
            return 1.0;
        }
        return 1.0 + upkeepMultiplier * c.settings.purchasedPaidCount();
    }

    // ----------------------------------------------------------------- reading

    public boolean isLocked(Flag f) {
        return locked.contains(f.id);
    }

    /**
     * The state of a flag, always in the same sense for both kinds:
     * paid protections report whether they are <b>enabled</b> (protecting), free toggles
     * report whether the visitor action is allowed. Chat and GUI both read this, so they
     * can never disagree.
     */
    public boolean get(Claim c, Flag f) {
        TerritorySettings s = c.settings;
        return f.isPaid() ? s.isEnabled(f.id) : switch (f) {
            case VISITOR_DOORS -> s.visitorDoors;
            case VISITOR_CONTAINERS -> s.visitorContainers;
            case VISITOR_ANIMALS -> s.visitorAnimals;
            case MOB_GRIEFING -> s.mobGriefing;
            case FLUID_FLOW -> s.fluidFlow;
            case CROP_TRAMPLE -> s.cropTrample;
            default -> false;
        };
    }

    /**
     * Writes a flag state in the same sense as {@link #get}. Enabling a paid protection
     * also marks it bought, because the caller has already taken payment; nothing here
     * refunds or un-buys.
     */
    public void set(Claim c, Flag f, boolean value) {
        TerritorySettings s = c.settings;
        if (f.isPaid()) {
            s.setEnabled(f.id, value);
            return;
        }
        switch (f) {
            case VISITOR_DOORS -> s.visitorDoors = value;
            case VISITOR_CONTAINERS -> s.visitorContainers = value;
            case VISITOR_ANIMALS -> s.visitorAnimals = value;
            case MOB_GRIEFING -> s.mobGriefing = value;
            case FLUID_FLOW -> s.fluidFlow = value;
            case CROP_TRAMPLE -> s.cropTrample = value;
            default -> {
            }
        }
    }

    /** True when the paid protection is bought and switched on, so it must be enforced. */
    public boolean isProtected(Claim c, Flag f) {
        return f.isPaid() && c.settings.isProtecting(f.id);
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

    /**
     * May this player change this flag at all, ignoring money?
     *
     * Roles decide: the owner and managers may change free toggles and buy or remove paid
     * protections (they pay the fee from their own inventory), admins may do anything
     * anywhere for free. The permission nodes also gate it,
     * so a server can hand out flag rights without handing out whole territories:
     * materialclaim.flags for free toggles, materialclaim.flags.paid for paid
     * protections, materialclaim.flags.any for any territory.
     */
    public boolean mayChange(Player p, Claim c, Flag f) {
        Role role = roleOf(p, c);
        if (role == Role.ADMIN) {
            return p.hasPermission("materialclaim.flags.any")
                    || p.hasPermission("materialclaim.admin");
        }
        boolean anyTerritory = p.hasPermission("materialclaim.flags.any");
        boolean allowedHere = anyTerritory
                || role == Role.OWNER
                || role == Role.MANAGER;
        if (!allowedHere) {
            return false;
        }
        // Free toggles need the basic node; paid protections need the paid node.
        return f.isPaid()
                ? p.hasPermission("materialclaim.flags.paid")
                : p.hasPermission("materialclaim.flags");
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
            Role role = roleOf(p, c);
            boolean lacksFlagNode = f.isPaid()
                    ? !p.hasPermission("materialclaim.flags.paid")
                    : !p.hasPermission("materialclaim.flags");
            if (lacksFlagNode && role != Role.VISITOR && role != Role.BUILDER) {
                return plugin.message("no-permission");
            }
            return plugin.message("not-your-claim");
        }
        return null;
    }

    /** Owner, manager or admin: may pay upkeep and change settings. */
    public boolean canManage(Player p, Claim c) {
        Role r = roleOf(p, c);
        return r == Role.ADMIN || r == Role.OWNER || r == Role.MANAGER;
    }

    /** Owner or admin: may manage trusted players and unclaim. */
    public boolean isOwnerOrAdmin(Player p, Claim c) {
        Role r = roleOf(p, c);
        return r == Role.ADMIN || r == Role.OWNER;
    }

    // ------------------------------------------------------------ persistence

    public Map<String, Object> toMap(TerritorySettings s) {
        return s.toMap();
    }

    public void readInto(TerritorySettings s, org.bukkit.configuration.ConfigurationSection sec) {
        s.readSection(sec);
    }
}
