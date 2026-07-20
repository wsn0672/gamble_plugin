package jp.wsn0672.gamblegate.vip;

import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.SoundService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.Venue;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class VipAccessManager implements Listener, Runnable {
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final SoundService sounds;
    private final Map<UUID, Location> lastNonVipLocations = new HashMap<>();
    private final Map<UUID, Long> lastWarningAt = new HashMap<>();

    public VipAccessManager(GambleGatePlugin plugin, VenueRepository venues, MessageService messages, SoundService sounds) {
        this.plugin = plugin; this.venues = venues; this.messages = messages; this.sounds = sounds;
    }

    public boolean hasAccess(Player player, Venue venue) {
        String venueNode = "gamblegate.vip." + venue.id().toLowerCase(Locale.ROOT);
        return player.hasPermission("gamblegate.vip") || player.hasPermission(venueNode) || player.hasPermission("gamblegate.bypass");
    }

    @Override
    public void run() { updateDoors(); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) return;
        Player player = event.getPlayer();
        if (vipVenueAt(event.getFrom()) == null) lastNonVipLocations.put(player.getUniqueId(), event.getFrom().clone());
        Venue vipVenue = vipVenueAt(event.getTo());
        if (vipVenue == null || hasAccess(player, vipVenue)) return;
        Location fallback = safeFallback(player, vipVenue, event.getFrom());
        event.setTo(fallback == null ? event.getFrom() : fallback);
        deny(player, vipVenue);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        if (vipVenueAt(event.getFrom()) == null) lastNonVipLocations.put(event.getPlayer().getUniqueId(), event.getFrom().clone());
        Venue vipVenue = vipVenueAt(event.getTo());
        if (vipVenue == null || hasAccess(event.getPlayer(), vipVenue)) return;
        event.setCancelled(true); deny(event.getPlayer(), vipVenue);
        if (vipVenue.vipContains(event.getFrom())) {
            Location fallback = safeFallback(event.getPlayer(), vipVenue, event.getFrom());
            if (fallback != null) Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().teleport(fallback, PlayerTeleportEvent.TeleportCause.PLUGIN));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDoorInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        FoundDoor found = findDoor(event.getClickedBlock()); if (found == null) return;
        event.setCancelled(true);
        if (!hasAccess(event.getPlayer(), found.venue())) deny(event.getPlayer(), found.venue());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRedstone(BlockRedstoneEvent event) {
        if (findDoor(event.getBlock()) != null) event.setNewCurrent(0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastNonVipLocations.remove(event.getPlayer().getUniqueId());
        lastWarningAt.remove(event.getPlayer().getUniqueId());
    }

    public void closeDoors(Venue venue) {
        for (BlockPosition position : venue.vipDoors()) {
            World world = Bukkit.getWorld(position.world());
            if (world != null && world.isChunkLoaded(position.x() >> 4, position.z() >> 4))
                setDoorOpen(world.getBlockAt(position.x(), position.y(), position.z()), false);
        }
    }

    public void resetRuntime() {
        for (Venue venue : venues.all()) closeDoors(venue);
        lastNonVipLocations.clear(); lastWarningAt.clear();
    }

    private void updateDoors() {
        double range = Math.max(0.5, plugin.getConfig().getDouble("vip.door-open-distance", 1.0));
        for (Venue venue : venues.all()) for (BlockPosition position : venue.vipDoors()) {
            World world = Bukkit.getWorld(position.world()); if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) continue;
            Block bottom = world.getBlockAt(position.x(), position.y(), position.z()); if (bottom.getType() != Material.IRON_DOOR) continue;
            boolean open = world.getPlayers().stream().anyMatch(player -> hasAccess(player, venue) && nearDoor(player, bottom, range));
            setDoorOpen(bottom, open);
        }
    }

    private boolean nearDoor(Player player, Block bottom, double range) {
        Location location = player.getLocation(); if (location.getWorld() != bottom.getWorld()) return false;
        double dx = location.getX() - (bottom.getX() + 0.5), dz = location.getZ() - (bottom.getZ() + 0.5);
        return dx * dx + dz * dz <= range * range && location.getY() >= bottom.getY() - 1 && location.getY() <= bottom.getY() + 3;
    }

    private void setDoorOpen(Block bottom, boolean open) {
        if (!(bottom.getBlockData() instanceof Door lower)) return;
        if (lower.getHalf() == Bisected.Half.TOP) { bottom = bottom.getRelative(BlockFace.DOWN); if (!(bottom.getBlockData() instanceof Door corrected)) return; lower = corrected; }
        if (lower.isOpen() != open) { lower.setOpen(open); bottom.setBlockData(lower, false); }
        Block top = bottom.getRelative(BlockFace.UP);
        if (top.getBlockData() instanceof Door upper && upper.isOpen() != open) { upper.setOpen(open); top.setBlockData(upper, false); }
    }

    private FoundDoor findDoor(Block block) {
        if (block.getType() != Material.IRON_DOOR) return null;
        Block bottom = block;
        if (block.getBlockData() instanceof Door door && door.getHalf() == Bisected.Half.TOP) bottom = block.getRelative(BlockFace.DOWN);
        BlockPosition position = position(bottom);
        for (Venue venue : venues.all()) if (venue.vipDoors().contains(position)) return new FoundDoor(venue);
        return null;
    }

    private Venue vipVenueAt(Location location) { return venues.all().stream().filter(venue -> venue.vipContains(location)).findFirst().orElse(null); }
    private Location safeFallback(Player player, Venue venue, Location origin) {
        Location saved = lastNonVipLocations.get(player.getUniqueId());
        if (saved != null && saved.getWorld() != null && !venue.vipContains(saved)) return saved.clone();
        for (var gate : venue.gates().values()) if (gate.entranceDestination() != null) {
            Location candidate = gate.entranceDestination().toBukkit();
            if (candidate != null && venue.contains(candidate) && !venue.vipContains(candidate)) return candidate;
        }
        return venue.vipContains(origin) ? null : origin;
    }

    private void deny(Player player, Venue venue) {
        long now = System.currentTimeMillis(); long previous = lastWarningAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < 1000) return; lastWarningAt.put(player.getUniqueId(), now);
        player.sendTitle(messages.text("vip.denied-title"), messages.text("vip.denied-subtitle", Map.of("venue", venue.id())), 5, 45, 10);
        messages.send(player, "vip.denied-message", Map.of("venue", venue.id())); sounds.play(player, "security-warning");
    }

    private BlockPosition position(Block block) { return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()); }
    private boolean sameBlock(Location first, Location second) { return first.getWorld() == second.getWorld() && first.getBlockX() == second.getBlockX() && first.getBlockY() == second.getBlockY() && first.getBlockZ() == second.getBlockZ(); }
    private record FoundDoor(Venue venue) {}
}
