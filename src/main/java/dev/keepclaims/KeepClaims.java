package dev.keepclaims;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KeepClaims extends JavaPlugin {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ClaimManager claims;
    private PaymentService payments;
    private GuiManager gui;
    private final Set<UUID> bypassing = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        payments = new PaymentService(this);
        claims = new ClaimManager(this);
        gui = new GuiManager(this);
        payments.reload();
        claims.reloadSettings();
        claims.load();

        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(gui, this);

        ClaimCommand command = new ClaimCommand(this);
        PluginCommand pc = getCommand("claims");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        long period = Math.max(1L, getConfig().getLong("check-interval-minutes", 5L)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> claims.expireCheck(), 200L, period);
    }

    @Override
    public void onDisable() {
        if (claims != null) {
            claims.saveNow();
        }
    }

    public void reloadAll() {
        reloadConfig();
        payments.reload();
        claims.reloadSettings();
    }

    public ClaimManager claims() {
        return claims;
    }

    public PaymentService payments() {
        return payments;
    }

    public GuiManager gui() {
        return gui;
    }

    public Set<UUID> bypassing() {
        return bypassing;
    }

    // ---------------------------------------------------------------- messages

    private String apply(String raw, String[] kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", MM.escapeTags(kv[i + 1]));
        }
        return raw;
    }

    private String prefix() {
        return getConfig().getString("messages.prefix", "");
    }

    /** Sends the configured message for a key. kv is pairs of placeholder name and value. */
    public void send(CommandSender to, String key, String... kv) {
        String raw = getConfig().getString("messages." + key, key);
        to.sendMessage(MM.deserialize(prefix() + apply(raw, kv)));
    }

    /** Sends a ready-made MiniMessage string with the prefix. */
    public void sendMini(CommandSender to, String mini) {
        to.sendMessage(MM.deserialize(prefix() + mini));
    }

    public void actionBar(Player p, String key, String... kv) {
        String raw = getConfig().getString("messages." + key, key);
        p.sendActionBar(MM.deserialize(apply(raw, kv)));
    }
}
