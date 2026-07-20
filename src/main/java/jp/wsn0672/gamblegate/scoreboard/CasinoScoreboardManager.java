package jp.wsn0672.gamblegate.scoreboard;

import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.config.CurrencyFormatter;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.model.Venue;
import jp.wsn0672.gamblegate.pass.PassManager;
import jp.wsn0672.gamblegate.slot.SlotManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CasinoScoreboardManager implements Listener, Runnable, SlotActivityListener {
    private static final ChatColor[] UNIQUE_SUFFIXES = {
            ChatColor.BLACK, ChatColor.DARK_BLUE, ChatColor.DARK_GREEN, ChatColor.DARK_AQUA,
            ChatColor.DARK_RED, ChatColor.DARK_PURPLE, ChatColor.GOLD, ChatColor.GRAY,
            ChatColor.DARK_GRAY, ChatColor.BLUE, ChatColor.GREEN, ChatColor.AQUA,
            ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.YELLOW, ChatColor.WHITE
    };

    private final GambleGatePlugin plugin;
    private final MessageService messages;
    private final Economy economy;
    private final PassManager passes;
    private final SlotManager slots;
    private final Map<UUID, CasinoSession> sessions = new HashMap<>();

    public CasinoScoreboardManager(GambleGatePlugin plugin, MessageService messages, Economy economy,
                                   PassManager passes, SlotManager slots) {
        this.plugin = plugin;
        this.messages = messages;
        this.economy = economy;
        this.passes = passes;
        this.slots = slots;
    }

    public void enter(Player player, Venue venue) {
        if (!plugin.getConfig().getBoolean("casino-scoreboard.enabled", true)) return;
        CasinoSession current = sessions.get(player.getUniqueId());
        if (current != null) {
            current.venue = venue;
            update(player, current);
            return;
        }
        org.bukkit.scoreboard.ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        Scoreboard board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective("gamblegate", "dummy", messages.text("scoreboard.title"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        CasinoSession session = new CasinoSession(venue, player.getScoreboard(), board, objective, economy.getBalance(player));
        sessions.put(player.getUniqueId(), session);
        player.setScoreboard(board);
        update(player, session);
    }

    public void leave(Player player, Venue venue) {
        CasinoSession session = sessions.get(player.getUniqueId());
        if (session == null || (venue != null && !session.venue.id().equalsIgnoreCase(venue.id()))) return;
        sessions.remove(player.getUniqueId());
        if (player.getScoreboard() == session.board) player.setScoreboard(session.previousBoard);
    }

    public String formattedProfit(Player player, Venue venue) {
        CasinoSession session = sessions.get(player.getUniqueId());
        double profit = session != null && (venue == null || session.venue.id().equalsIgnoreCase(venue.id()))
                ? economy.getBalance(player) - session.startBalance : 0;
        String sign = profit > 0.000001 ? "+" : profit < -0.000001 ? "-" : "±";
        String color = profit > 0.000001 ? "&a" : profit < -0.000001 ? "&c" : "&7";
        return messages.text("scoreboard.profit-value", Map.of(
                "profit", CurrencyFormatter.format(economy, Math.abs(profit)), "sign", sign, "color", color));
    }

    @Override
    public void run() {
        for (var entry : new ArrayList<>(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                sessions.remove(entry.getKey());
                continue;
            }
            update(player, entry.getValue());
        }
    }

    @Override
    public void onGameStarted(Player player, Venue venue, long bet) {
        CasinoSession session = session(player, venue);
        if (session == null) return;
        session.plays++;
        session.state = GameState.SPINNING;
        session.bet = bet;
        session.pot = 0;
        session.chains = 0;
        session.result = null;
        session.resultUntil = 0;
        update(player, session);
    }

    @Override
    public void onGoldRush(Player player, Venue venue, long pot, int chains) {
        CasinoSession session = session(player, venue);
        if (session == null) return;
        session.state = GameState.GOLD_RUSH;
        session.pot = pot;
        session.chains = chains;
        update(player, session);
    }

    @Override
    public void onGameResult(Player player, Venue venue, String result, long payout) {
        CasinoSession session = session(player, venue);
        if (session == null) return;
        session.state = GameState.RESULT;
        session.result = result;
        session.lastPayout = payout;
        long durationTicks = Math.max(20, plugin.getConfig().getLong("casino-scoreboard.result-display-ticks", 100));
        session.resultUntil = System.currentTimeMillis() + durationTicks * 50L;
        update(player, session);
    }

    public void shutdown() {
        for (var entry : new ArrayList<>(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline() && player.getScoreboard() == entry.getValue().board) {
                player.setScoreboard(entry.getValue().previousBoard);
            }
        }
        sessions.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private CasinoSession session(Player player, Venue venue) {
        CasinoSession session = sessions.get(player.getUniqueId());
        return session != null && session.venue.id().equalsIgnoreCase(venue.id()) ? session : null;
    }

    private void update(Player player, CasinoSession session) {
        if (session.state == GameState.RESULT && System.currentTimeMillis() >= session.resultUntil) {
            session.state = GameState.IDLE;
            session.result = null;
        }
        for (String entry : new ArrayList<>(session.board.getEntries())) session.board.resetScores(entry);
        session.objective.setDisplayName(messages.text("scoreboard.title"));
        List<String> lines = lines(player, session);
        int score = lines.size();
        for (int i = 0; i < lines.size() && i < UNIQUE_SUFFIXES.length; i++) {
            session.objective.getScore(lines.get(i) + UNIQUE_SUFFIXES[i]).setScore(score--);
        }
        if (player.getScoreboard() != session.board) player.setScoreboard(session.board);
    }

    private List<String> lines(Player player, CasinoSession session) {
        List<String> lines = new ArrayList<>();
        lines.add(messages.text("scoreboard.blank"));
        double balance = economy.getBalance(player);
        lines.add(messages.text("scoreboard.balance", Map.of("balance", CurrencyFormatter.format(economy, balance))));
        double profit = balance - session.startBalance;
        String sign = profit > 0.000001 ? "+" : profit < -0.000001 ? "-" : "±";
        String color = profit > 0.000001 ? "&a" : profit < -0.000001 ? "&c" : "&7";
        lines.add(messages.text("scoreboard.profit", Map.of(
                "profit", CurrencyFormatter.format(economy, Math.abs(profit)), "sign", sign, "color", color)));
        lines.add(messages.text("scoreboard.plays", Map.of("plays", session.plays)));
        if (passes.hasActivePass(player, session.venue)) {
            if (passes.hasActiveSubscription(player, session.venue)) {
                lines.add(messages.text("scoreboard.pass-auto-renew"));
            } else {
                lines.add(messages.text("scoreboard.pass-expiry", Map.of(
                        "remaining", remaining(passes.activePassExpiresAt(player, session.venue) - System.currentTimeMillis()))));
            }
        }
        long pending = slots.pendingPayout(player.getUniqueId());
        if (pending > 0) lines.add(messages.text("scoreboard.pending", Map.of("pending", CurrencyFormatter.format(economy, pending))));
        if (session.state == GameState.SPINNING) {
            lines.add(messages.text("scoreboard.state", Map.of("state", messages.text("scoreboard.state-spinning"))));
            lines.add(messages.text("scoreboard.bet", Map.of("bet", CurrencyFormatter.format(economy, session.bet))));
        } else if (session.state == GameState.GOLD_RUSH) {
            lines.add(messages.text("scoreboard.state", Map.of("state", messages.text("scoreboard.state-gold-rush"))));
            lines.add(messages.text("scoreboard.pot", Map.of("pot", CurrencyFormatter.format(economy, session.pot))));
            lines.add(messages.text("scoreboard.chains", Map.of("chains", session.chains)));
        } else if (session.state == GameState.RESULT && session.result != null) {
            lines.add(messages.text("scoreboard.result", Map.of("result", session.result)));
            lines.add(messages.text("scoreboard.payout", Map.of("payout", CurrencyFormatter.format(economy, session.lastPayout))));
        }
        String footer = messages.text("scoreboard.footer");
        if (!footer.isBlank()) {
            lines.add(messages.text("scoreboard.blank-footer"));
            lines.add(footer);
        }
        return lines;
    }

    private String remaining(long millis) {
        if (millis <= 0) return messages.text("scoreboard.expired");
        long days = millis / 86_400_000L;
        if (days > 0) return messages.text("scoreboard.days", Map.of("value", days));
        long hours = Math.max(1, millis / 3_600_000L);
        return messages.text("scoreboard.hours", Map.of("value", hours));
    }

    private enum GameState { IDLE, SPINNING, GOLD_RUSH, RESULT }

    private static final class CasinoSession {
        private Venue venue;
        private final Scoreboard previousBoard;
        private final Scoreboard board;
        private final Objective objective;
        private final double startBalance;
        private int plays;
        private GameState state = GameState.IDLE;
        private long bet;
        private long pot;
        private int chains;
        private String result;
        private long lastPayout;
        private long resultUntil;

        private CasinoSession(Venue venue, Scoreboard previousBoard, Scoreboard board, Objective objective, double startBalance) {
            this.venue = venue;
            this.previousBoard = previousBoard;
            this.board = board;
            this.objective = objective;
            this.startBalance = startBalance;
        }
    }
}
