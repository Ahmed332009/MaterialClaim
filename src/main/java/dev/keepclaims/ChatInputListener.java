package dev.keepclaims;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Collects the one chat line used to name a territory or set its entry/leave message.
 * The menu asks for the value first; the next chat line from that player is captured
 * instead of being sent to the server.
 */
public final class ChatInputListener implements Listener {

    private final KeepClaims plugin;
    private final GuiManager gui;

    public ChatInputListener(KeepClaims plugin, GuiManager gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!GuiManager.hasPendingText(id)) {
            return;
        }
        e.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(e.message());
        // The inventory and claim maps are not thread safe, so finish on the main thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!e.getPlayer().isOnline()) {
                return;
            }
            gui.completeText(e.getPlayer(), message);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        gui.cancelText(e.getPlayer().getUniqueId());
    }
}
