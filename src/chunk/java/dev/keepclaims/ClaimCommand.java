package dev.keepclaims;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClaimCommand implements CommandExecutor, TabCompleter {
    private final KeepClaims plugin;

    public ClaimCommand(KeepClaims plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Console may reload.
        if (!(sender instanceof Player p)) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("reload")) {
                plugin.reloadAll();
                plugin.send(sender, "reloaded");
            } else {
                sender.sendMessage("MaterialClaim: use '/claims admin reload' from the console.");
            }
            return true;
        }
        if (!p.hasPermission("materialclaim.use")) {
            plugin.send(p, "no-permission");
            return true;
        }
        ClaimManager cm = plugin.claims();
        GuiManager gui = plugin.gui();
        if (args.length == 0) {
            gui.openMain(p);
            return true;
        }
        ChunkKey here = cm.keyOf(p.getLocation());
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> gui.openMain(p);
            case "claim" -> cm.claimHere(p);
            case "unclaim" -> cm.unclaim(p, here);
            case "pay" -> gui.openPay(p, here);
            case "list" -> gui.openMyClaims(p);
            case "borders" -> gui.showBorders(p, here);
            case "info" -> {
                Claim c = cm.get(here);
                if (c == null) {
                    plugin.send(p, "no-claim-here");
                } else {
                    plugin.sendMini(p, "<gray>Owner: <white>" + (c.isServer() ? "Server" : c.ownerName)
                            + " <gray>| <white>" + c.size() + " chunk(s) <gray>| " + cm.statusLine(c));
                }
            }
            case "flags" -> flags(p, args.length > 1 ? args[1] : null);
            case "flag" -> flag(p, args);
            case "trust", "untrust" -> {
                if (args.length < 2) {
                    plugin.sendMini(p, "<red>Usage: /claims trust <player> [builder|manager]  or  /claims untrust <player>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    target = Bukkit.getOfflinePlayerIfCached(args[1]);
                }
                if (target == null) {
                    plugin.send(p, "unknown-player");
                    return true;
                }
                String name = target.getName() != null ? target.getName() : args[1];
                if (args[0].equalsIgnoreCase("trust")) {
                    TrustList.Level level = null;
                    if (args.length > 2) {
                        String lv = args[2].toLowerCase(Locale.ROOT);
                        if (lv.equals("builder")) {
                            level = TrustList.Level.BUILDER;
                        } else if (lv.equals("manager")) {
                            level = TrustList.Level.MANAGER;
                        } else {
                            plugin.sendMini(p, "<red>Level must be builder or manager.");
                            return true;
                        }
                    }
                    cm.trust(p, here, target.getUniqueId(), name, level);
                } else {
                    cm.untrust(p, here, target.getUniqueId(), name);
                }
            }
            case "admin" -> admin(p, args);
            default -> gui.openMain(p);
        }
        return true;
    }

    // ------------------------------------------------------------------ flags

    /** Resolves the territory from the optional argument, else the chunk the player stands in. */
    private Claim resolveTerritory(Player p, String query) {
        ClaimManager cm = plugin.claims();
        if (query != null && !query.isBlank()) {
            Claim found = cm.findTerritory(query);
            if (found == null) {
                plugin.send(p, "territory-not-found", "query", query);
            }
            return found;
        }
        Claim here = cm.get(cm.keyOf(p.getLocation()));
        if (here == null) {
            plugin.send(p, "no-claim-here");
        }
        return here;
    }

    private void flag(Player p, String[] args) {
        if (args.length < 3) {
            plugin.sendMini(p, "<gray>/claims flag <flag> <value> [territory]");
            plugin.sendMini(p, "<gray>Flags: " + flagIds());
            return;
        }
        String rawFlag = args[1];
        String value = args[2];
        String territoryArg = args.length > 3 ? args[3] : null;

        // pvp-scope and the text fields are not simple on/off flags.
        if (rawFlag.equalsIgnoreCase("pvp-scope")) {
            TerritorySettings.PvpScope scope = parseScope(value);
            if (scope == null) {
                plugin.sendMini(p, "<red>Use everyone, visitors, trusted or nobody.");
                return;
            }
            Claim c = resolveTerritory(p, territoryArg);
            if (c == null) {
                return;
            }
            plugin.claims().setPvpScope(p, c, scope);
            return;
        }

        SettingsService.TextInput field = switch (rawFlag.toLowerCase(Locale.ROOT)) {
            case "name" -> SettingsService.TextInput.NAME;
            case "entry-message" -> SettingsService.TextInput.ENTRY_MESSAGE;
            case "leave-message" -> SettingsService.TextInput.LEAVE_MESSAGE;
            default -> SettingsService.TextInput.NONE;
        };
        if (field != SettingsService.TextInput.NONE) {
            Claim c = resolveTerritory(p, territoryArg);
            if (c == null) {
                return;
            }
            // Join the remaining arguments so unquoted messages work.
            StringBuilder sb = new StringBuilder(value);
            for (int i = 3; i < args.length; i++) {
                sb.append(' ').append(args[i]);
            }
            plugin.claims().setText(p, c, field, sb.toString());
            return;
        }

        SettingsService.Flag flag = SettingsService.Flag.parse(rawFlag);
        if (flag == null) {
            plugin.send(p, "flag-unknown", "flag", rawFlag);
            return;
        }
        Boolean on = parseBool(value);
        if (on == null) {
            plugin.sendMini(p, "<red>Use on or off.");
            return;
        }
        Claim c = resolveTerritory(p, territoryArg);
        if (c == null) {
            return;
        }
        if (plugin.claims().changeFlag(p, c, flag, on)) {
            plugin.claims().saveNow();
        }
    }

    /** everyone, visitors, trusted or nobody. Returns null for anything else. */
    private static TerritorySettings.PvpScope parseScope(String raw) {
        String v = raw.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (v) {
            case "everyone", "all" -> TerritorySettings.PvpScope.EVERYONE;
            case "visitors", "visitorsonly" -> TerritorySettings.PvpScope.VISITORS_ONLY;
            case "trusted", "trustedonly", "members", "membersonly" -> TerritorySettings.PvpScope.TRUSTED_ONLY;
            case "nobody", "noone", "none" -> TerritorySettings.PvpScope.NOBODY;
            default -> null;
        };
    }

    private static Boolean parseBool(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "allow", "allowed" -> Boolean.TRUE;
            case "off", "false", "no", "deny", "block", "blocked" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String flagIds() {
        StringBuilder sb = new StringBuilder();
        for (SettingsService.Flag f : SettingsService.Flag.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(f.id);
        }
        sb.append(", pvp-scope, name, entry-message, leave-message");
        return sb.toString();
    }

    private void flags(Player p, String territoryArg) {
        Claim c = resolveTerritory(p, territoryArg);
        if (c == null) {
            return;
        }
        SettingsService svc = plugin.settings();
        plugin.sendMini(p, "<gray>Settings for <white>" + plugin.escape(c.displayName())
                + "<gray> (" + c.size() + " chunks)");
        for (SettingsService.Flag f : SettingsService.Flag.values()) {
            // get() reports "enabled/protecting" for paid flags and "allowed" for free
            // toggles, so this reads the same way as the GUI.
            boolean value = svc.get(c, f);
            String shown = f.isPaid()
                    ? (value ? "<green>ON<gray> (protecting)" : "<yellow>OFF<gray> (vanilla)")
                    : (value ? "<green>on" : "<red>off");
            if (f.isPaid()) {
                shown += c.settings.isPurchased(f.id)
                        ? " <dark_gray>[purchased, free to toggle, +"
                          + Math.round(svc.upkeepMultiplier() * 100) + "% upkeep]"
                        : " <dark_gray>[not bought - costs 24h upkeep once]";
            }
            plugin.sendMini(p, "<gray>- <white>" + f.id + "<gray>: " + shown);
        }
        plugin.sendMini(p, "<gray>- <white>pvp-scope<gray>: <white>" + c.settings.pvpScope.label());
        plugin.sendMini(p, "<gray>- <white>name<gray>: <white>"
                + plugin.escape(c.settings.customName.isBlank() ? "(none)" : c.settings.customName));
        plugin.sendMini(p, "<gray>- <white>entry-message<gray>: <white>"
                + plugin.escape(c.settings.entryMessage.isBlank() ? "(none)" : c.settings.entryMessage));
        plugin.sendMini(p, "<gray>- <white>leave-message<gray>: <white>"
                + plugin.escape(c.settings.leaveMessage.isBlank() ? "(none)" : c.settings.leaveMessage));
    }

    private void admin(Player p, String[] args) {
        if (!p.hasPermission("materialclaim.admin")) {
            plugin.send(p, "no-permission");
            return;
        }
        ClaimManager cm = plugin.claims();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "bypass" -> {
                if (plugin.bypassing().remove(p.getUniqueId())) {
                    plugin.send(p, "bypass-off");
                } else {
                    plugin.bypassing().add(p.getUniqueId());
                    plugin.send(p, "bypass-on");
                }
            }
            case "serverclaim" -> {
                int radius = 0;
                if (args.length > 2) {
                    try {
                        radius = Math.max(0, Math.min(10, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ignored) {
                        // keep 0
                    }
                }
                cm.serverClaim(p, radius);
            }
            case "unclaim" -> cm.adminUnclaim(p);
            case "reload" -> {
                plugin.reloadAll();
                plugin.send(p, "reloaded");
            }
            default -> plugin.sendMini(p, "<gray>/claims admin <bypass|serverclaim [radius]|unclaim|reload>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("menu", "claim", "unclaim", "pay", "list", "borders", "info",
                    "flags", "flag", "trust", "untrust", "admin")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(o.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("trust")) {
            for (String s : List.of("builder", "manager")) {
                if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("flag")) {
            for (SettingsService.Flag f : SettingsService.Flag.values()) {
                if (f.id.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(f.id);
                }
            }
            for (String s : List.of("pvp-scope", "name", "entry-message", "leave-message")) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("flag")) {
            List<String> values = args[1].equalsIgnoreCase("pvp-scope")
                    ? List.of("everyone", "visitors", "trusted", "nobody")
                    : List.of("on", "off");
            for (String s : values) {
                if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission("materialclaim.admin")) {
            for (String s : List.of("bypass", "serverclaim", "unclaim", "reload")) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
