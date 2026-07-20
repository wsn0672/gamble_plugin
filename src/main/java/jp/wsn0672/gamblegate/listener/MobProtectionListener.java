package jp.wsn0672.gamblegate.listener;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.config.VenueRepository;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;

public final class MobProtectionListener implements Listener {
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;

    public MobProtectionListener(GambleGatePlugin plugin, VenueRepository venues) {
        this.plugin = plugin;
        this.venues = venues;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (isProtectedMob(event.getEntity()) && isProtected(event.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(EntityMoveEvent event) {
        if (!isProtectedMob(event.getEntity()) || !isProtected(event.getTo())) return;
        if (isProtected(event.getFrom())) event.getEntity().remove();
        else event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        if (!isProtectedMob(event.getEntity()) || event.getTo() == null || !isProtected(event.getTo())) return;
        event.setCancelled(true);
        if (isProtected(event.getFrom())) event.getEntity().remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPathfind(EntityPathfindEvent event) {
        if (isProtectedMob(event.getEntity()) && isProtected(event.getLoc())) event.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) removeIfProtected(entity);
    }

    public void removeProtectedMobs() {
        Bukkit.getWorlds().forEach(world -> world.getEntities().forEach(this::removeIfProtected));
    }

    private void removeIfProtected(Entity entity) {
        if (isProtectedMob(entity) && isProtected(entity.getLocation())) entity.remove();
    }

    private boolean isProtectedMob(Entity entity) {
        return entity instanceof Enemy || entity instanceof Enderman;
    }

    private boolean isProtected(Location location) {
        double radius = Math.max(0, plugin.getConfig().getDouble("hostile-mob-protection-radius", 20));
        return venues.all().stream().anyMatch(venue -> venue.withinDistance(location, radius));
    }
}
