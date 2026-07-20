package jp.wsn0672.gamblegate.pass;

import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.BlockPosition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class PassMachineEffects implements Runnable {
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final Map<BlockPosition, TextDisplay> bgmLabels = new HashMap<>();
    private long ticks;
    private double phase;

    public PassMachineEffects(GambleGatePlugin plugin, VenueRepository venues, MessageService messages) {
        this.plugin = plugin;
        this.venues = venues;
        this.messages = messages;
    }

    @Override
    public void run() {
        int interval = Math.max(1, plugin.getConfig().getInt("pass-machine-effects.interval-ticks", 10));
        if (++ticks % interval != 0) return;
        syncBgmLabels();
        if (!plugin.getConfig().getBoolean("pass-machine-effects.enabled", true)) return;
        phase = (phase + 0.2) % (Math.PI * 2);
        double viewDistance = Math.max(1, plugin.getConfig().getDouble("pass-machine-effects.view-distance", 32));
        venues.all().forEach(venue -> venue.passMachines().forEach(machine -> render(machine, viewDistance)));
    }

    private void render(BlockPosition machine, double viewDistance) {
        World world = Bukkit.getWorld(machine.world());
        if (world == null || !world.isChunkLoaded(machine.x() >> 4, machine.z() >> 4)) return;
        double centerX = machine.x() + 0.5;
        double centerY = machine.y() + 0.5;
        double centerZ = machine.z() + 0.5;
        double maxDistanceSquared = viewDistance * viewDistance;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(new org.bukkit.Location(world, centerX, centerY, centerZ)) > maxDistanceSquared) continue;
            double y = centerY + 0.35 + Math.sin(phase) * 0.08;
            player.spawnParticle(Particle.ENCHANT, centerX, y, centerZ, 2, 0.22, 0.18, 0.22, 0.02);
            if (ticks % (intervalForBurst() * 4L) == 0) {
                player.spawnParticle(Particle.ELECTRIC_SPARK, centerX, centerY + 0.45, centerZ, 1, 0.12, 0.12, 0.12, 0.01);
            }
        }
    }

    private int intervalForBurst() {
        return Math.max(1, plugin.getConfig().getInt("pass-machine-effects.interval-ticks", 10));
    }

    private void syncBgmLabels() {
        Set<BlockPosition> configured = new HashSet<>();
        venues.all().forEach(venue -> configured.addAll(venue.bgmButtons()));
        bgmLabels.entrySet().removeIf(entry -> {
            if (configured.contains(entry.getKey())) return false;
            remove(entry.getValue());
            return true;
        });
        for (BlockPosition button : configured) syncBgmLabel(button);
    }

    private void syncBgmLabel(BlockPosition button) {
        World world = Bukkit.getWorld(button.world());
        if (world == null || !world.isChunkLoaded(button.x() >> 4, button.z() >> 4)) return;
        if (!world.getBlockAt(button.x(), button.y(), button.z()).getType().name().endsWith("_BUTTON")) {
            remove(bgmLabels.remove(button));
            return;
        }
        TextDisplay display = bgmLabels.get(button);
        if (display == null || !display.isValid()) {
            Location location = new Location(world, button.x() + 0.5, button.y() + 1.15, button.z() + 0.5);
            display = world.spawn(location, TextDisplay.class, entity -> {
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                entity.setShadowed(true);
                entity.setSeeThrough(true);
                entity.setLineWidth(120);
                entity.setPersistent(false);
                entity.setViewRange(0.6f);
            });
            bgmLabels.put(button, display);
        }
        display.setText(messages.text("passes.bgm-display"));
    }

    public void shutdown() {
        bgmLabels.values().forEach(this::remove);
        bgmLabels.clear();
    }

    private void remove(TextDisplay display) {
        if (display != null && display.isValid()) display.remove();
    }
}
