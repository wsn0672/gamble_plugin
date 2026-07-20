package jp.wsn0672.gamblegate.effects;

import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.SavedLocation;
import jp.wsn0672.gamblegate.model.Venue;
import org.bukkit.Color;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Entity;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RoofFireworkManager implements Listener {
    private static final String DECORATIVE_TAG = "gamblegate_roof_firework";
    private static final List<List<Color>> COLOR_THEMES = List.of(
            List.of(Color.fromRGB(255, 45, 45), Color.fromRGB(255, 105, 90), Color.fromRGB(190, 20, 25)),
            List.of(Color.fromRGB(255, 125, 20), Color.fromRGB(255, 180, 70), Color.fromRGB(220, 75, 0)),
            List.of(Color.fromRGB(255, 215, 0), Color.fromRGB(255, 240, 90), Color.fromRGB(255, 170, 0)),
            List.of(Color.fromRGB(45, 210, 70), Color.fromRGB(125, 245, 80), Color.fromRGB(0, 150, 75)),
            List.of(Color.fromRGB(0, 220, 255), Color.fromRGB(80, 245, 255), Color.fromRGB(0, 150, 210)),
            List.of(Color.fromRGB(45, 105, 255), Color.fromRGB(100, 180, 255), Color.fromRGB(30, 60, 190)),
            List.of(Color.fromRGB(165, 80, 255), Color.fromRGB(215, 135, 255), Color.fromRGB(105, 40, 180)),
            List.of(Color.fromRGB(255, 70, 175), Color.fromRGB(255, 140, 205), Color.fromRGB(190, 25, 105)),
            List.of(Color.fromRGB(255, 255, 255), Color.fromRGB(210, 225, 255), Color.fromRGB(170, 190, 220))
    );
    private final JavaPlugin plugin;
    private final VenueRepository venues;
    private final CommandSender silentCommandSender;

    public RoofFireworkManager(JavaPlugin plugin, VenueRepository venues) {
        this.plugin = plugin;
        this.venues = venues;
        this.silentCommandSender = Bukkit.createCommandSender(component -> {});
    }

    public void celebrate(Venue venue, long playerProfit) {
        if (!plugin.isEnabled() || playerProfit <= 0
                || !plugin.getConfig().getBoolean("roof-fireworks.enabled", true)
                || venue.fireworkLaunchPoints().isEmpty()) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long profitPerShot = Math.max(1L, plugin.getConfig().getLong("roof-fireworks.profit-per-shot", 10_000L));
        int shots = (int) Math.min(10L, 1L + ((playerProfit - 1L) / profitPerShot));
        long delay = 0;
        for (int shot = 0; shot < shots; shot++) {
            if (shot > 0) delay += randomInterval(random);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> launchRandom(venue), delay);
        }
    }

    private void launchRandom(Venue venue) {
        List<Location> available = new ArrayList<>();
        for (SavedLocation saved : venue.fireworkLaunchPoints()) {
            Location location = saved.toBukkit();
            if (location == null || location.getWorld() == null) continue;
            if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) continue;
            available.add(location);
        }
        if (available.isEmpty()) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location location = available.get(random.nextInt(available.size())).clone();
        FireworkMeta fireworkMeta = createFireworkMeta(random);
        String pendingTag = "gg_fw_" + UUID.randomUUID().toString().replace("-", "");
        String command = String.format(Locale.ROOT,
                "minecraft:execute in %s run minecraft:summon minecraft:firework_rocket %.3f %.3f %.3f {Tags:[\"%s\"]}",
                location.getWorld().getKey(), location.getX(), location.getY(), location.getZ(), pendingTag);
        if (!Bukkit.dispatchCommand(silentCommandSender, command)) {
            plugin.getLogger().warning("屋根花火のsummonコマンドに失敗しました: venue=" + venue.id());
            return;
        }
        Firework firework = null;
        for (Entity entity : location.getWorld().getNearbyEntities(location, 2, 2, 2)) {
            if (entity instanceof Firework candidate && candidate.getScoreboardTags().contains(pendingTag)) {
                firework = candidate;
                break;
            }
        }
        if (firework == null) {
            plugin.getLogger().warning("summonした屋根花火を取得できませんでした: venue=" + venue.id());
            return;
        }
        firework.removeScoreboardTag(pendingTag);
        firework.addScoreboardTag(DECORATIVE_TAG);
        // 正常動作するsummon個体へ、重ねた大玉効果と曲線飛行だけを追加します。
        firework.setFireworkMeta(fireworkMeta);
        startRandomCurvedFlight(firework);
    }

    private List<Color> themedColors(ThreadLocalRandom random, List<Color> theme) {
        int maximum = Math.max(1, Math.min(3, plugin.getConfig().getInt("roof-fireworks.colors-per-firework", 3)));
        int colorCount = maximum == 1 ? 1 : random.nextInt(1, maximum + 1);
        List<Color> available = new ArrayList<>(theme);
        List<Color> colors = new ArrayList<>(colorCount);
        for (int i = 0; i < colorCount; i++) colors.add(available.remove(random.nextInt(available.size())));
        return colors;
    }

    private FireworkMeta createFireworkMeta(ThreadLocalRandom random) {
        ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET);
        FireworkMeta meta = (FireworkMeta) rocket.getItemMeta();
        meta.clearEffects();
        // 1発の中では全ての爆発を同じ色テーマに統一し、補色同士が濁って見えるのを防ぎます。
        List<Color> theme = COLOR_THEMES.get(random.nextInt(COLOR_THEMES.size()));
        int explosionCount = Math.max(1, Math.min(8, plugin.getConfig().getInt("roof-fireworks.explosions-per-firework", 4)));
        for (int explosion = 0; explosion < explosionCount; explosion++) {
            List<Color> colors = themedColors(random, theme);
            FireworkEffect effect = FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(colors)
                    .withFade(theme.get(random.nextInt(theme.size())))
                    .trail(plugin.getConfig().getBoolean("roof-fireworks.trail", true))
                    .flicker(plugin.getConfig().getBoolean("roof-fireworks.flicker", true))
                    .build();
            meta.addEffect(effect);
        }
        meta.setPower(Math.max(1, Math.min(4, plugin.getConfig().getInt("roof-fireworks.flight-power", 3))));
        return meta;
    }

    private void startRandomCurvedFlight(Firework firework) {
        if (!plugin.getConfig().getBoolean("roof-fireworks.curve.enabled", true)) return;
        double minimumStrength = clamp(plugin.getConfig().getDouble("roof-fireworks.curve.min-strength", 0.14), 0, 1.5);
        double maximumStrength = Math.max(minimumStrength, clamp(plugin.getConfig().getDouble("roof-fireworks.curve.max-strength", 0.42), 0, 1.5));
        double smoothing = clamp(plugin.getConfig().getDouble("roof-fireworks.curve.smoothing", 0.32), 0.05, 1.0);
        double verticalSpeed = clamp(plugin.getConfig().getDouble("roof-fireworks.curve.vertical-speed", 0.68), 0.2, 1.5);
        int minimumTurnTicks = Math.max(1, Math.min(40, plugin.getConfig().getInt("roof-fireworks.curve.min-turn-ticks", 3)));
        int maximumTurnTicks = Math.max(minimumTurnTicks, Math.min(60, plugin.getConfig().getInt("roof-fireworks.curve.max-turn-ticks", 9)));
        new BukkitRunnable() {
            private double heading = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            private double targetHeading = heading;
            private double strength = randomStrength();
            private int untilTurn = 1;

            @Override
            public void run() {
                if (!firework.isValid() || firework.isDetonated()) {
                    cancel();
                    return;
                }
                ThreadLocalRandom random = ThreadLocalRandom.current();
                if (--untilTurn <= 0) {
                    targetHeading = random.nextDouble(0, Math.PI * 2);
                    strength = randomStrength();
                    untilTurn = minimumTurnTicks == maximumTurnTicks
                            ? minimumTurnTicks : random.nextInt(minimumTurnTicks, maximumTurnTicks + 1);
                }
                double difference = Math.atan2(Math.sin(targetHeading - heading), Math.cos(targetHeading - heading));
                heading += difference * smoothing;
                Vector current = firework.getVelocity();
                double desiredX = Math.cos(heading) * strength;
                double desiredZ = Math.sin(heading) * strength;
                double x = current.getX() * (1.0 - smoothing) + desiredX * smoothing;
                double z = current.getZ() * (1.0 - smoothing) + desiredZ * smoothing;
                double desiredY = verticalSpeed + random.nextDouble(-0.08, 0.09);
                double y = Math.max(0.25, current.getY() * 0.65 + desiredY * 0.35);
                firework.setVelocity(new Vector(x, y, z));
            }

            private double randomStrength() {
                return minimumStrength == maximumStrength ? minimumStrength
                        : ThreadLocalRandom.current().nextDouble(minimumStrength, maximumStrength);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int randomInterval(ThreadLocalRandom random) {
        int minimum = Math.max(1, Math.min(100, plugin.getConfig().getInt("roof-fireworks.min-shot-interval-ticks", 4)));
        int maximum = Math.max(minimum, Math.min(100, plugin.getConfig().getInt("roof-fireworks.max-shot-interval-ticks", 12)));
        return minimum == maximum ? minimum : random.nextInt(minimum, maximum + 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDecorativeFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework && firework.getScoreboardTags().contains(DECORATIVE_TAG)) {
            event.setCancelled(true);
        }
    }
}
