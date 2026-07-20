package jp.wsn0672.gamblegate.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jp.wsn0672.gamblegate.model.Venue;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 会場がプレイヤーから受け取った額と、プレイヤーへ渡した額を記録します。
 * 実際の支払い可否を決める口座ではなく、純粋な統計台帳です。
 */
public final class CasinoAccountManager {
    private final JavaPlugin plugin;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, AccountState> accounts = new LinkedHashMap<>();

    public CasinoAccountManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("casino-accounts.json");
        load();
    }

    public void ensureVenues(Collection<Venue> venues) {
        boolean changed = false;
        for (Venue venue : venues) {
            String key = key(venue.id());
            if (!accounts.containsKey(key)) {
                accounts.put(key, newState());
                changed = true;
            }
        }
        if (changed) save();
    }

    /** 互換用。統計額にかかわらずカジノは常に営業可能です。 */
    public boolean isOpen(Venue venue) { return true; }

    public AccountSnapshot snapshot(Venue venue) { return snapshot(account(venue.id())); }

    public AccountSnapshot snapshotAll() {
        long received = 0, paid = 0, todayReceived = 0, todayPaid = 0;
        for (AccountState state : accounts.values()) {
            DailyState today = day(state);
            received = add(received, state.totalIncome);
            paid = add(paid, state.totalExpense);
            todayReceived = add(todayReceived, today.income);
            todayPaid = add(todayPaid, today.expense);
        }
        return new AccountSnapshot(received, paid, todayReceived, todayPaid);
    }

    public List<DailySnapshot> history(Venue venue, int days) {
        AccountState state = account(venue.id());
        day(state);
        int limit = Math.max(1, Math.min(days, 365));
        return state.daily.entrySet().stream()
                .sorted(Map.Entry.<String, DailyState>comparingByKey(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> new DailySnapshot(entry.getKey(), entry.getValue().income, entry.getValue().expense))
                .toList();
    }

    public PlayerSnapshot playerSnapshot(String playerQuery, Venue venue) {
        String playerId = findPlayerId(playerQuery);
        if (playerId == null) return null;
        long received = 0, paid = 0, todayReceived = 0, todayPaid = 0;
        String name = playerQuery;
        boolean found = false;
        Collection<AccountState> states = venue == null ? accounts.values() : List.of(account(venue.id()));
        for (AccountState state : states) {
            PlayerState player = state.players == null ? null : state.players.get(playerId);
            if (player == null) continue;
            found = true;
            if (player.lastKnownName != null && !player.lastKnownName.isBlank()) name = player.lastKnownName;
            DailyState today = playerDay(player);
            received = add(received, player.totalIncome);
            paid = add(paid, player.totalExpense);
            todayReceived = add(todayReceived, today.income);
            todayPaid = add(todayPaid, today.expense);
        }
        if (!found) return null;
        return new PlayerSnapshot(UUID.fromString(playerId), name, received, paid, todayReceived, todayPaid);
    }

    public List<DailySnapshot> playerHistory(String playerQuery, Venue venue, int days) {
        String playerId = findPlayerId(playerQuery);
        if (playerId == null) return List.of();
        PlayerState player = account(venue.id()).players.get(playerId);
        if (player == null) return List.of();
        playerDay(player);
        int limit = Math.max(1, Math.min(days, 365));
        return player.daily.entrySet().stream()
                .sorted(Map.Entry.<String, DailyState>comparingByKey(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> new DailySnapshot(entry.getKey(), entry.getValue().income, entry.getValue().expense))
                .toList();
    }

    public void recordIncome(Venue venue, Player player, long amount, String category) {
        record(venue, player.getUniqueId(), player.getName(), Math.max(0, amount), true, category);
    }

    public void recordExpense(Venue venue, Player player, long amount, String category) {
        record(venue, player.getUniqueId(), player.getName(), Math.max(0, amount), false, category);
    }

    public AccountSnapshot reset(Venue venue) {
        accounts.put(key(venue.id()), newState());
        save();
        return snapshot(venue);
    }

    private void record(Venue venue, UUID playerId, String playerName, long amount, boolean income, String category) {
        if (amount <= 0) return;
        AccountState state = account(venue.id());
        DailyState today = day(state);
        if (income) addIncome(state, today, amount, category);
        else addExpense(state, today, amount, category);

        if (state.players == null) state.players = new LinkedHashMap<>();
        PlayerState player = state.players.computeIfAbsent(playerId.toString(), ignored -> new PlayerState());
        player.lastKnownName = playerName;
        DailyState playerToday = playerDay(player);
        if (income) addIncome(player, playerToday, amount, category);
        else addExpense(player, playerToday, amount, category);
        prune(state.daily);
        prune(player.daily);
        save();
    }

    private void addIncome(Totals totals, DailyState day, long amount, String category) {
        totals.totalIncome = add(totals.totalIncome, amount);
        day.income = add(day.income, amount);
        day.incomeByCategory.merge(category, amount, CasinoAccountManager::add);
    }

    private void addExpense(Totals totals, DailyState day, long amount, String category) {
        totals.totalExpense = add(totals.totalExpense, amount);
        day.expense = add(day.expense, amount);
        day.expenseByCategory.merge(category, amount, CasinoAccountManager::add);
    }

    private AccountSnapshot snapshot(AccountState state) {
        DailyState today = day(state);
        return new AccountSnapshot(state.totalIncome, state.totalExpense, today.income, today.expense);
    }

    private AccountState account(String venueId) {
        String key = key(venueId);
        AccountState state = accounts.get(key);
        if (state != null) {
            normalize(state);
            return state;
        }
        state = newState();
        accounts.put(key, state);
        save();
        return state;
    }

    private AccountState newState() {
        AccountState state = new AccountState();
        day(state);
        return state;
    }

    private DailyState day(AccountState state) {
        if (state.daily == null) state.daily = new LinkedHashMap<>();
        return state.daily.computeIfAbsent(today(), ignored -> new DailyState());
    }

    private DailyState playerDay(PlayerState player) {
        if (player.daily == null) player.daily = new LinkedHashMap<>();
        return player.daily.computeIfAbsent(today(), ignored -> new DailyState());
    }

    private void normalize(AccountState state) {
        if (state.daily == null) state.daily = new LinkedHashMap<>();
        if (state.players == null) state.players = new LinkedHashMap<>();
        for (DailyState daily : state.daily.values()) normalize(daily);
        for (PlayerState player : state.players.values()) {
            if (player.daily == null) player.daily = new LinkedHashMap<>();
            for (DailyState daily : player.daily.values()) normalize(daily);
        }
    }

    private void normalize(DailyState daily) {
        if (daily.incomeByCategory == null) daily.incomeByCategory = new LinkedHashMap<>();
        if (daily.expenseByCategory == null) daily.expenseByCategory = new LinkedHashMap<>();
    }

    private String findPlayerId(String query) {
        try {
            UUID uuid = UUID.fromString(query);
            String id = uuid.toString();
            for (AccountState state : accounts.values()) if (state.players != null && state.players.containsKey(id)) return id;
        } catch (IllegalArgumentException ignored) {}
        for (AccountState state : accounts.values()) {
            if (state.players == null) continue;
            for (var entry : state.players.entrySet()) {
                String name = entry.getValue().lastKnownName;
                if (name != null && name.equalsIgnoreCase(query)) return entry.getKey();
            }
        }
        return null;
    }

    private String today() { return LocalDate.now(ZoneId.systemDefault()).toString(); }

    private void prune(Map<String, DailyState> daily) {
        int retention = Math.max(1, plugin.getConfig().getInt("casino-account.history-retention-days", 365));
        if (daily.size() <= retention) return;
        List<String> dates = new ArrayList<>(daily.keySet());
        dates.sort(String::compareTo);
        for (int i = 0; i < dates.size() - retention; i++) daily.remove(dates.get(i));
    }

    private void load() {
        accounts.clear();
        if (!Files.exists(file)) return;
        boolean migrate = false;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Store store = gson.fromJson(reader, Store.class);
            if (store == null || store.accounts == null) return;
            migrate = store.version < 2;
            for (var entry : store.accounts.entrySet()) {
                AccountState state = entry.getValue();
                if (state == null) continue;
                normalize(state);
                accounts.put(key(entry.getKey()), state);
            }
        } catch (Exception exception) {
            plugin.getLogger().severe("casino-accounts.json の読み込みに失敗しました: " + exception.getMessage());
        }
        if (migrate) save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(new Store(accounts)), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("casino-accounts.json の保存に失敗しました: " + exception.getMessage());
        }
    }

    private String key(String venueId) { return venueId.toLowerCase(Locale.ROOT); }
    private static long add(long first, long second) {
        try { return Math.addExact(first, second); }
        catch (ArithmeticException exception) { return Long.MAX_VALUE; }
    }

    public record AccountSnapshot(long totalIncome, long totalExpense, long todayIncome, long todayExpense) {
        public long netIncome() { return totalIncome - totalExpense; }
        public long todayNetIncome() { return todayIncome - todayExpense; }
    }
    public record PlayerSnapshot(UUID playerId, String playerName, long totalIncome, long totalExpense,
                                 long todayIncome, long todayExpense) {
        public long netIncome() { return totalExpense - totalIncome; }
        public long todayNetIncome() { return todayExpense - todayIncome; }
    }
    public record DailySnapshot(String date, long income, long expense) {
        public long netIncome() { return income - expense; }
        public long playerNetIncome() { return expense - income; }
    }

    private static class Totals {
        long totalIncome;
        long totalExpense;
    }
    private static final class Store {
        private int version = 2;
        private Map<String, AccountState> accounts = new LinkedHashMap<>();
        private Store() {}
        private Store(Map<String, AccountState> accounts) { this.accounts = accounts; }
    }
    private static final class AccountState extends Totals {
        private Map<String, DailyState> daily = new LinkedHashMap<>();
        private Map<String, PlayerState> players = new LinkedHashMap<>();
    }
    private static final class PlayerState extends Totals {
        private String lastKnownName;
        private Map<String, DailyState> daily = new LinkedHashMap<>();
    }
    private static final class DailyState {
        private long income;
        private long expense;
        private Map<String, Long> incomeByCategory = new LinkedHashMap<>();
        private Map<String, Long> expenseByCategory = new LinkedHashMap<>();
    }
}
