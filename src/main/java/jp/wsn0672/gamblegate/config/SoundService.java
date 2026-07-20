package jp.wsn0672.gamblegate.config;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;

public final class SoundService {
    private final JavaPlugin plugin;
    private final Set<String> warnedSounds = new HashSet<>();

    public SoundService(JavaPlugin plugin) { this.plugin = plugin; }

    public void play(Player player, String key) {
        String path = "sounds." + key;
        String name = plugin.getConfig().getString(path + ".name", "");
        if (name == null || name.isBlank()) return;
        float volume = (float) plugin.getConfig().getDouble(path + ".volume", 1.0);
        volume *= (float) Math.max(0, plugin.getConfig().getDouble("sound-volume-multiplier", 1.4));
        float pitch = (float) plugin.getConfig().getDouble(path + ".pitch", 1.0);
        NamespacedKey namespacedKey = NamespacedKey.fromString(name);
        Sound sound = namespacedKey == null ? null : Registry.SOUND_EVENT.get(namespacedKey);
        if (sound == null) {
            if (warnedSounds.add(name)) plugin.getLogger().warning("無効な効果音IDをスキップしました: " + name + " (" + path + ")");
            return;
        }
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, Math.max(0, volume), Math.max(0.01f, Math.min(2, pitch)));
    }
}
