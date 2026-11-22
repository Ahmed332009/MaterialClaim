package dev.keepclaims;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
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
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    /** Last territory each player stood in, for entry/leave messages. */
    private final Map<UUID, Claim> lastTerritory = new ConcurrentHashMap<>();

    private SettingsService settings() {
        return plugin.settings();
    }

    /** True when this territory has the given paid protection active. */
    private boolean protectedBy(Claim c, SettingsService.Flag flag) {
        return c != null && settings().isProtected(c, flag);
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

    private static final Set<Material> DOOR_LIKE = EnumSet.of(
            Material.OAK_DOOR, Material.SPRUCE_DOOR, Material.BIRCH_DOOR, Material.JUNGLE_DOOR,
            Material.ACACIA_DOOR, Material.DARK_OAK_DOOR, Material.MANGROVE_DOOR,
            Material.CHERRY_DOOR, Material.BAMBOO_DOOR, Material.CRIMSON_DOOR, Material.WARPED_DOOR,
            Material.OAK_FENCE_GATE, Material.SPRUCE_FENCE_GATE, Material.BIRCH_FENCE_GATE,
            Material.JUNGLE_FENCE_GATE, Material.ACACIA_FENCE_GATE, Material.DARK_OAK_FENCE_GATE,
            Material.MANGROVE_FENCE_GATE, Material.CHERRY_FENCE_GATE, Material.BAMBOO_FENCE_GATE,
            Material.CRIMSON_FENCE_GATE, Material.WARPED_FENCE_GATE,
            Material.LEVER);

    /**
     * Whether a visitor may use this interactive block, based on the territory's free
     * VISITOR_DOORS / VISITOR_CONTAINERS toggles. Anything that is not a door-like or
     * container block stays blocked for visitors.
     */
    private boolean visitorMayInteract(Claim c, Material m) {
        boolean doorLike = DOOR_LIKE.contains(m)
                || m.name().endsWith("_DOOR")
                || m.name().endsWith("_FENCE_GATE")
                || m.name().endsWith("_BUTTON")
                || m.name().endsWith("_TRAPDOOR")
                || m == Material.BARREL || m == Material.CHEST || m == Material.TRAPPED_CHEST
                || m == Material.HOPPER || m == Material.DROPPER || m == Material.DISPENSER
                || m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER
                || m == Material.BREWING_STAND || m == Material.SHULKER_BOX
                || m == Material.LECTERN;
        if (!doorLike) {
            return false;
        }
        boolean container = m == Material.BARREL || m == Material.CHEST || m == Material.TRAPPED_CHEST
                || m == Material.HOPPER || m == Material.DROPPER || m == Material.DISPENSER
                || m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER
                || m == Material.BREWING_STAND || m == Material.SHULKER_BOX
                || m == Material.LECTERN;
        return container
                ? settings().get(c, SettingsService.Flag.VISITOR_CONTAINERS)
                : settings().get(c, SettingsService.Flag.VISITOR_DOORS);
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
            // Visitors may be allowed to use doors/buttons/levers, or containers.
            if (!visitorMayInteract(c, b.getType())) {
                e.setUseInteractedBlock(Event.Result.DENY);
                blocked = true;
            }
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
        if (c == null || claims.allowed(e.getPlayer(), c)) {
            return;
        }
        // Visitors may be allowed to handle animals and vehicles.
        boolean animal = target instanceof Animals || target instanceof org.bukkit.entity.Vehicle;
        if (animal && settings().get(c, SettingsService.Flag.VISITOR_ANIMALS)) {
            return;
        }
        deny(e.getPlayer(), c);
        e.setCancelled(true);
    }

    /** Damaging, leashing or mounting an animal follows the same visitor toggle. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnimalDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (!(victim instanceof Animals || victim instanceof org.bukkit.entity.Vehicle)) {
            return;
        }
        Player attacker = playerFrom(e.getDamager());
        if (attacker == null) {
            return;
        }
        Claim c = claims.at(victim.getLocation());
        if (c == null || claims.allowed(attacker, c)) {
            return;
        }
        if (settings().get(c, SettingsService.Flag.VISITOR_ANIMALS)) {
            return;
        }
        deny(attacker, c);
        e.setCancelled(true);
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

    /**
     * PvP protection. When PVP is off (protected and paid for), the territory's scope
     * decides who may still fight inside it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = playerFrom(e.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        Claim c = claims.at(victim.getLocation());
        if (!protectedBy(c, SettingsService.Flag.PVP)) {
            return;
        }
        boolean allowed = switch (c.settings.pvpScope) {
            case EVERYONE -> true;
            case VISITORS_ONLY -> !c.mayBuild(attacker.getUniqueId())
                    && !c.mayBuild(victim.getUniqueId());
            case TRUSTED_ONLY -> c.mayBuild(attacker.getUniqueId())
                    && c.mayBuild(victim.getUniqueId());
            case NOBODY -> false;
        };
        if (!allowed) {
            e.setCancelled(true);
            deny(attacker, c);
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

    /**
     * Mob griefing (endermen, withers, ...) and crop trampling. A player changing a
     * block (trampling farmland) is governed by CROP_TRAMPLE; a mob is governed by
     * MOB_GRIEFING.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof FallingBlock) {
            return;
        }
        Claim c = claims.at(e.getBlock());
        if (c == null) {
            return;
        }
        if (e.getEntity() instanceof Player p) {
            // Farmland turning to dirt under a player is a trample.
            if (e.getBlock().getType() != Material.FARMLAND) {
                return;
            }
            if (settings().get(c, SettingsService.Flag.CROP_TRAMPLE)) {
                return;
            }
            if (c.mayBuild(p.getUniqueId())) {
                return;
            }
            e.setCancelled(true);
            deny(p, c);
            return;
        }
        if (!settings().get(c, SettingsService.Flag.MOB_GRIEFING)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------- explosions, fire, fluids

    /**
     * Explosions only damage a block if the territory that owns that block has
     * EXPLOSION_DAMAGE protection off. Each block is judged by its own territory, so
     * a neighbour with the protection active is never damaged regardless of where the
     * explosion came from.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> protectedBy(claims.at(b), SettingsService.Flag.EXPLOSIONS));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> protectedBy(claims.at(b), SettingsService.Flag.EXPLOSIONS));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (protectedBy(claims.at(e.getBlock()), SettingsService.Flag.FIRE_SPREAD)) {
            e.setCancelled(true);
        }
    }

    /** Fire spreading into a territory that has FIRE_SPREAD protection active. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getNewState().getType() != Material.FIRE) {
            return;
        }
        if (protectedBy(claims.at(e.getBlock()), SettingsService.Flag.FIRE_SPREAD)) {
            e.setCancelled(true);
        }
    }

    /** Hostile mobs may not spawn inside a territory that has MOB_SPAWNING protection. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (!e.getEntityType().getCategory().isHostile()) {
            return;
        }
        if (protectedBy(claims.at(e.getLocation()), SettingsService.Flag.MOB_SPAWNING)) {
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
        // A territory that allows fluid flow accepts fluids from anywhere.
        if (settings().get(to, SettingsService.Flag.FLUID_FLOW)) {
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

    // ------------------------------------------------------ entry/leave messages

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        // Only re-check when the player actually changed chunk.
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)
                && from.getWorld() == to.getWorld()) {
            return;
        }
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Claim now = claims.at(to);
        Claim before = lastTerritory.get(id);
        if (before == now) {
            return;
        }
        if (now == null) {
            lastTerritory.remove(id);
        } else {
            lastTerritory.put(id, now);
        }
        if (before != null && !before.settings.leaveMessage.isBlank()) {
            String msg = before.settings.leaveMessage
                    .replace("{territory}", before.displayName())
                    .replace("{player}", p.getName());
            plugin.sendRaw(p, msg);
        }
        if (now != null && !now.settings.entryMessage.isBlank()) {
            String msg = now.settings.entryMessage
                    .replace("{territory}", now.displayName())
                    .replace("{player}", p.getName());
            plugin.sendRaw(p, msg);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastTerritory.remove(id);
        denyCooldown.remove(id);
    }

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
