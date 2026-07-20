package jp.wsn0672.gamblegate.highlow;

import dev.geco.gsit.api.GSitAPI;
import dev.geco.gsit.api.event.EntitySitEvent;
import dev.geco.gsit.model.Seat;
import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.account.CasinoAccountManager;
import jp.wsn0672.gamblegate.config.CurrencyFormatter;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.SoundService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.HighLowMachine;
import jp.wsn0672.gamblegate.model.Venue;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.BiConsumer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class HighLowManager implements Listener, Runnable {
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final SoundService sounds;
    private final Economy economy;
    private final CasinoAccountManager accounts;
    private final Random random = new Random();
    private final Map<String, GameSession> sessions = new HashMap<>();
    private final Map<UUID, String> playerSessions = new HashMap<>();
    private final Map<UUID, BetMenu> menus = new HashMap<>();
    private final Map<String, TextDisplay> lingeringDisplays = new HashMap<>();
    private final Map<String, GuideDisplay> guideDisplays = new HashMap<>();
    private final Map<String, TextDisplay> idleDisplays = new HashMap<>();
    private final Set<BukkitTask> tasks = new HashSet<>();
    private final Map<UUID, PendingPayout> pendingPayouts = new HashMap<>();
    private final File pendingFile;
    private BiPredicate<Player, Venue> admissionChecker = (player, venue) -> false;
    private BiConsumer<Venue, Long> houseLossListener = (venue, profit) -> {};
    private BiConsumer<Player, Venue> completionListener = (player, venue) -> {};
    private long ticks;
    private boolean resetting;

    public HighLowManager(GambleGatePlugin plugin, VenueRepository venues, MessageService messages, SoundService sounds,
                          Economy economy, CasinoAccountManager accounts) {
        this.plugin = plugin; this.venues = venues; this.messages = messages; this.sounds = sounds;
        this.economy = economy; this.accounts = accounts;
        pendingFile = new File(plugin.getDataFolder(), "pending-highlow-payouts.yml");
        loadPendingPayouts();
    }

    public void setAdmissionChecker(BiPredicate<Player, Venue> checker) { admissionChecker = checker; }
    public void setHouseLossListener(BiConsumer<Venue, Long> listener) { houseLossListener = listener == null ? (venue, profit) -> {} : listener; }
    public void setCompletionListener(BiConsumer<Player, Venue> listener) { completionListener = listener == null ? (player, venue) -> {} : listener; }
    public boolean isPlaying(Player player, Venue venue) {
        String sessionKey = playerSessions.get(player.getUniqueId());
        GameSession session = sessionKey == null ? null : sessions.get(sessionKey);
        return session != null && session.venue().id().equalsIgnoreCase(venue.id());
    }
    public boolean isPlaying(Venue venue, HighLowMachine machine) {
        return sessions.containsKey(key(venue, machine)) || menus.values().stream().anyMatch(menu -> menu.machine() == machine);
    }

    @Override
    public void run() {
        ticks += 5;
        int retryInterval = Math.max(20, plugin.getConfig().getInt("high-low.pending-payout-retry-ticks", 200));
        if (ticks % retryInterval < 5) retryPendingPayouts();
        for (BetMenu menu : new ArrayList<>(menus.values())) {
            Player player = Bukkit.getPlayer(menu.playerId());
            if (player == null || !player.isOnline()) { menus.remove(menu.playerId()); continue; }
            if (!accounts.isOpen(menu.venue()) || !admissionChecker.test(player, menu.venue()) || !isSeated(player, menu.machine()))
                player.closeInventory();
        }
        for (GameSession session : new ArrayList<>(sessions.values())) {
            Player player = session.player();
            if (!player.isOnline() || !accounts.isOpen(session.venue()) || !admissionChecker.test(player, session.venue()) || !isSeated(player, session.machine())) {
                cashOut(session, player.isOnline() ? "highlow.auto-cashout" : null);
            }
        }
        for (var entry : new ArrayList<>(guideDisplays.entrySet())) {
            GuideDisplay guide = entry.getValue();
            Player player = Bukkit.getPlayer(guide.playerId());
            if (player == null || !player.isOnline() || !isSeated(player, guide.machine()) || sessions.containsKey(entry.getKey())) removeGuide(entry.getKey());
        }
        if (ticks % 20 == 0) refreshIdleDisplays();
    }

    @EventHandler
    public void onSit(EntitySitEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        FoundMachine found = findByChair(event.getSeat().getBlock());
        if (found == null) return;
        runLater(() -> showGuide(player, found.venue(), found.machine()), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        FoundControl found = findByControl(event.getClickedBlock());
        if (found == null) return;
        event.setCancelled(true);
        if (found.control() == Control.CENTER) center(event.getPlayer(), found.venue(), found.machine());
        else choose(event.getPlayer(), found.venue(), found.machine(), found.control() == Control.HIGH ? Choice.HIGH : Choice.LOW);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        FoundMachine found = findByChair(event.getBlock());
        if (found == null) {
            FoundControl control = findByControl(event.getBlock());
            if (control != null) found = new FoundMachine(control.venue(), control.machine());
        }
        if (found != null && isPlaying(found.venue(), found.machine())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "highlow.busy");
            sounds.play(event.getPlayer(), "error");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menus.remove(event.getPlayer().getUniqueId());
        String sessionKey = playerSessions.get(event.getPlayer().getUniqueId());
        GameSession session = sessionKey == null ? null : sessions.get(sessionKey);
        if (session != null) cashOut(session, null);
    }

    private void center(Player player, Venue venue, HighLowMachine machine) {
        GameSession active = sessions.get(key(venue, machine));
        if (active != null) {
            if (!active.player().getUniqueId().equals(player.getUniqueId())) {
                messages.send(player, "highlow.busy"); sounds.play(player, "error"); return;
            }
            if (active.resolving()) { messages.send(player, "highlow.resolving"); return; }
            cashOut(active, "highlow.cashout");
            return;
        }
        openBetMenu(player, venue, machine);
    }

    private void openBetMenu(Player player, Venue venue, HighLowMachine machine) {
        if (!isAutoLinked(venue, machine)) { messages.send(player, "highlow.auto-link-missing"); sounds.play(player, "error"); return; }
        if (!accounts.isOpen(venue)) { messages.send(player, "account.game-closed"); sounds.play(player, "error"); return; }
        if (!admissionChecker.test(player, venue)) { messages.send(player, "highlow.not-admitted"); sounds.play(player, "error"); return; }
        if (!isSeated(player, machine)) { messages.send(player, "highlow.not-sitting"); sounds.play(player, "error"); return; }
        if (playerSessions.containsKey(player.getUniqueId())) { messages.send(player, "highlow.already-playing"); return; }
        boolean occupied = menus.values().stream().anyMatch(menu -> menu.machine() == machine && !menu.playerId().equals(player.getUniqueId()));
        if (occupied) { messages.send(player, "highlow.busy"); sounds.play(player, "error"); return; }
        Inventory inventory = Bukkit.createInventory(null, 27, messages.text("highlow.gui-title"));
        List<Long> bets = bets();
        int[] slots = {11, 13, 15}; Material[] materials = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND};
        for (int i = 0; i < bets.size() && i < slots.length; i++)
            inventory.setItem(slots[i], menuItem(materials[i], messages.text("highlow.bet-name", Map.of("bet", money(bets.get(i))))));
        inventory.setItem(22, menuItem(Material.BARRIER, messages.text("highlow.cancel-name")));
        menus.put(player.getUniqueId(), new BetMenu(player.getUniqueId(), venue, machine, inventory));
        player.openInventory(inventory); sounds.play(player, "gui-open");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        BetMenu menu = menus.get(player.getUniqueId());
        if (menu == null || !menu.inventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        int index = switch (event.getRawSlot()) { case 11 -> 0; case 13 -> 1; case 15 -> 2; default -> -1; };
        if (index >= 0) {
            List<Long> bets = bets();
            if (index < bets.size()) start(player, menu.venue(), menu.machine(), bets.get(index));
        } else if (event.getRawSlot() == 22) player.closeInventory();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        BetMenu menu = menus.get(event.getPlayer().getUniqueId());
        if (menu == null || !menu.inventory().equals(event.getInventory())) return;
        menus.remove(event.getPlayer().getUniqueId());
        Player player = (Player) event.getPlayer();
        if (!resetting && plugin.isEnabled()) runLater(() -> showGuide(player, menu.venue(), menu.machine()), 1L);
    }

    private void start(Player player, Venue venue, HighLowMachine machine, long bet) {
        if (!isAutoLinked(venue, machine) || !accounts.isOpen(venue) || !admissionChecker.test(player, venue) || !isSeated(player, machine)) {
            player.closeInventory(); messages.send(player, "highlow.start-invalid"); sounds.play(player, "error"); return;
        }
        if (sessions.containsKey(key(venue, machine)) || playerSessions.containsKey(player.getUniqueId())) {
            player.closeInventory(); messages.send(player, "highlow.busy"); sounds.play(player, "error"); return;
        }
        if (!economy.has(player, bet)) { messages.send(player, "highlow.insufficient-funds", Map.of("bet", money(bet))); sounds.play(player, "error"); return; }
        EconomyResponse response = economy.withdrawPlayer(player, bet);
        if (!response.transactionSuccess()) { messages.send(player, "highlow.payment-failed"); sounds.play(player, "error"); return; }
        accounts.recordIncome(venue, player, bet, "highlow-bet");

        Card card = randomCard();
        String sessionKey = key(venue, machine);
        removeGuide(sessionKey);
        removeIdleDisplay(sessionKey);
        removeLingeringDisplay(sessionKey);
        TextDisplay display = createDisplay(venue, machine, cardText(card));
        if (display == null) {
            economy.depositPlayer(player, bet);
            accounts.recordExpense(venue, player, bet, "highlow-start-refund");
            messages.send(player, "highlow.display-failed"); sounds.play(player, "error"); return;
        }
        long payoutCap = Math.max(1, plugin.getConfig().getLong("high-low.max-payout", 100_000_000));
        long initialPot = Math.min(payoutCap, bet);
        GameSession session = new GameSession(player, venue, machine, card, bet, initialPot, display);
        menus.remove(player.getUniqueId()); player.closeInventory();
        sessions.put(sessionKey, session); playerSessions.put(player.getUniqueId(), sessionKey);
        messages.send(player, "highlow.started", Map.of("bet", money(bet), "card", card.label(), "pot", money(initialPot)));
        player.sendTitle(messages.text("highlow.start-title"), messages.text("highlow.start-subtitle", Map.of("card", card.label(), "pot", money(initialPot))), 5, 45, 10);
        sounds.play(player, "highlow-start");
    }

    private void choose(Player player, Venue venue, HighLowMachine machine, Choice choice) {
        GameSession session = sessions.get(key(venue, machine));
        if (session == null || !session.player().getUniqueId().equals(player.getUniqueId())) {
            messages.send(player, "highlow.start-first"); sounds.play(player, "error"); return;
        }
        if (session.resolving()) { messages.send(player, "highlow.resolving"); return; }
        if (!isSeated(player, machine)) { cashOut(session, "highlow.auto-cashout"); return; }
        session.resolving(true);
        session.display().setText(ChatColor.GOLD + "◆\n" + ChatColor.YELLOW + "?");
        sounds.play(player, "highlow-reveal");
        Card next = randomCard();
        long delay = Math.max(1, plugin.getConfig().getLong("high-low.reveal-ticks", 16));
        runLater(() -> resolve(session, choice, next), delay);
    }

    private void resolve(GameSession session, Choice choice, Card next) {
        if (sessions.get(key(session.venue(), session.machine())) != session) return;
        session.display().setText(cardText(next));
        int comparison = Integer.compare(next.rank(), session.card().rank());
        if (comparison == 0) {
            session.card(next); session.resolving(false);
            messages.send(session.player(), "highlow.draw", Map.of("card", next.label(), "pot", money(session.pot())));
            session.player().sendTitle(messages.text("highlow.draw-title"), messages.text("highlow.draw-subtitle"), 5, 35, 10);
            sounds.play(session.player(), "highlow-draw");
            return;
        }
        boolean correct = choice == Choice.HIGH ? comparison > 0 : comparison < 0;
        if (!correct) {
            messages.send(session.player(), "highlow.lost", Map.of("card", next.label(), "pot", money(session.pot())));
            session.player().sendTitle(messages.text("highlow.lose-title"), messages.text("highlow.lose-subtitle", Map.of("card", next.label())), 5, 55, 15);
            sounds.play(session.player(), "highlow-lose");
            finish(session, true);
            return;
        }
        int nextStreak = session.wins() + 1;
        session.pot(growPot(session.venue(), session.pot(), session.card().rank(), choice));
        session.card(next); session.wins(nextStreak); session.resolving(false);
        messages.send(session.player(), "highlow.correct", Map.of("card", next.label(), "pot", money(session.pot()), "wins", session.wins()));
        session.player().sendTitle(messages.text("highlow.correct-title"), messages.text("highlow.correct-subtitle", Map.of("pot", money(session.pot()), "wins", session.wins())), 5, 45, 10);
        sounds.play(session.player(), "highlow-win");
    }

    private long growPot(Venue venue, long pot, int currentRank, Choice choice) {
        int favorable = choice == Choice.HIGH ? 13 - currentRank : currentRank - 1;
        if (favorable <= 0) return pot;
        // 同値は引き分けで再抽選されるため、決着時の的中率は favorable / 12。
        // RTP 1.0では、どのカード・どの連勝数で換金しても期待値が変わらない公平配当です。
        double multiplier = effectiveRtp(venue) / (favorable / 12.0);
        long cap = Math.max(1, plugin.getConfig().getLong("high-low.max-payout", 100_000_000));
        double value = pot * multiplier;
        return !Double.isFinite(value) || value >= cap ? cap : Math.max(1, (long) Math.floor(value));
    }

    private double effectiveRtp(Venue venue) {
        double base = Math.max(0.50, Math.min(1.20, plugin.getConfig().getDouble("high-low.rtp", 1.0)));
        if (!plugin.getConfig().getBoolean("high-low.dynamic-odds.enabled", true)) return base;
        CasinoAccountManager.AccountSnapshot account = accounts.snapshot(venue);
        double income = Math.max(0, (double) account.totalIncome());
        double expense = Math.max(0, (double) account.totalExpense());
        double turnover = income + expense;
        if (!Double.isFinite(turnover) || turnover <= 0) return base;
        double minimum = Math.max(0, plugin.getConfig().getDouble("high-low.dynamic-odds.minimum-turnover", 100_000));
        double strength = minimum <= 0 ? 1 : Math.min(1, turnover / minimum);
        double margin = (income - expense) / turnover;
        double fullMargin = Math.max(0.0001, plugin.getConfig().getDouble("high-low.dynamic-odds.full-adjustment-profit-margin", 0.20));
        double direction = Math.max(-1, Math.min(1, margin / fullMargin));
        double maximum = Math.max(0, Math.min(0.20, plugin.getConfig().getDouble("high-low.dynamic-odds.maximum-rtp-adjustment", 0.02)));
        return Math.max(0.50, Math.min(1.20, base + direction * maximum * strength));
    }

    private void cashOut(GameSession session, String messagePath) {
        if (sessions.get(key(session.venue(), session.machine())) != session) return;
        long payout = session.pot();
        EconomyResponse response = depositWithFallback(session.player(), payout);
        if (!response.transactionSuccess()) {
            queuePayout(session.player(), payout);
            if (session.player().isOnline()) messages.send(session.player(), "highlow.payout-queued", Map.of("payout", money(payout)));
        }
        accounts.recordExpense(session.venue(), session.player(), payout, "highlow-payout");
        if (payout > session.bet()) houseLossListener.accept(session.venue(), payout - session.bet());
        if (messagePath != null && session.player().isOnline()) {
            messages.send(session.player(), messagePath, Map.of("payout", money(payout), "wins", session.wins()));
            session.player().sendTitle(messages.text("highlow.cashout-title"), messages.text("highlow.cashout-subtitle", Map.of("payout", money(payout))), 5, 55, 15);
            sounds.play(session.player(), "highlow-cashout");
        }
        finish(session, false);
    }

    private void finish(GameSession session) { finish(session, false); }

    private void finish(GameSession session, boolean linger) {
        String machineKey = key(session.venue(), session.machine());
        sessions.remove(machineKey);
        playerSessions.remove(session.player().getUniqueId());
        completionListener.accept(session.player(), session.venue());
        if (session.display() == null || !session.display().isValid()) return;
        if (!linger) {
            session.display().remove();
            if (!resetting && plugin.isEnabled()) runLater(() -> showGuide(session.player(), session.venue(), session.machine()), 1L);
            return;
        }
        lingeringDisplays.put(machineKey, session.display());
        long delay = Math.max(5, plugin.getConfig().getLong("high-low.result-display-ticks", 30));
        runLater(() -> {
            if (lingeringDisplays.get(machineKey) != session.display()) return;
            removeLingeringDisplay(machineKey);
            showGuide(session.player(), session.venue(), session.machine());
        }, delay);
    }

    private void removeLingeringDisplay(String machineKey) {
        TextDisplay display = lingeringDisplays.remove(machineKey);
        if (display != null && display.isValid()) display.remove();
    }

    private void showGuide(Player player, Venue venue, HighLowMachine machine) {
        String machineKey = key(venue, machine);
        if (!player.isOnline() || sessions.containsKey(machineKey) || !isSeated(player, machine)
                || !admissionChecker.test(player, venue) || !accounts.isOpen(venue)) return;
        if (!isAutoLinked(venue, machine)) { messages.send(player, "highlow.auto-link-missing"); sounds.play(player, "error"); return; }
        removeIdleDisplay(machineKey);
        removeGuide(machineKey);
        double scale = Math.max(0.25, plugin.getConfig().getDouble("high-low.guide-display-scale", 0.8));
        Map<String, Object> betValues = Map.of("bets", formattedBets(), "bet", formattedBets());
        TextDisplay display = createDisplay(venue, machine, messages.text("highlow.desk-guide", betValues), scale);
        if (display == null) return;
        guideDisplays.put(machineKey, new GuideDisplay(player.getUniqueId(), machine, display));
        messages.send(player, "highlow.sit-guide", betValues);
        sounds.play(player, "highlow-ready");
    }

    private void removeGuide(String machineKey) {
        GuideDisplay guide = guideDisplays.remove(machineKey);
        if (guide != null && guide.display() != null && guide.display().isValid()) guide.display().remove();
    }

    private void refreshIdleDisplays() {
        Set<String> currentMachines = new HashSet<>();
        for (Venue venue : venues.all()) for (HighLowMachine machine : venue.highLowMachines().values()) {
            String machineKey = key(venue, machine);
            currentMachines.add(machineKey);
            boolean shouldShow = !sessions.containsKey(machineKey) && !lingeringDisplays.containsKey(machineKey)
                    && !guideDisplays.containsKey(machineKey) && menus.values().stream().noneMatch(menu -> menu.machine() == machine)
                    && isAutoLinked(venue, machine) && hasNearbyPlayer(machine);
            if (!shouldShow) { removeIdleDisplay(machineKey); continue; }
            if (idleDisplays.containsKey(machineKey)) {
                idleDisplays.get(machineKey).setText(idleText());
                continue;
            }
            double scale = Math.max(0.25, plugin.getConfig().getDouble("high-low.idle-display-scale", 1.0));
            TextDisplay display = createDisplay(venue, machine, idleText(), scale);
            if (display != null) idleDisplays.put(machineKey, display);
        }
        for (String machineKey : new ArrayList<>(idleDisplays.keySet())) if (!currentMachines.contains(machineKey)) removeIdleDisplay(machineKey);
    }

    private boolean hasNearbyPlayer(HighLowMachine machine) {
        if (machine.chair() == null) return false;
        World world = Bukkit.getWorld(machine.chair().world());
        if (world == null) return false;
        Location center = new Location(world, machine.chair().x() + 0.5, machine.chair().y() + 0.5, machine.chair().z() + 0.5);
        double range = Math.max(1, plugin.getConfig().getDouble("high-low.proximity-distance", 30));
        double rangeSquared = range * range;
        return world.getPlayers().stream().anyMatch(player -> player.getLocation().distanceSquared(center) <= rangeSquared);
    }

    private void removeIdleDisplay(String machineKey) {
        TextDisplay display = idleDisplays.remove(machineKey);
        if (display != null && display.isValid()) display.remove();
    }


    public boolean isAutoLinked(Venue venue, HighLowMachine machine) {
        return machine.complete() && nearest(venue, machine, Material.WARPED_BUTTON) != null
                && nearest(venue, machine, Material.POLISHED_BLACKSTONE_BUTTON) != null
                && nearest(venue, machine, Material.CRIMSON_BUTTON) != null;
    }

    public void sendLinkStatus(CommandSender sender, Venue venue, HighLowMachine machine) {
        Block low = nearest(venue, machine, Material.WARPED_BUTTON);
        Block center = nearest(venue, machine, Material.POLISHED_BLACKSTONE_BUTTON);
        Block high = nearest(venue, machine, Material.CRIMSON_BUTTON);
        String path = low != null && center != null && high != null ? "highlow-admin.link-success" : "highlow-admin.link-missing";
        messages.send(sender, path, Map.of("low", location(low), "center", location(center), "high", location(high)));
    }

    private FoundControl findByControl(Block block) {
        Control control = Control.from(block.getType());
        if (control == null) return null;
        BlockPosition clicked = position(block);
        for (Venue venue : venues.all()) for (HighLowMachine machine : venue.highLowMachines().values()) {
            Block linked = nearest(venue, machine, block.getType());
            if (linked != null && clicked.equals(position(linked))) return new FoundControl(venue, machine, control);
        }
        return null;
    }

    private FoundMachine findByChair(Block block) {
        BlockPosition position = position(block);
        for (Venue venue : venues.all()) for (HighLowMachine machine : venue.highLowMachines().values())
            if (position.equals(machine.chair())) return new FoundMachine(venue, machine);
        return null;
    }

    private Block nearest(Venue venue, HighLowMachine machine, Material material) {
        BlockPosition chair = machine.chair();
        if (chair == null) return null;
        World world = Bukkit.getWorld(chair.world());
        if (world == null) return null;
        double radius = Math.max(1, plugin.getConfig().getDouble("high-low.auto-link-radius", 3));
        int range = (int) Math.ceil(radius); double bestDistance = Double.MAX_VALUE; Block best = null;
        for (int x = chair.x() - range; x <= chair.x() + range; x++) for (int y = chair.y() - range; y <= chair.y() + range; y++) for (int z = chair.z() - range; z <= chair.z() + range; z++) {
            double distance = distanceSquared(chair, x, y, z);
            if (distance > radius * radius || distance > bestDistance || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != material || !venue.contains(block.getLocation()) || !ownedBy(venue, machine, block)) continue;
            if (best == null || distance < bestDistance) { best = block; bestDistance = distance; }
        }
        return best;
    }

    private boolean ownedBy(Venue venue, HighLowMachine owner, Block block) {
        double ownerDistance = distanceSquared(owner.chair(), block.getX(), block.getY(), block.getZ());
        for (HighLowMachine other : venue.highLowMachines().values()) {
            if (other == owner || other.chair() == null || !other.chair().world().equals(block.getWorld().getName())) continue;
            double otherDistance = distanceSquared(other.chair(), block.getX(), block.getY(), block.getZ());
            if (otherDistance < ownerDistance || (otherDistance == ownerDistance && other.id().compareToIgnoreCase(owner.id()) < 0)) return false;
        }
        return true;
    }

    private TextDisplay createDisplay(Venue venue, HighLowMachine machine, String text) {
        return createDisplay(venue, machine, text, Math.max(0.25, plugin.getConfig().getDouble("high-low.card-display-scale", 1.5)));
    }

    private TextDisplay createDisplay(Venue venue, HighLowMachine machine, String text, double configuredScale) {
        Block center = nearest(venue, machine, Material.POLISHED_BLACKSTONE_BUTTON);
        if (center == null || machine.chair() == null) return null;
        BlockPosition chair = machine.chair();
        int deltaX = center.getX() - chair.x(), deltaZ = center.getZ() - chair.z();
        int directionX = 0, directionZ = 0;
        if (Math.abs(deltaX) > Math.abs(deltaZ)) directionX = deltaX >= 0 ? 1 : -1;
        else directionZ = deltaZ >= 0 ? 1 : -1;
        double yOffset = plugin.getConfig().getDouble("high-low.card-display-y-offset", 0.22);
        Location location = center.getLocation().add(0.5 + directionX, yOffset, 0.5 + directionZ);
        TextDisplay display = center.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setText(text);
            entity.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setShadowed(true);
            entity.setSeeThrough(false);
            entity.setLineWidth(80);
            entity.setPersistent(false);
            entity.setViewRange((float) Math.max(1.0, plugin.getConfig().getDouble("high-low.display-view-range", 2.0)));
            float scale = (float) configuredScale;
            var transformation = entity.getTransformation();
            transformation.getScale().set(scale, scale, scale);
            entity.setTransformation(transformation);
        });
        double toChairX = chair.x() + 0.5 - location.getX();
        double toChairZ = chair.z() + 0.5 - location.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-toChairX, toChairZ));
        float pitch = (float) plugin.getConfig().getDouble("high-low.card-display-pitch", -10);
        display.setRotation(yaw, pitch);
        return display;
    }

    private String idleText() {
        List<String> variants = new ArrayList<>();
        Map<String, Object> values = Map.of("bets", formattedBets(), "bet", formattedBets());
        variants.add(messages.text("highlow.idle-display", values));
        variants.addAll(messages.lines("highlow.idle-displays", values));
        long interval = Math.max(20, plugin.getConfig().getLong("high-low.idle-display-cycle-ticks", 60));
        return variants.get((int) ((ticks / interval) % variants.size()));
    }

    private boolean isSeated(Player player, HighLowMachine machine) {
        Seat seat = GSitAPI.getSeatByEntity(player);
        return seat != null && position(seat.getBlock()).equals(machine.chair());
    }

    private Card randomCard() { return new Card(random.nextInt(1, 14), Suit.values()[random.nextInt(Suit.values().length)]); }
    private String cardText(Card card) { return card.suit().color + "§l" + card.suit().symbol + "\n§f§l" + rank(card.rank()); }
    private String rank(int rank) { return switch (rank) { case 1 -> "A"; case 11 -> "J"; case 12 -> "Q"; case 13 -> "K"; default -> Integer.toString(rank); }; }
    private String money(long amount) { return CurrencyFormatter.format(economy, amount); }
    private List<Long> bets() {
        List<Long> configured = plugin.getConfig().getLongList("high-low.bets").stream().filter(value -> value > 0).limit(3).toList();
        return configured.size() == 3 ? configured : List.of(100L, 1_000L, 10_000L);
    }
    private String formattedBets() { return bets().stream().map(this::money).collect(java.util.stream.Collectors.joining(" / ")); }
    private ItemStack menuItem(Material material, String name) {
        ItemStack stack = new ItemStack(material); ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name); stack.setItemMeta(meta); return stack;
    }
    private String key(Venue venue, HighLowMachine machine) { return venue.id().toLowerCase(Locale.ROOT) + ":" + machine.id().toLowerCase(Locale.ROOT); }
    private BlockPosition position(Block block) { return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()); }
    private double distanceSquared(BlockPosition from, int x, int y, int z) { double dx = from.x() - x, dy = from.y() - y, dz = from.z() - z; return dx * dx + dy * dy + dz * dz; }
    private String location(Block block) { return block == null ? "未検出" : block.getWorld().getName() + " " + block.getX() + " " + block.getY() + " " + block.getZ(); }

    private void runLater(Runnable action, long delay) {
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> { try { action.run(); } finally { tasks.remove(handle[0]); } }, delay);
        tasks.add(handle[0]);
    }

    private EconomyResponse depositWithFallback(Player player, long amount) {
        if (!economy.hasAccount(player)) economy.createPlayerAccount(player);
        double before = economy.getBalance(player);
        EconomyResponse response = economy.depositPlayer(player, amount);
        double tolerance = Math.max(0.000001, amount * 0.000000001);
        if (response.transactionSuccess() || economy.getBalance(player) - before >= amount - tolerance) return response.transactionSuccess()
                ? response : new EconomyResponse(amount, economy.getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
        return response;
    }

    private void queuePayout(Player player, long amount) {
        PendingPayout current = pendingPayouts.get(player.getUniqueId());
        long total;
        try { total = Math.addExact(current == null ? 0 : current.amount(), amount); }
        catch (ArithmeticException exception) { total = Long.MAX_VALUE; }
        pendingPayouts.put(player.getUniqueId(), new PendingPayout(player.getName(), total));
        savePendingPayouts();
    }

    private void retryPendingPayouts() {
        for (var entry : new ArrayList<>(pendingPayouts.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            EconomyResponse response = depositWithFallback(player, entry.getValue().amount());
            if (!response.transactionSuccess()) continue;
            long payout = entry.getValue().amount(); pendingPayouts.remove(entry.getKey()); savePendingPayouts();
            messages.send(player, "highlow.pending-payout-paid", Map.of("payout", money(payout)));
        }
    }

    private void loadPendingPayouts() {
        pendingPayouts.clear(); if (!pendingFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(pendingFile);
        ConfigurationSection players = data.getConfigurationSection("players"); if (players == null) return;
        for (String raw : players.getKeys(false)) try {
            UUID uuid = UUID.fromString(raw); long amount = players.getLong(raw + ".amount");
            if (amount > 0) pendingPayouts.put(uuid, new PendingPayout(players.getString(raw + ".name", raw), amount));
        } catch (IllegalArgumentException ignored) {}
    }

    private void savePendingPayouts() {
        YamlConfiguration data = new YamlConfiguration();
        for (var entry : pendingPayouts.entrySet()) { String path = "players." + entry.getKey(); data.set(path + ".name", entry.getValue().name()); data.set(path + ".amount", entry.getValue().amount()); }
        File temporary = new File(plugin.getDataFolder(), "pending-highlow-payouts.yml.tmp");
        try {
            data.save(temporary);
            try { Files.move(temporary.toPath(), pendingFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary.toPath(), pendingFile.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) { plugin.getLogger().severe("ハイ＆ロー保留払戻しの保存に失敗しました: " + exception.getMessage()); }
    }

    public void resetRuntime() {
        resetting = true;
        tasks.forEach(BukkitTask::cancel); tasks.clear();
        for (GameSession session : new ArrayList<>(sessions.values())) cashOut(session, null);
        sessions.clear(); playerSessions.clear();
        for (TextDisplay display : lingeringDisplays.values()) if (display != null && display.isValid()) display.remove();
        lingeringDisplays.clear();
        for (GuideDisplay guide : guideDisplays.values()) if (guide.display() != null && guide.display().isValid()) guide.display().remove();
        guideDisplays.clear();
        for (TextDisplay display : idleDisplays.values()) if (display != null && display.isValid()) display.remove();
        idleDisplays.clear();
        for (BetMenu menu : new ArrayList<>(menus.values())) {
            Player player = Bukkit.getPlayer(menu.playerId()); if (player != null && player.isOnline()) player.closeInventory();
        }
        menus.clear();
        resetting = false;
    }

    private enum Choice { HIGH, LOW }
    private enum Control {
        LOW, CENTER, HIGH;
        static Control from(Material material) { return switch (material) { case WARPED_BUTTON -> LOW; case POLISHED_BLACKSTONE_BUTTON -> CENTER; case CRIMSON_BUTTON -> HIGH; default -> null; }; }
    }
    private enum Suit {
        HEART("♥", "§c"), DIAMOND("♦", "§c"), SPADE("♠", "§f"), CLUB("♣", "§f");
        private final String symbol; private final String color;
        Suit(String symbol, String color) { this.symbol = symbol; this.color = color; }
    }
    private record Card(int rank, Suit suit) { String label() { return suit.symbol + switch (rank) { case 1 -> "A"; case 11 -> "J"; case 12 -> "Q"; case 13 -> "K"; default -> rank; }; } }
    private record FoundControl(Venue venue, HighLowMachine machine, Control control) {}
    private record FoundMachine(Venue venue, HighLowMachine machine) {}
    private record PendingPayout(String name, long amount) {}
    private record GuideDisplay(UUID playerId, HighLowMachine machine, TextDisplay display) {}
    private record BetMenu(UUID playerId, Venue venue, HighLowMachine machine, Inventory inventory) {}
    private static final class GameSession {
        private final Player player; private final Venue venue; private final HighLowMachine machine; private final TextDisplay display;
        private final long bet; private Card card; private long pot; private int wins; private boolean resolving;
        private GameSession(Player player, Venue venue, HighLowMachine machine, Card card, long bet, long pot, TextDisplay display) { this.player = player; this.venue = venue; this.machine = machine; this.card = card; this.bet = bet; this.pot = pot; this.display = display; }
        Player player() { return player; } Venue venue() { return venue; } HighLowMachine machine() { return machine; } TextDisplay display() { return display; }
        long bet() { return bet; }
        Card card() { return card; } void card(Card value) { card = value; } long pot() { return pot; } void pot(long value) { pot = value; }
        int wins() { return wins; } void wins(int value) { wins = value; } boolean resolving() { return resolving; } void resolving(boolean value) { resolving = value; }
    }
}
