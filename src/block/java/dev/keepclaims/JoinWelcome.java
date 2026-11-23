package dev.keepclaims;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Greets a player who has never played here before and explains the claim plugin.
 *
 * "First time" means the player has no prior play data, which is checked with
 * {@code hasPlayedBefore()}. That is deliberately not "has no claims": a returning player
 * who owns nothing must not be greeted as a newcomer on every join.
 *
 * Every line is a normal config.yml message, so the whole welcome can be reworded,
 * shortened or removed without touching code. Set {@code welcome-first-join: false} to
 * switch it off.
 */
public final class JoinWelcome implements Listener {
    private final KeepClaims plugin;

    public JoinWelcome(KeepClaims plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("welcome-first-join", true)) {
            return;
        }
        Player p = e.getPlayer();
        if (p.hasPlayedBefore()) {
            return;
        }
        // Half a second later, so the messages land after the vanilla join spam.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            plugin.send(p, "welcome");
            plugin.send(p, "welcome-claim");
            plugin.send(p, "welcome-claim-line");
            plugin.send(p, "welcome-claim-line2");
            plugin.send(p, "welcome-command");
        }, 10L);
    }
}
