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
            case "trust", "untrust" -> {
                if (args.length < 2) {
                    plugin.sendMini(p, "<red>Usage: /claims " + args[0].toLowerCase(Locale.ROOT) + " <player>");
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
                    cm.trust(p, here, target.getUniqueId(), name);
                } else {
                    cm.untrust(p, here, target.getUniqueId(), name);
                }
            }
            case "admin" -> admin(p, args);
            default -> gui.openMain(p);
        }
        return true;
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
            for (String s : List.of("menu", "claim", "unclaim", "pay", "list", "borders", "info", "trust", "untrust", "admin")) {
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
