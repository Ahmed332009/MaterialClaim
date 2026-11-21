package dev.keepclaims;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Golem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.Hanging;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps non-trusted players and mobs from changing claimed chunks. */
public final class ProtectionListener implements Listener {
    private final KeepClaims plugin;
    private final ClaimManager claims;
    private final Map<UUID, Long> denyCooldown = new ConcurrentHashMap<>();

    public ProtectionListener(KeepClaims plugin) {
        this.plugin = plugin;
        this.claims = plugin.claims();
    }

    // ----------------------------------------------------------------- helpers

    private boolean check(Player p, Block b) {
        Claim c = claims.at(b);
        if (claims.allowed(p, c)) {
            return true;
        }
        deny(p, c);
        return false;
    }

    private void deny(Player p, Claim c) {
        long now = System.currentTimeMillis();
        Long last = denyCooldown.get(p.getUniqueId());
        if (last != null && now - last < 1500L) {
            return;
        }
        denyCooldown.put(p.getUniqueId(), now);
        plugin.actionBar(p, "protected", "owner", c.isServer() ? "the server" : c.ownerName);
    }

    private Player playerFrom(Entity e) {
        if (e instanceof Player p) {
            return p;
        }
        if (e instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) {
                return p;
            }
        }
        return null;
    }

    private boolean protectedEntity(Entity e) {
        return e instanceof Hanging
                || e instanceof ArmorStand
                || e instanceof Animals
                || e instanceof AbstractVillager
                || e instanceof Golem
                || e instanceof WaterMob
                || e instanceof Allay
                || e instanceof org.bukkit.entity.Vehicle;
    }

    private boolean crosses(Claim pistonClaim, Claim other) {
        return other != null && (pistonClaim == null || !claims.sameOwner(pistonClaim, other));
    }

    private boolean isPublic(Material m) {
        return plugin.getConfig().getStringList("public-blocks").contains(m.name());
    }

    private static final Set<Material> BLOCK_ITEMS = EnumSet.of(Material.BONE_MEAL, Material.FLINT_AND_STEEL,
            Material.FIRE_CHARGE, Material.BRUSH, Material.SHEARS, Material.END_CRYSTAL, Material.ARMOR_STAND,
            Material.ENDER_EYE, Material.GLASS_BOTTLE);

    private boolean actsOnBlocks(Material m) {
        String n = m.name();
        return BLOCK_ITEMS.contains(m)
                || n.endsWith("_HOE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_SPAWN_EGG") || n.endsWith("_BOAT") || n.endsWith("_RAFT")
                || n.endsWith("MINECART");
    }

    // ------------------------------------------------------------ player blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!check(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!check(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) {
            return;
        }
        Player p = e.getPlayer();
        Claim c = claims.at(b);
        if (c == null || claims.allowed(p, c)) {
            return;
        }
        if (e.getAction() == Action.PHYSICAL) {
            Material t = b.getType();
            if (t == Material.FARMLAND || t == Material.TURTLE_EGG || t == Material.SNIFFER_EGG) {
                e.setCancelled(true);
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        boolean blocked = false;
        if (b.getType().isInteractable() && !isPublic(b.getType())) {
            e.setUseInteractedBlock(Event.Result.DENY);
            blocked = true;
        }
        if (e.getItem() != null && actsOnBlocks(e.getItem().getType())) {
            e.setUseItemInHand(Event.Result.DENY);
            blocked = true;
        }
        if (blocked) {
            deny(p, c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!check(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!check(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
        }
    }

    // ----------------------------------------------------------------- entities

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity target = e.getRightClicked();
        if (target instanceof AbstractVillager || !protectedEntity(target)) {
            return;
        }
        Claim c = claims.at(target.getLocation());
        if (!claims.allowed(e.getPlayer(), c)) {
            deny(e.getPlayer(), c);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        Claim c = claims.at(e.getRightClicked().getLocation());
        if (!claims.allowed(e.getPlayer(), c)) {
            deny(e.getPlayer(), c);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (!(e.getEntered() instanceof Player p)) {
            return;
        }
        Claim c = claims.at(e.getVehicle().getLocation());
        if (!claims.allowed(p, c)) {
            deny(p, c);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!protectedEntity(e.getEntity())) {
            return;
        }
        Player p = playerFrom(e.getDamager());
        if (p == null) {
            return;
        }
        Claim c = claims.at(e.getEntity().getLocation());
        if (!claims.allowed(p, c)) {
            deny(p, c);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        Player p = e.getPlayer();
        if (p == null) {
            return;
        }
        Claim c = claims.at(e.getEntity().getLocation());
        if (!claims.allowed(p, c)) {
            deny(p, c);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Claim c = claims.at(e.getEntity().getLocation());
        if (c == null) {
            return;
        }
        Player p = playerFrom(e.getRemover());
        if (p == null) {
            e.setCancelled(true);
        } else if (!claims.allowed(p, c)) {
            deny(p, c);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player || e.getEntity() instanceof FallingBlock) {
            return;
        }
        if (claims.at(e.getBlock()) != null) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------- explosions, fire, fluids

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> claims.at(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> claims.at(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (claims.at(e.getBlock()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Claim c = claims.at(e.getBlock());
        if (c == null) {
            return;
        }
        Player p = e.getPlayer();
        if (p == null) {
            e.setCancelled(true);
        } else if (!claims.allowed(p, c)) {
            deny(p, c);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Claim to = claims.at(e.getToBlock());
        if (to == null) {
            return;
        }
        Claim from = claims.at(e.getBlock());
        if (from == null || !claims.sameOwner(from, to)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        Claim pistonClaim = claims.at(e.getBlock());
        BlockFace dir = e.getDirection();
        if (crosses(pistonClaim, claims.at(e.getBlock().getRelative(dir)))) {
            e.setCancelled(true);
            return;
        }
        for (Block b : e.getBlocks()) {
            if (crosses(pistonClaim, claims.at(b)) || crosses(pistonClaim, claims.at(b.getRelative(dir)))) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        Claim pistonClaim = claims.at(e.getBlock());
        for (Block b : e.getBlocks()) {
            if (crosses(pistonClaim, claims.at(b))) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // -------------------------------------------------------------------- join

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            int low = claims.lowCount(p.getUniqueId());
            if (low > 0) {
                plugin.send(p, "expiring-warning", "count", String.valueOf(low));
            }
        }, 60L);
    }
}
