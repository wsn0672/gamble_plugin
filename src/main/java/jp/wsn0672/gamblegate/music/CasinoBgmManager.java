package jp.wsn0672.gamblegate.music;

import jp.wsn0672.gamblegate.highlow.NoteBlockMidiPlayer;
import jp.wsn0672.gamblegate.model.Venue;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.ToDoubleFunction;

public final class CasinoBgmManager {
    private final NoteBlockMidiPlayer player;

    public CasinoBgmManager(JavaPlugin plugin, ToDoubleFunction<UUID> listenerVolumeMultiplier) {
        player = new NoteBlockMidiPlayer(plugin, listenerVolumeMultiplier);
    }

    public void enter(Player listener, Venue venue) {
        player.start(key(listener), listener.getLocation(), listener.getUniqueId());
    }

    public void leave(Player listener, Venue venue) {
        player.stop(key(listener));
    }

    public void shutdown() { player.stopAll(); }
    private String key(Player player) { return "casino:" + player.getUniqueId(); }
}
