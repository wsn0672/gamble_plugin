package jp.wsn0672.gamblegate.command;

import jp.wsn0672.gamblegate.account.CasinoAccountManager;
import jp.wsn0672.gamblegate.config.CurrencyFormatter;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.Venue;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CasinoAccountCommand implements CommandExecutor, TabCompleter {
    private final VenueRepository venues;
    private final CasinoAccountManager accounts;
    private final MessageService messages;
    private final Economy economy;

    public CasinoAccountCommand(VenueRepository venues, CasinoAccountManager accounts, MessageService messages, Economy economy) {
        this.venues = venues; this.accounts = accounts; this.messages = messages; this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gamblegate.accountadmin")) { messages.send(sender, "errors.no-permission"); return true; }
        if (args.length == 0) { help(sender); return true; }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "total", "balance" -> summary(sender, args);
            case "today" -> today(sender, args);
            case "history" -> history(sender, args);
            case "player" -> player(sender, args);
            case "playerhistory" -> playerHistory(sender, args);
            case "reset" -> reset(sender, args);
            default -> { help(sender); yield true; }
        };
    }

    private boolean summary(CommandSender sender, String[] args) {
        Venue venue = optionalVenue(sender, args, 1);
        if (args.length >= 2 && venue == null) return true;
        CasinoAccountManager.AccountSnapshot account = venue == null ? accounts.snapshotAll() : accounts.snapshot(venue);
        messages.send(sender, "account.summary-v2", values(venue == null ? messages.text("account.all-venues") : venue.id(),
                account.totalIncome(), account.totalExpense(), account.netIncome()));
        messages.send(sender, "account.today-v2", values("", account.todayIncome(), account.todayExpense(), account.todayNetIncome()));
        return true;
    }

    private boolean today(CommandSender sender, String[] args) {
        Venue venue = optionalVenue(sender, args, 1);
        if (args.length >= 2 && venue == null) return true;
        CasinoAccountManager.AccountSnapshot account = venue == null ? accounts.snapshotAll() : accounts.snapshot(venue);
        messages.send(sender, "account.today-header", Map.of("scope", venue == null ? messages.text("account.all-venues") : venue.id()));
        messages.send(sender, "account.today-v2", values("", account.todayIncome(), account.todayExpense(), account.todayNetIncome()));
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (args.length < 2) { help(sender); return true; }
        Venue venue = requiredVenue(sender, args[1]);
        if (venue == null) return true;
        int days = days(sender, args, 2);
        if (days < 0) return true;
        messages.send(sender, "account.history-header-v2", Map.of("venue", venue.id(), "days", days));
        for (CasinoAccountManager.DailySnapshot day : accounts.history(venue, days)) sendDay(sender, day, false);
        return true;
    }

    private boolean player(CommandSender sender, String[] args) {
        if (args.length < 2) { help(sender); return true; }
        Venue venue = optionalVenue(sender, args, 2);
        if (args.length >= 3 && venue == null) return true;
        CasinoAccountManager.PlayerSnapshot player = accounts.playerSnapshot(args[1], venue);
        if (player == null) { messages.send(sender, "account.player-not-found", Map.of("player", args[1])); return true; }
        String scope = venue == null ? messages.text("account.all-venues") : venue.id();
        messages.send(sender, "account.player-summary", Map.of(
                "player", player.playerName(), "scope", scope,
                "taken", money(player.totalIncome()), "given", money(player.totalExpense()),
                "net", signed(player.netIncome())));
        messages.send(sender, "account.player-today", Map.of(
                "taken", money(player.todayIncome()), "given", money(player.todayExpense()),
                "net", signed(player.todayNetIncome())));
        return true;
    }

    private boolean playerHistory(CommandSender sender, String[] args) {
        if (args.length < 3) { help(sender); return true; }
        Venue venue = requiredVenue(sender, args[2]);
        if (venue == null) return true;
        CasinoAccountManager.PlayerSnapshot player = accounts.playerSnapshot(args[1], venue);
        if (player == null) { messages.send(sender, "account.player-not-found", Map.of("player", args[1])); return true; }
        int days = days(sender, args, 3);
        if (days < 0) return true;
        messages.send(sender, "account.player-history-header", Map.of("player", player.playerName(), "venue", venue.id(), "days", days));
        for (CasinoAccountManager.DailySnapshot day : accounts.playerHistory(args[1], venue, days)) sendDay(sender, day, true);
        return true;
    }

    private boolean reset(CommandSender sender, String[] args) {
        if (args.length < 2) { help(sender); return true; }
        Venue venue = requiredVenue(sender, args[1]);
        if (venue == null) return true;
        accounts.reset(venue);
        messages.send(sender, "account.reset-v2", Map.of("venue", venue.id()));
        return true;
    }

    private void sendDay(CommandSender sender, CasinoAccountManager.DailySnapshot day, boolean playerView) {
        long net = playerView ? day.playerNetIncome() : day.netIncome();
        messages.send(sender, playerView ? "account.player-history-line" : "account.history-line-v2", Map.of(
                "date", day.date(), "taken", money(day.income()), "given", money(day.expense()), "net", signed(net)));
    }

    private Map<String, Object> values(String scope, long taken, long given, long net) {
        return Map.of("scope", scope, "taken", money(taken), "given", money(given), "net", signed(net));
    }

    private Venue optionalVenue(CommandSender sender, String[] args, int index) {
        return args.length <= index ? null : requiredVenue(sender, args[index]);
    }

    private Venue requiredVenue(CommandSender sender, String id) {
        Venue venue = venues.get(id);
        if (venue == null) messages.send(sender, "errors.venue-not-found", Map.of("venue", id));
        return venue;
    }

    private int days(CommandSender sender, String[] args, int index) {
        int days = 7;
        if (args.length > index) {
            try { days = Integer.parseInt(args[index]); }
            catch (NumberFormatException exception) { messages.send(sender, "account.invalid-days"); return -1; }
        }
        if (days < 1 || days > 365) { messages.send(sender, "account.invalid-days"); return -1; }
        return days;
    }

    private String money(long amount) { return CurrencyFormatter.format(economy, amount); }
    private String signed(long amount) {
        if (amount > 0) return "§a+" + money(amount);
        if (amount < 0) return "§c-" + money(amount == Long.MIN_VALUE ? Long.MAX_VALUE : -amount);
        return "§7±" + money(0);
    }
    private void help(CommandSender sender) { messages.lines("account.usage-v2", Map.of()).forEach(sender::sendMessage); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) choices.addAll(List.of("total", "today", "history", "player", "playerhistory", "reset"));
        else if (args.length == 2 && List.of("total", "today", "history", "reset").contains(args[0].toLowerCase(Locale.ROOT)))
            venues.all().forEach(venue -> choices.add(venue.id()));
        else if (args.length == 3 && args[0].equalsIgnoreCase("player")) venues.all().forEach(venue -> choices.add(venue.id()));
        else if (args.length == 3 && args[0].equalsIgnoreCase("playerhistory")) venues.all().forEach(venue -> choices.add(venue.id()));
        else if ((args.length == 3 && args[0].equalsIgnoreCase("history"))
                || (args.length == 4 && args[0].equalsIgnoreCase("playerhistory"))) choices.addAll(List.of("1", "7", "30"));
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
