package jp.wsn0672.gamblegate.scoreboard;

import jp.wsn0672.gamblegate.model.Venue;
import org.bukkit.entity.Player;

public interface SlotActivityListener {
    SlotActivityListener NONE = new SlotActivityListener() {};

    default void onGameStarted(Player player, Venue venue, long bet) {}
    default void onGoldRush(Player player, Venue venue, long pot, int chains) {}
    default void onGameResult(Player player, Venue venue, String result, long payout) {}
}
