package dev.keepclaims;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Staff commands for managing land they do not own. The set mirrors what the established
 * claim plugins offer, so a server owner moving from GriefPrevention or a chunk claim plugin
 * finds the same ideas under one prefix:
 *
 * <pre>
 * GriefPrevention            here
 * /deleteclaim               /claims admin delete
 * /deleteallclaims &lt;player&gt;   /claims admin deleteplayer &lt;player&gt;
 * /deletealladminclaims      /claims admin deleteserver
 * /transferclaim             /claims admin transfer &lt;player&gt;
 * /adjustbonusclaimblocks    /claims admin setlimit &lt;player&gt; &lt;blocks&gt;
 * /claimslist &lt;player&gt;       /claims admin list [player]
 * /ignoreclaims              /claims admin bypass
 * /adminclaims               /claims admin serverclaim [radius]
 * </pre>
 *
 * Everything here needs {@code materialclaim.admin}. Land is never charged to staff and no
 * upkeep is ever taken from a player by these commands.
 */
public final class AdminCommands {

    /** Safety rail for {@code deleteserver}: refuse to wipe more than this in one command. */
    private static final int MAX_SERVER_WIPE = 500;

    private final KeepClaims plugin;

    public AdminCommands(KeepClaims plugin) {
        this.plugin = plugin;
    }

    private ClaimManager cm() {
        return plugin.claims();
    }

    // ------------------------------------------------------------------ entry

    /** Runs an admin subcommand. Returns true when the subcommand was recognised. */
    public boolean run(Player p, String[] args) {
        if (!p.hasPermission("materialclaim.admin")) {
            plugin.send(p, "no-permission");
            return true;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "help" -> help(p);
            case "bypass" -> bypass(p);
            case "serverclaim" -> serverClaim(p, args);
            case "unclaim" -> unclaimHere(p);
            case "reload" -> {
                plugin.reloadAll();
                plugin.send(p, "reloaded");
            }
            case "delete" -> deleteHere(p);
            case "deleteplayer" -> deletePlayer(p, args);
            case "deleteserver" -> deleteServer(p);
            case "transfer" -> transfer(p, args);
            case "setlimit" -> setLimit(p, args);
            case "setupkeep" -> setUpkeep(p, args);
            case "list" -> list(p, args);
            case "who" -> who(p);
            case "info" -> info(p, args);
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        plugin.sendMini(p, "<dark_green><bold>MaterialClaim admin commands");
        plugin.sendMini(p, "<gray>Land: "
                + "<white>/claims admin delete<gray> - remove the territory you stand in");
        plugin.sendMini(p, "<white>/claims admin deleteplayer <player><gray> - remove all of a player's land");
        plugin.sendMini(p, "<white>/claims admin transfer <player><gray> - give the territory you stand in away");
        plugin.sendMini(p, "<white>/claims admin serverclaim<gray> - get the netherite shovel,"
                + " then select the land to protect");
        plugin.sendMini(p, "<white>/claims admin deleteserver<gray> - remove every server claim");
        plugin.sendMini(p, "<white>/claims admin unclaim<gray> - remove the whole territory you stand in");
        plugin.sendMini(p, "<gray>Look up: "
                + "<white>/claims admin list [player]<gray>, <white>/claims admin who<gray>, "
                + "<white>/claims admin info<gray>");
        plugin.sendMini(p, "<gray>Limits and time: "
                + "<white>/claims admin setlimit <player> <blocks><gray> (0 = clear)");
        plugin.sendMini(p, "<white>/claims admin setupkeep <player> <hours|status><gray> - set the clock");
        plugin.sendMini(p, "<gray>Tools: <white>/claims admin bypass<gray> (ignore protection), "
                + "<white>/claims admin reload");
    }

    private void bypass(Player p) {
        if (plugin.bypassing().remove(p.getUniqueId())) {
            plugin.send(p, "bypass-off");
        } else {
            plugin.bypassing().add(p.getUniqueId());
            plugin.send(p, "bypass-on");
        }
    }

    // ------------------------------------------------------------- inspection

    private void list(Player p, String[] args) {
        if (args.length > 2) {
            OfflinePlayer target = resolve(p, args[2]);
            if (target == null) {
                return;
            }
            List<Claim> owned = ownedBy(target.getUniqueId());
            if (owned.isEmpty()) {
                plugin.sendMini(p, "<gray>" + esc(name(target)) + " owns no land.");
                return;
            }
            long blocks = 0;
            for (Claim c : owned) {
                blocks += c.blocks();
            }
            plugin.sendMini(p, "<gray>" + esc(name(target)) + " owns <white>" + owned.size()
                    + "</white> territor" + (owned.size() == 1 ? "y" : "ies")
                    + " totalling <white>" + blocks + "</white> blocks:");
            for (Claim c : owned) {
                plugin.sendMini(p, "  <white>" + c.shortId() + " <gray>| " + cm().sizeLine(c)
                        + " | " + cm().statusLine(c));
            }
            return;
        }
        // No name given: list the territory the admin is standing in, plus the land around.
        Claim here = cm().at(p.getLocation());
        if (here != null) {
            describe(p, here);
            return;
        }
        plugin.send(p, "no-claim-here");
        plugin.sendMini(p, "<gray>Usage: <white>/claims admin list <player>");
    }

    private void who(Player p) {
        // Every territory in the world the admin is standing in, whoever owns it.
        World w = p.getWorld();
        UUID wid = w.getUID();
        List<Claim> found = new ArrayList<>();
        for (Claim c : cm().territories()) {
            if (wid.equals(c.world())) {
                found.add(c);
            }
        }
        if (found.isEmpty()) {
            plugin.sendMini(p, "<gray>No claims in <white>" + esc(w.getName()) + "<gray>.");
            return;
        }
        plugin.sendMini(p, "<gray>Claims in <white>" + esc(w.getName()) + "<gray>: <white>"
                + found.size() + "<gray> territor" + (found.size() == 1 ? "y" : "ies") + ".");
        int shown = 0;
        for (Claim c : found) {
            if (shown++ >= 20) {
                plugin.sendMini(p, "<gray>... and <white>" + (found.size() - 20) + "<gray> more.");
                break;
            }
            plugin.sendMini(p, "  <white>" + c.shortId() + " <gray>| "
                    + esc(c.isServer() ? "Server" : c.ownerName) + " | " + cm().sizeLine(c)
                    + " | " + cm().statusLine(c));
        }
    }

    private void info(Player p, String[] args) {
        Claim c;
        if (args.length > 2) {
            OfflinePlayer target = resolve(p, args[2]);
            if (target == null) {
                return;
            }
            List<Claim> owned = ownedBy(target.getUniqueId());
            if (owned.isEmpty()) {
                plugin.sendMini(p, "<gray>" + esc(name(target)) + " owns no land.");
                return;
            }
            c = owned.get(0);
        } else {
            c = cm().at(p.getLocation());
            if (c == null) {
                plugin.send(p, "no-claim-here");
                return;
            }
        }
        describe(p, c);
    }

    /** Detailed readout of one territory, including things only staff can see. */
    private void describe(Player p, Claim c) {
        plugin.sendMini(p, "<dark_green><bold>Territory " + esc(c.shortId()));
        plugin.sendMini(p, "<gray>Owner: <white>"
                + (c.isServer() ? "Server" : esc(c.ownerName))
                + (c.isServer() ? "" : " <dark_gray>(" + c.owner + ")"));
        plugin.sendMini(p, "<gray>Size: <white>" + cm().sizeLine(c)
                + " <gray>| <white>" + c.areas.size() + "<gray> rectangle(s)");
        try {
            plugin.sendMini(p, "<gray>World: <white>" + esc(Bukkit.getWorld(c.areas.get(0).world()).getName()));
        } catch (RuntimeException ignored) {
            plugin.sendMini(p, "<gray>World: <white>unknown (not loaded)");
        }
        plugin.sendMini(p, "<gray>Status: " + cm().statusLine(c));
        if (!c.isServer()) {
            plugin.sendMini(p, "<gray>Upkeep rate: <white>" + Util.trim(cm().rate(c))
                    + " hours/h <gray>| left: <white>" + Util.duration(cm().remainingMs(c)));
        }
        plugin.sendMini(p, "<gray>Trusted: <white>" + c.trusted.size()
                + "<gray> | protections on: <white>" + c.settings.activePaidCount()
                + "<gray>/4, bought: <white>" + c.settings.purchasedPaidCount());
    }

    // ------------------------------------------------------------------ land

    /** Removes the territory the admin stands in, whoever owns it. */
    private void deleteHere(Player p) {
        Claim c = cm().at(p.getLocation());
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        String owner = c.isServer() ? "the server" : c.ownerName;
        long blocks = c.blocks();
        cm().deleteClaim(c);
        plugin.sendMini(p, "<green>Removed <white>" + blocks + "</white> blocks of land owned by <white>"
                + esc(owner) + "</white>. Nothing was refunded.");
    }

    private void deletePlayer(Player p, String[] args) {
        if (args.length < 3) {
            plugin.sendMini(p, "<gray>Usage: <white>/claims admin deleteplayer <player>");
            return;
        }
        OfflinePlayer target = resolve(p, args[2]);
        if (target == null) {
            return;
        }
        List<Claim> owned = ownedBy(target.getUniqueId());
        if (owned.isEmpty()) {
            plugin.sendMini(p, "<gray>" + esc(name(target)) + " owns no land.");
            return;
        }
        long blocks = 0;
        for (Claim c : owned) {
            blocks += c.blocks();
        }
        for (Claim c : owned) {
            cm().deleteClaim(c);
        }
        plugin.sendMini(p, "<green>Removed <white>" + owned.size() + "</white> territor"
                + (owned.size() == 1 ? "y" : "ies") + " (<white>" + blocks
                + "</white> blocks) belonging to <white>" + esc(name(target))
                + "</white>. Nothing was refunded.");
    }

    private void deleteServer(Player p) {
        List<Claim> server = new ArrayList<>();
        for (Claim c : cm().territories()) {
            if (c.isServer()) {
                server.add(c);
            }
        }
        if (server.isEmpty()) {
            plugin.sendMini(p, "<gray>There are no server claims.");
            return;
        }
        if (server.size() > MAX_SERVER_WIPE) {
            plugin.send(p, "admin-server-wipe-blocked",
                    "count", String.valueOf(server.size()),
                    "max", String.valueOf(MAX_SERVER_WIPE));
            return;
        }
        long blocks = 0;
        for (Claim c : server) {
            blocks += c.blocks();
        }
        for (Claim c : server) {
            cm().deleteClaim(c);
        }
        plugin.send(p, "admin-server-wiped",
                "count", String.valueOf(server.size()), "blocks", String.valueOf(blocks));
    }

    private void transfer(Player p, String[] args) {
        if (args.length < 3) {
            plugin.sendMini(p, "<gray>Usage: <white>/claims admin transfer <player>");
            return;
        }
        Claim c = cm().at(p.getLocation());
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        OfflinePlayer target = resolve(p, args[2]);
        if (target == null) {
            return;
        }
        if (target.getUniqueId().equals(c.owner)) {
            plugin.sendMini(p, "<yellow>That territory already belongs to "
                    + esc(name(target)) + ".");
            return;
        }
        String was = c.isServer() ? "the server" : c.ownerName;
        cm().transfer(c, target.getUniqueId(), name(target));
        plugin.send(p, "admin-transferred",
                "blocks", String.valueOf(c.blocks()),
                "from", was,
                "to", name(target));
    }

    /**
     * Gives the staff member the netherite selection shovel and switches them into server
     * claim mode. Selecting two corners then creates server land for exactly that rectangle,
     * however big, and the shovel disappears.
     *
     * A radius argument is still accepted for the old square behaviour, so nothing that
     * already works in a script or a habit breaks.
     */
    private void serverClaim(Player p, String[] args) {
        if (args.length > 2) {
            try {
                int radius = Math.max(0, Math.min(10, Integer.parseInt(args[2])));
                cm().serverClaim(p, radius);
                return;
            } catch (NumberFormatException e) {
                plugin.sendMini(p, "<red>Usage: <white>/claims admin serverclaim"
                        + " <gray>for the selection tool, or "
                        + "<white>/claims admin serverclaim <0-10><gray> for a square.");
                return;
            }
        }
        plugin.selection().beginServerSelection(p);
    }

    /** Removes the whole territory the admin is standing in. Kept as an alias of delete. */
    private void unclaimHere(Player p) {
        Claim c = cm().at(p.getLocation());
        if (c == null) {
            plugin.send(p, "no-claim-here");
            return;
        }
        cm().adminUnclaim(p);
    }

    // ------------------------------------------------------- limits and time

    private void setLimit(Player p, String[] args) {
        if (args.length < 4) {
            plugin.sendMini(p, "<gray>Usage: <white>/claims admin setlimit <player> <blocks>"
                    + " <gray>(0 removes the override)");
            return;
        }
        OfflinePlayer target = resolve(p, args[2]);
        if (target == null) {
            return;
        }
        long blocks;
        try {
            blocks = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            plugin.sendMini(p, "<red>That is not a number of blocks.");
            return;
        }
        if (blocks < 0) {
            plugin.sendMini(p, "<red>The limit cannot be negative.");
            return;
        }
        cm().setLimitOverride(target.getUniqueId(), blocks);
        if (blocks == 0) {
            plugin.send(p, "admin-limit-cleared", "player", name(target));
        } else {
            plugin.send(p, "admin-limit-set",
                    "player", name(target), "blocks", String.valueOf(blocks));
        }
    }

    private void setUpkeep(Player p, String[] args) {
        if (args.length < 4) {
            plugin.sendMini(p, "<gray>Usage: <white>/claims admin setupkeep <player> <hours|status>");
            return;
        }
        OfflinePlayer target = resolve(p, args[2]);
        if (target == null) {
            return;
        }
        List<Claim> owned = ownedBy(target.getUniqueId());
        if (owned.isEmpty()) {
            plugin.sendMini(p, "<gray>" + esc(name(target)) + " owns no land.");
            return;
        }
        if (args[3].equalsIgnoreCase("status")) {
            plugin.sendMini(p, "<gray>Upkeep for <white>" + esc(name(target)) + "<gray>:");
            for (Claim c : owned) {
                plugin.sendMini(p, "  <white>" + c.shortId() + " <gray>| "
                        + Util.duration(cm().remainingMs(c)) + " left | " + cm().statusLine(c));
            }
            return;
        }
        double hours;
        try {
            hours = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            plugin.sendMini(p, "<red>That is not a number of hours. Use a number, or status.");
            return;
        }
        if (hours < 0) {
            plugin.sendMini(p, "<red>Hours cannot be negative.");
            return;
        }
        for (Claim c : owned) {
            cm().setUpkeep(c, hours);
        }
        plugin.send(p, "admin-upkeep-set",
                "player", name(target),
                "time", Util.duration((long) (hours * 3_600_000L)),
                "count", String.valueOf(owned.size()));
    }

    // ----------------------------------------------------------------- helpers

    private List<Claim> ownedBy(UUID owner) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : cm().territories()) {
            if (owner.equals(c.owner)) {
                out.add(c);
            }
        }
        return out;
    }

    private String name(OfflinePlayer p) {
        String n = p.getName();
        return n == null ? p.getUniqueId().toString() : n;
    }

    private String esc(String s) {
        return plugin.escape(s);
    }

    /** Online players first, then anyone the server has seen before. */
    private OfflinePlayer resolve(Player p, String arg) {
        Player online = Bukkit.getPlayerExact(arg);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(arg);
        if (cached != null) {
            return cached;
        }
        if (arg.length() == 36) {
            try {
                OfflinePlayer byId = Bukkit.getOfflinePlayer(UUID.fromString(arg));
                if (byId.hasPlayedBefore() || byId.isOnline()) {
                    return byId;
                }
            } catch (IllegalArgumentException ignored) {
                // not a uuid
            }
        }
        plugin.send(p, "unknown-player");
        return null;
    }

    // ------------------------------------------------------------ tab complete

    public List<String> complete(Player p, String[] args) {
        List<String> out = new ArrayList<>();
        String q = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (args.length == 2) {
            for (String s : List.of("help", "bypass", "serverclaim", "unclaim", "reload",
                    "delete", "deleteplayer", "deleteserver", "transfer", "setlimit",
                    "setupkeep", "list", "who", "info")) {
                if (s.startsWith(q)) {
                    out.add(s);
                }
            }
            return out;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        boolean wantsPlayer = switch (sub) {
            case "deleteplayer", "transfer", "setlimit", "setupkeep", "list", "info" -> true;
            default -> false;
        };
        if (args.length == 3 && wantsPlayer) {
            String a = args[2].toLowerCase(Locale.ROOT);
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.getName().toLowerCase(Locale.ROOT).startsWith(a)) {
                    out.add(o.getName());
                }
            }
            return out;
        }
        if (args.length == 3 && sub.equals("serverclaim")) {
            String a = args[2].toLowerCase(Locale.ROOT);
            for (String s : List.of("0", "1", "2", "3", "5", "10")) {
                if (s.startsWith(a)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 4 && sub.equals("setupkeep")) {
            String a = args[3].toLowerCase(Locale.ROOT);
            for (String s : List.of("status", "24", "72", "168", "336")) {
                if (s.startsWith(a)) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
