package jp.wsn0672.gamblegate.slot;

import dev.geco.gsit.api.GSitAPI;
import dev.geco.gsit.api.event.EntitySitEvent;
import dev.geco.gsit.model.Seat;
import jp.wsn0672.gamblegate.account.CasinoAccountManager;
import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.config.CurrencyFormatter;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.SoundService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.SlotMachine;
import jp.wsn0672.gamblegate.model.Venue;
import jp.wsn0672.gamblegate.scoreboard.SlotActivityListener;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Shelf;
import org.bukkit.block.data.Powerable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
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
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

public final class SlotManager implements Listener, Runnable {
    private static final Symbol[] SYMBOLS = Symbol.values();
    private static final NormalOutcome[] NORMAL_OUTCOMES = NormalOutcome.values();
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final SoundService sounds;
    private final Economy economy;
    private final CasinoAccountManager accounts;
    private final Random random = new Random();
    private final Map<String, GameSession> sessions = new HashMap<>();
    private final Map<UUID, String> playerSessions = new HashMap<>();
    private final Set<BukkitTask> tasks = new HashSet<>();
    private final Set<Particle> unsupportedParticles = new HashSet<>();
    private final Set<UUID> handledLeverClicks = new HashSet<>();
    private final Map<String, CachedLinks> linkCache = new HashMap<>();
    private final Map<BlockPosition, LeverPulse> visualLeverPulses = new HashMap<>();
    private final File pendingPayoutFile;
    private final Map<UUID, PendingPayout> pendingPayouts = new HashMap<>();
    private BiPredicate<Player, Venue> admissionChecker = (player, venue) -> false;
    private BiConsumer<Player, Venue> completionListener = (player, venue) -> {};
    private BiConsumer<Venue, Long> houseLossListener = (venue, profit) -> {};
    private SlotActivityListener activityListener = SlotActivityListener.NONE;
    private long ticks;
    private long leverPulseSequence;
    private int idleFrame;

    public SlotManager(GambleGatePlugin plugin, VenueRepository venues, MessageService messages, SoundService sounds, Economy economy, CasinoAccountManager accounts) {
        this.plugin = plugin; this.venues = venues; this.messages = messages; this.sounds = sounds; this.economy = economy;
        this.accounts = accounts;
        this.pendingPayoutFile = new File(plugin.getDataFolder(), "pending-payouts.yml");
        loadPendingPayouts();
    }

    public void setAdmissionChecker(BiPredicate<Player, Venue> checker) { admissionChecker = checker; }
    public void setCompletionListener(BiConsumer<Player, Venue> listener) { completionListener = listener; }
    public void setHouseLossListener(BiConsumer<Venue, Long> listener) { houseLossListener = listener == null ? (venue, profit) -> {} : listener; }
    public void setActivityListener(SlotActivityListener listener) { activityListener = listener == null ? SlotActivityListener.NONE : listener; }

    public long pendingPayout(UUID playerId) {
        PendingPayout payout = pendingPayouts.get(playerId);
        return payout == null ? 0 : payout.amount();
    }

    public boolean isPlaying(Player player, Venue venue) {
        String sessionKey = playerSessions.get(player.getUniqueId());
        GameSession session = sessionKey == null ? null : sessions.get(sessionKey);
        return session != null && session.venue().id().equalsIgnoreCase(venue.id());
    }

    public boolean isMachinePlaying(Venue venue, SlotMachine machine) { return sessions.containsKey(key(venue, machine)); }

    @Override
    public void run() {
        ticks++;
        int payoutRetryInterval = Math.max(20, plugin.getConfig().getInt("slot-machine.pending-payout-retry-ticks", 200));
        if (ticks % payoutRetryInterval == 0) retryPendingPayouts();
        int interval = Math.max(1, plugin.getConfig().getInt("slot-machine.idle-cycle-ticks", 20));
        if (ticks % interval != 0) return;
        idleFrame++;
        double range = Math.max(1, plugin.getConfig().getDouble("slot-machine.proximity-distance", 10));
        for (Venue venue : venues.all()) for (SlotMachine machine : venue.slotMachines().values()) {
            if (!machine.complete() || !isAutoLinked(venue, machine) || isMachinePlaying(venue, machine) || hasSeatedPlayer(machine) || !hasNearbyPlayer(machine, range)) continue;
            setShelf(machine, new Symbol[]{
                    SYMBOLS[Math.floorMod(idleFrame, SYMBOLS.length)],
                    SYMBOLS[Math.floorMod(idleFrame + 2, SYMBOLS.length)],
                    SYMBOLS[Math.floorMod(idleFrame + 4, SYMBOLS.length)]
            });
        }
    }

    @EventHandler
    public void onSit(EntitySitEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        FoundSlot found = findByChair(event.getSeat().getBlock());
        if (found == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!found.machine().complete() || !isAutoLinked(found.venue(), found.machine())) { messages.send(player, "slots.auto-link-missing"); sounds.play(player, "error"); return; }
            if (!admissionChecker.test(player, found.venue())) { messages.send(player, "slots.not-admitted"); sounds.play(player, "error"); return; }
            GameSession active = sessions.get(key(found.venue(), found.machine()));
            if (active != null) {
                if (active.player().getUniqueId().equals(player.getUniqueId())) messages.send(player, "slots.already-spinning");
                else { messages.send(player, "slots.busy"); sounds.play(player, "error"); }
                return;
            }
            Symbol ready = readySymbol();
            setShelf(found.machine(), new Symbol[]{ready, ready, ready});
            messages.send(player, "slots.ready", Map.of("bet", CurrencyFormatter.format(economy, found.machine().bet())));
            play(player, "slot-ready");
            readyEffects(found.machine());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        FoundSlot shelf = findByShelf(block);
        if (shelf != null) { event.setCancelled(true); return; }
        FoundSlot found = findByLever(block);
        if (found == null) {
            if (block.getType() == Material.LEVER && event.getAction() == Action.RIGHT_CLICK_BLOCK
                    && event.getPlayer().hasPermission("gamblegate.slotadmin")) {
                Venue venue = venueAt(block.getLocation());
                if (venue != null) messages.send(event.getPlayer(), "slots.unregistered-lever", Map.of(
                        "venue", venue.id(), "world", block.getWorld().getName(),
                        "x", block.getX(), "y", block.getY(), "z", block.getZ()));
            }
            return;
        }
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!handledLeverClicks.add(event.getPlayer().getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> handledLeverClicks.remove(event.getPlayer().getUniqueId()));
        pulseLever(block, event.getPlayer());
        start(event.getPlayer(), found.venue(), found.machine());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof Shelf shelf && findByShelf(shelf.getBlock()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        FoundSlot found = findByShelf(event.getBlock());
        if (found == null) found = findByLever(event.getBlock());
        if (found == null) found = findByChair(event.getBlock());
        if (found != null && isMachinePlaying(found.venue(), found.machine())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "slots.busy");
            sounds.play(event.getPlayer(), "error");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLinkedBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.LEVER && !(event.getBlock().getState() instanceof Shelf)) return;
        Bukkit.getScheduler().runTask(plugin, this::invalidateLinkCache);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLinkedBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.LEVER && !(event.getBlockPlaced().getState() instanceof Shelf)) return;
        Bukkit.getScheduler().runTask(plugin, this::invalidateLinkCache);
    }

    private void start(Player player, Venue venue, SlotMachine machine) {
        if (!machine.complete() || !isAutoLinked(venue, machine)) { messages.send(player, "slots.auto-link-missing"); sounds.play(player, "error"); return; }
        if (!accounts.isOpen(venue)) { messages.send(player, "account.game-closed"); sounds.play(player, "error"); return; }
        if (!admissionChecker.test(player, venue)) { messages.send(player, "slots.not-admitted"); sounds.play(player, "error"); return; }
        Seat seat = GSitAPI.getSeatByEntity(player);
        if (seat == null || !position(seat.getBlock()).equals(machine.chair())) { messages.send(player, "slots.not-sitting"); sounds.play(player, "error"); return; }
        String machineKey = key(venue, machine);
        GameSession active = sessions.get(machineKey);
        if (active != null) {
            if (active.player().getUniqueId().equals(player.getUniqueId())) messages.send(player, "slots.already-spinning");
            else { messages.send(player, "slots.busy"); sounds.play(player, "error"); }
            return;
        }
        if (playerSessions.containsKey(player.getUniqueId())) { messages.send(player, "slots.already-spinning"); return; }
        long bet = machine.bet();
        if (!economy.has(player, bet)) { messages.send(player, "slots.insufficient-funds", Map.of("bet", CurrencyFormatter.format(economy, bet))); sounds.play(player, "error"); return; }
        EconomyResponse response = economy.withdrawPlayer(player, bet);
        if (!response.transactionSuccess()) { messages.send(player, "slots.payment-failed"); sounds.play(player, "error"); return; }
        accounts.recordIncome(venue, player, bet, "slot-bet");
        GameSession session = new GameSession(player, venue, machine, bet);
        sessions.put(machineKey, session); playerSessions.put(player.getUniqueId(), machineKey);
        activityListener.onGameStarted(player, venue, bet);
        messages.send(player, "slots.started", Map.of("bet", CurrencyFormatter.format(economy, bet)));
        showTitle(player, "slots.spin-title", "slots.spin-subtitle", Map.of("bet", CurrencyFormatter.format(economy, bet)), 5, 30, 5);
        play(player, "slot-start");
        burstStart(machine);
        spin(session, false);
    }

    private void pulseLever(Block block, Player clickingPlayer) {
        turnLeverOff(block);
        if (!(block.getBlockData() instanceof Powerable serverData)) return;

        Powerable visualData = (Powerable) serverData.clone();
        visualData.setPowered(true);
        double viewDistance = Math.max(1, plugin.getConfig().getDouble("slot-machine.lever-animation-view-distance", 32));
        double viewDistanceSquared = viewDistance * viewDistance;
        Set<UUID> viewers = new HashSet<>();
        BlockPosition position = position(block);
        LeverPulse previous = visualLeverPulses.get(position);
        if (previous != null) viewers.addAll(previous.viewers());
        for (Player viewer : block.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(block.getLocation()) > viewDistanceSquared) continue;
            viewer.sendBlockChange(block.getLocation(), visualData);
            viewers.add(viewer.getUniqueId());
        }

        long pulseId = ++leverPulseSequence;
        LeverPulse pulse = new LeverPulse(pulseId, viewers);
        visualLeverPulses.put(position, pulse);
        // The cancelled interaction can make the server send the real (off) state back to
        // the clicking client after the immediate fake state. Re-send it next tick so the
        // player who pulled the lever also always sees the downward pulse.
        viewers.add(clickingPlayer.getUniqueId());
        runLater(() -> resendLeverPulse(position, pulse, clickingPlayer.getUniqueId()), 1);
        long duration = Math.max(1, plugin.getConfig().getLong("slot-machine.lever-animation-ticks", 4));
        runLater(() -> restoreLeverPulse(position, pulse), duration);
    }

    private void resendLeverPulse(BlockPosition position, LeverPulse pulse, UUID viewerId) {
        if (visualLeverPulses.get(position) != pulse) return;
        World world = Bukkit.getWorld(position.world());
        Player viewer = Bukkit.getPlayer(viewerId);
        if (world == null || viewer == null || !viewer.isOnline() || !viewer.getWorld().equals(world)
                || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) return;
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        if (!(block.getBlockData() instanceof Powerable data)) return;
        Powerable visualData = (Powerable) data.clone();
        visualData.setPowered(true);
        viewer.sendBlockChange(block.getLocation(), visualData);
    }

    private void restoreLeverPulse(BlockPosition position, LeverPulse pulse) {
        if (visualLeverPulses.get(position) != pulse) return;
        visualLeverPulses.remove(position);
        sendRealBlock(position, pulse.viewers());
    }

    private void sendRealBlock(BlockPosition position, Set<UUID> viewers) {
        World world = Bukkit.getWorld(position.world());
        if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) return;
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        turnLeverOff(block);
        for (UUID viewerId : viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline() && viewer.getWorld().equals(world)) {
                viewer.sendBlockChange(block.getLocation(), block.getBlockData());
            }
        }
    }

    private void restoreVisualLevers() {
        for (var entry : new HashMap<>(visualLeverPulses).entrySet()) sendRealBlock(entry.getKey(), entry.getValue().viewers());
        visualLeverPulses.clear();
    }

    private void turnLeverOff(Block block) {
        if (!(block.getBlockData() instanceof Powerable powerable) || !powerable.isPowered()) return;
        powerable.setPowered(false);
        block.setBlockData(powerable, false);
    }

    private void resetLever(SlotMachine machine) {
        Block block = nearestLever(machine);
        if (block == null) return;
        if (!(block.getBlockData() instanceof Powerable powerable) || !powerable.isPowered()) return;
        powerable.setPowered(false); block.setBlockData(powerable, false);
    }

    private void spin(GameSession session, boolean goldRush) {
        Symbol[] result = goldRush ? rollGoldRush() : rollNormal(session.venue(), session.bet());
        List<Integer> configuredStops = plugin.getConfig().getIntegerList("slot-machine.stop-steps");
        int[] stops = {20, 30, 40};
        for (int i = 0; i < Math.min(3, configuredStops.size()); i++) stops[i] = Math.max(2, configuredStops.get(i));
        int updateTicks = Math.max(1, plugin.getConfig().getInt("slot-machine.spin-update-ticks", 2));
        Symbol[] visible = randomDisplay(); boolean[] stopped = new boolean[3];
        if (goldRush) showTitle(session.player(), "slots.gold-spin-title", "slots.gold-spin-subtitle", Map.of("pot", CurrencyFormatter.format(economy, session.goldPot())), 5, 30, 5);
        BukkitTask[] taskHandle = new BukkitTask[1];
        BukkitRunnable runnable = new BukkitRunnable() {
            int step;
            @Override public void run() {
                step++;
                for (int reel = 0; reel < 3; reel++) {
                    if (stopped[reel]) continue;
                    if (step >= stops[reel]) {
                        visible[reel] = result[reel]; stopped[reel] = true;
                        reelStop(session, reel + 1, result[reel]);
                    } else {
                        visible[reel] = randomSymbol(goldRush ? "slot-machine.gold-rush.fallback-weights" : "slot-machine.normal-weights");
                    }
                }
                setShelf(session.machine(), visible);
                play(session.player(), "slot-tick");
                int particleInterval = Math.max(1, plugin.getConfig().getInt("slot-machine.effects.spin-particle-interval-steps", 5));
                int largeParticleInterval = Math.max(particleInterval, plugin.getConfig().getInt("slot-machine.effects.spin-large-particle-interval-steps", 15));
                if (step % particleInterval == 0) spinEffects(session.machine(), goldRush, step % largeParticleInterval == 0);
                if (stopped[0] && stopped[1] && stopped[2]) {
                    cancel(); tasks.remove(taskHandle[0]);
                    runLater(() -> evaluate(session, result, goldRush), 15L);
                }
            }
        };
        taskHandle[0] = runnable.runTaskTimer(plugin, 0L, updateTicks);
        tasks.add(taskHandle[0]);
    }

    private void reelStop(GameSession session, int reel, Symbol symbol) {
        play(session.player(), "slot-stop");
        String titlePath = "slots.reel-titles." + symbol.name();
        showTitle(session.player(), titlePath + ".title", titlePath + ".subtitle",
                Map.of("reel", reel, "symbol", symbolName(symbol)), 0, 15, 3);
        reelStopEffects(session.machine());
    }

    private void evaluate(GameSession session, Symbol[] result, boolean goldRush) {
        if (!sessions.containsKey(key(session.venue(), session.machine()))) return;
        boolean triple = result[0] == result[1] && result[1] == result[2];
        if (goldRush) {
            if (triple && result[0] == Symbol.COPPER_INGOT) {
                messages.send(session.player(), "slots.gold-rush-bust", Map.of("pot", CurrencyFormatter.format(economy, session.goldPot())));
                showTitle(session.player(), "slots.bust-title", "slots.bust-subtitle", Map.of("pot", CurrencyFormatter.format(economy, session.goldPot())), 5, 55, 10);
                activityListener.onGameResult(session.player(), session.venue(), messages.text("scoreboard.result-gold-bust"), 0);
                playSequence(session.player(), "slot-lose", "slot-copper", "slot-fail"); burstLoss(session.machine()); finish(session); return;
            }
            if (triple && result[0] == Symbol.GOLD_INGOT) {
                session.goldPot(doubleAndCap(session.goldPot()));
                session.goldChains(session.goldChains() + 1);
                activityListener.onGoldRush(session.player(), session.venue(), session.goldPot(), session.goldChains());
                messages.send(session.player(), "slots.gold-rush-chain", Map.of("pot", CurrencyFormatter.format(economy, session.goldPot())));
                goldRushTitle(session); burstGold(session.machine());
                runLater(() -> spin(session, true), 35L); return;
            }
            pay(session, session.goldPot(), WinType.GOLD_RUSH, null, "slots.gold-rush-payout", Map.of("payout", CurrencyFormatter.format(economy, session.goldPot()))); return;
        }
        if (triple) {
            Symbol symbol = result[0];
            if (symbol == Symbol.COPPER_INGOT) {
                messages.send(session.player(), "slots.copper-loss");
                showTitle(session.player(), "slots.copper-title", "slots.copper-subtitle", Map.of(), 5, 45, 10);
                activityListener.onGameResult(session.player(), session.venue(), messages.text("scoreboard.result-copper"), 0);
                playSequence(session.player(), "slot-lose", "slot-copper", "slot-fail"); burstLoss(session.machine()); finish(session); return;
            }
            if (symbol == Symbol.GOLD_INGOT) {
                session.goldPot(doubleAndCap(session.bet()));
                session.goldChains(1);
                activityListener.onGoldRush(session.player(), session.venue(), session.goldPot(), session.goldChains());
                messages.send(session.player(), "slots.gold-rush-start", Map.of("pot", CurrencyFormatter.format(economy, session.goldPot())));
                goldRushTitle(session); burstGold(session.machine());
                runLater(() -> spin(session, true), 40L); return;
            }
            double multiplier = plugin.getConfig().getDouble("slot-machine.payouts." + symbol.name(), symbol.defaultMultiplier);
            long payout = scaledAndCap(session.bet(), multiplier);
            pay(session, payout, WinType.TRIPLE, symbol, "slots.triple-win", Map.of("symbol", symbolName(symbol), "payout", CurrencyFormatter.format(economy, payout))); return;
        }
        if (isPair(result)) {
            long payout = scaledAndCap(session.bet(), plugin.getConfig().getDouble("slot-machine.payouts.two-match", 0.3));
            pay(session, payout, WinType.PAIR, null, "slots.two-match", Map.of("payout", CurrencyFormatter.format(economy, payout))); return;
        }
        messages.send(session.player(), "slots.lose");
        showTitle(session.player(), "slots.lose-title", "slots.lose-subtitle", Map.of(), 5, 40, 10);
        activityListener.onGameResult(session.player(), session.venue(), messages.text("scoreboard.result-lose"), 0);
        playSequence(session.player(), "slot-lose", "slot-fail"); burstLoss(session.machine()); finish(session);
    }

    private void pay(GameSession session, long payout, WinType type, Symbol symbol, String message, Map<String, ?> replacements) {
        if (payout > 0) {
            EconomyResponse response = depositWithFallback(session.player(), payout);
            if (!response.transactionSuccess()) {
                queuePayout(session.player(), payout);
                plugin.getLogger().warning("スロットの払戻しを保留しました: player=" + session.player().getName()
                        + ", venue=" + session.venue().id() + ", slot=" + session.machine().id()
                        + ", amount=" + payout + ", provider=" + economy.getName()
                        + ", balance=" + economy.getBalance(session.player())
                        + ", type=" + response.type + ", error=" + response.errorMessage
                        + ". 経済プラグインの残高上限などが解消されると自動で再入金します。");
                messages.send(session.player(), "slots.payout-queued", Map.of("payout", CurrencyFormatter.format(economy, payout)));
            }
            accounts.recordExpense(session.venue(), session.player(), payout, "slot-payout");
        }
        messages.send(session.player(), message, replacements);
        String result = type == WinType.GOLD_RUSH ? messages.text("scoreboard.result-gold-rush")
                : type == WinType.PAIR ? messages.text("scoreboard.result-pair") : symbolName(symbol);
        activityListener.onGameResult(session.player(), session.venue(), result, payout);
        if (payout > session.bet()) houseLossListener.accept(session.venue(), payout - session.bet());
        Map<String, Object> titleValues = Map.of("payout", CurrencyFormatter.format(economy, payout));
        if (type == WinType.GOLD_RUSH) {
            showTitle(session.player(), "slots.payout-title", "slots.payout-subtitle", titleValues, 5, 70, 15);
            playSequence(session.player(), "slot-payout", "slot-exp", "slot-levelup", "slot-challenge"); burstPayout(session.machine());
        } else if (type == WinType.TRIPLE) {
            Map<String, Object> tripleValues = Map.of("payout", CurrencyFormatter.format(economy, payout), "symbol", symbolName(symbol));
            String titlePath = "slots.jackpot-titles." + symbol.name();
            showTitle(session.player(), titlePath + ".title", titlePath + ".subtitle", tripleValues, 5, 60, 15);
            playTripleSequence(session.player(), symbol); burstWin(session.machine(), true);
        } else {
            showTitle(session.player(), "slots.pair-title", "slots.pair-subtitle", titleValues, 5, 45, 10);
            playSequence(session.player(), "slot-win", "slot-exp", "slot-levelup"); burstWin(session.machine(), false);
        }
        finish(session);
    }

    private void finish(GameSession session) {
        sessions.remove(key(session.venue(), session.machine())); playerSessions.remove(session.player().getUniqueId());
        completionListener.accept(session.player(), session.venue());
    }

    private Symbol[] rollNormal(Venue venue, long bet) {
        return switch (randomOutcome(venue, bet)) {
            case LOSS -> threeDifferent();
            case TWO_MATCH -> twoMatch();
            case TRIPLE_COPPER -> triple(Symbol.COPPER_INGOT);
            case TRIPLE_IRON -> triple(Symbol.IRON_INGOT);
            case TRIPLE_EMERALD -> triple(Symbol.EMERALD);
            case TRIPLE_DIAMOND -> triple(Symbol.DIAMOND);
            case TRIPLE_GOLD -> triple(Symbol.GOLD_INGOT);
        };
    }

    private NormalOutcome randomOutcome(Venue venue, long bet) {
        String path = "slot-machine.normal-outcomes.";
        double payingScale = payingOutcomeScale(venue, bet, path);
        double total = 0;
        for (NormalOutcome outcome : NORMAL_OUTCOMES) total += adjustedOutcomeWeight(path, outcome, payingScale);
        if (total <= 0) return NormalOutcome.LOSS;
        double pick = random.nextDouble() * total;
        for (NormalOutcome outcome : NORMAL_OUTCOMES) {
            pick -= adjustedOutcomeWeight(path, outcome, payingScale);
            if (pick < 0) return outcome;
        }
        return NormalOutcome.LOSS;
    }

    private double adjustedOutcomeWeight(String path, NormalOutcome outcome, double payingScale) {
        long base = Math.max(0, plugin.getConfig().getLong(path + outcome.name(), outcome.defaultWeight));
        return base * (isPayingOutcome(outcome) ? payingScale : 1.0);
    }

    private boolean isPayingOutcome(NormalOutcome outcome) {
        return outcome == NormalOutcome.TWO_MATCH || outcome == NormalOutcome.TRIPLE_IRON
                || outcome == NormalOutcome.TRIPLE_EMERALD || outcome == NormalOutcome.TRIPLE_DIAMOND
                || outcome == NormalOutcome.TRIPLE_GOLD;
    }

    /**
     * 設定された各結果の比率は保ったまま、無配当結果と配当結果の比率だけを調整し、
     * Gold Rushの連鎖・上限額まで含めた期待払戻しを目標RTPへ合わせます。
     */
    private double payingOutcomeScale(Venue venue, long bet, String path) {
        double zeroWeight = 0, payingWeight = 0, weightedPayout = 0;
        for (NormalOutcome outcome : NORMAL_OUTCOMES) {
            double weight = Math.max(0, plugin.getConfig().getLong(path + outcome.name(), outcome.defaultWeight));
            double multiplier = outcomePayoutMultiplier(outcome, bet);
            if (multiplier <= 0) zeroWeight += weight;
            else {
                payingWeight += weight;
                weightedPayout += weight * multiplier;
            }
        }
        double target = effectiveSlotRtp(venue);
        double denominator = weightedPayout - target * payingWeight;
        if (zeroWeight <= 0 || payingWeight <= 0 || denominator <= 0 || !Double.isFinite(denominator)) return 1.0;
        double scale = target * zeroWeight / denominator;
        return Double.isFinite(scale) && scale > 0 ? scale : 1.0;
    }

    private double effectiveSlotRtp(Venue venue) {
        double base = Math.max(0.50, Math.min(1.20, plugin.getConfig().getDouble("slot-machine.rtp", 1.0)));
        if (!plugin.getConfig().getBoolean("slot-machine.dynamic-odds.enabled", true)) return base;
        CasinoAccountManager.AccountSnapshot account = accounts.snapshot(venue);
        double income = Math.max(0, (double) account.totalIncome());
        double expense = Math.max(0, (double) account.totalExpense());
        double turnover = income + expense;
        if (!Double.isFinite(turnover) || turnover <= 0) return base;

        double minimumTurnover = Math.max(0, plugin.getConfig().getDouble("slot-machine.dynamic-odds.minimum-turnover", 100_000));
        double sampleStrength = minimumTurnover <= 0 ? 1 : Math.min(1, turnover / minimumTurnover);
        double profitMargin = (income - expense) / turnover;
        double fullMargin = Math.max(0.0001, plugin.getConfig().getDouble("slot-machine.dynamic-odds.full-adjustment-profit-margin", 0.20));
        double direction = Math.max(-1, Math.min(1, profitMargin / fullMargin));
        double maximum = Math.max(0, Math.min(0.20, plugin.getConfig().getDouble("slot-machine.dynamic-odds.maximum-rtp-adjustment", 0.02)));
        return Math.max(0.50, Math.min(1.20, base + direction * maximum * sampleStrength));
    }

    private double outcomePayoutMultiplier(NormalOutcome outcome, long bet) {
        if (bet <= 0) return switch (outcome) {
            case TWO_MATCH -> Math.max(0, plugin.getConfig().getDouble("slot-machine.payouts.two-match", 0.3));
            case TRIPLE_IRON -> Math.max(0, plugin.getConfig().getDouble("slot-machine.payouts.IRON_INGOT", 2.0));
            case TRIPLE_EMERALD -> Math.max(0, plugin.getConfig().getDouble("slot-machine.payouts.EMERALD", 3.0));
            case TRIPLE_DIAMOND -> Math.max(0, plugin.getConfig().getDouble("slot-machine.payouts.DIAMOND", 5.0));
            case TRIPLE_GOLD -> 1.0;
            default -> 0;
        };
        return switch (outcome) {
            case TWO_MATCH -> scaledAndCap(bet, plugin.getConfig().getDouble("slot-machine.payouts.two-match", 0.3)) / (double) bet;
            case TRIPLE_IRON -> scaledAndCap(bet, plugin.getConfig().getDouble("slot-machine.payouts.IRON_INGOT", 2.0)) / (double) bet;
            case TRIPLE_EMERALD -> scaledAndCap(bet, plugin.getConfig().getDouble("slot-machine.payouts.EMERALD", 3.0)) / (double) bet;
            case TRIPLE_DIAMOND -> scaledAndCap(bet, plugin.getConfig().getDouble("slot-machine.payouts.DIAMOND", 5.0)) / (double) bet;
            case TRIPLE_GOLD -> goldRushExpectedPayout(doubleAndCap(bet)) / bet;
            default -> 0;
        };
    }

    private double goldRushExpectedPayout(long pot) {
        long cap = Math.max(1, plugin.getConfig().getLong("slot-machine.max-payout", 100_000_000));
        double chainChance = Math.max(0, Math.min(0.999999,
                plugin.getConfig().getDouble("slot-machine.gold-rush.triple-gold-chance", 0.5)));
        double copperChance = fallbackSymbolChance(Symbol.COPPER_INGOT);
        double bustChance = copperChance * copperChance * copperChance;
        return goldRushExpectedPayout(Math.min(cap, Math.max(0, pot)), cap, chainChance, bustChance);
    }

    private double goldRushExpectedPayout(long pot, long cap, double chainChance, double bustChance) {
        if (pot >= cap) return (1.0 - bustChance) * cap;
        long doubled = pot > cap / 2 ? cap : pot * 2;
        return chainChance * goldRushExpectedPayout(doubled, cap, chainChance, bustChance)
                + (1.0 - chainChance) * (1.0 - bustChance) * pot;
    }

    private double fallbackSymbolChance(Symbol target) {
        String path = "slot-machine.gold-rush.fallback-weights.";
        long total = 0, selected = 0;
        for (Symbol symbol : SYMBOLS) {
            long weight = Math.max(0, plugin.getConfig().getLong(path + symbol.name(), 1));
            total += weight;
            if (symbol == target) selected = weight;
        }
        return total <= 0 ? 1.0 / SYMBOLS.length : selected / (double) total;
    }

    private Symbol[] threeDifferent() {
        Symbol first = randomSymbol("slot-machine.normal-weights");
        Symbol second = randomExcluding(first);
        Symbol third = randomExcluding(first, second);
        return new Symbol[]{first, second, third};
    }

    private Symbol[] twoMatch() {
        Symbol match = randomSymbol("slot-machine.normal-weights");
        Symbol other = randomExcluding(match);
        Symbol[] result = {match, match, other};
        int otherIndex = random.nextInt(3);
        result[2] = result[otherIndex]; result[otherIndex] = other;
        return result;
    }

    private Symbol[] triple(Symbol symbol) { return new Symbol[]{symbol, symbol, symbol}; }
    private Symbol[] randomDisplay() { return new Symbol[]{randomSymbol("slot-machine.normal-weights"), randomSymbol("slot-machine.normal-weights"), randomSymbol("slot-machine.normal-weights")}; }

    private Symbol[] rollGoldRush() {
        double chance = Math.max(0, Math.min(0.999999, plugin.getConfig().getDouble("slot-machine.gold-rush.triple-gold-chance", 0.5)));
        if (random.nextDouble() < chance) return triple(Symbol.GOLD_INGOT);
        Symbol[] result = new Symbol[]{randomSymbol("slot-machine.gold-rush.fallback-weights"), randomSymbol("slot-machine.gold-rush.fallback-weights"), randomSymbol("slot-machine.gold-rush.fallback-weights")};
        if (result[0] == Symbol.GOLD_INGOT && result[1] == Symbol.GOLD_INGOT && result[2] == Symbol.GOLD_INGOT) result[2] = Symbol.COPPER_INGOT;
        return result;
    }

    private Symbol randomExcluding(Symbol... excluded) {
        List<Symbol> choices = new ArrayList<>();
        outer: for (Symbol symbol : SYMBOLS) {
            for (Symbol blocked : excluded) if (symbol == blocked) continue outer;
            choices.add(symbol);
        }
        return choices.get(random.nextInt(choices.size()));
    }

    private Symbol randomSymbol(String path) {
        long total = 0;
        for (Symbol symbol : SYMBOLS) total += Math.max(0, plugin.getConfig().getLong(path + "." + symbol.name(), 1));
        if (total <= 0) return SYMBOLS[random.nextInt(SYMBOLS.length)];
        long pick = random.nextLong(total);
        for (Symbol symbol : SYMBOLS) {
            pick -= Math.max(0, plugin.getConfig().getLong(path + "." + symbol.name(), 1));
            if (pick < 0) return symbol;
        }
        return Symbol.COPPER_INGOT;
    }

    private boolean isPair(Symbol[] result) { return result[0] == result[1] || result[0] == result[2] || result[1] == result[2]; }
    private Symbol readySymbol() {
        String configured = plugin.getConfig().getString("slot-machine.ready-symbol", "IRON_INGOT");
        try { return Symbol.valueOf((configured == null ? "IRON_INGOT" : configured).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return Symbol.IRON_INGOT; }
    }
    private long doubleAndCap(long value) { return scaledAndCap(value, 2); }
    private long scaledAndCap(long value, double multiplier) {
        long cap = Math.max(1, plugin.getConfig().getLong("slot-machine.max-payout", 100_000_000));
        if (value <= 0 || !Double.isFinite(multiplier) || multiplier <= 0) return 0;
        double scaled = value * multiplier;
        return scaled >= cap ? cap : Math.max(0, (long) Math.floor(scaled));
    }
    private String symbolName(Symbol symbol) { return messages.text("slots.symbols." + symbol.name()); }

    private void showTitle(Player player, String title, String subtitle, Map<String, ?> values, int in, int stay, int out) {
        if (player.isOnline()) player.sendTitle(messages.text(title, values), messages.text(subtitle, values), in, stay, out);
    }
    private void goldRushTitle(GameSession session) {
        showTitle(session.player(), "slots.gold-rush-title", "slots.gold-rush-subtitle", Map.of("pot", CurrencyFormatter.format(economy, session.goldPot())), 5, 55, 10);
        playGoldRushBeaconLayers(session.player());
    }

    private void playGoldRushBeaconLayers(Player player) {
        int repeats = Math.max(1, plugin.getConfig().getInt("slot-machine.effects.gold-rush-beacon-repeats", 5));
        long interval = Math.max(1, plugin.getConfig().getLong("slot-machine.effects.gold-rush-beacon-interval-ticks", 4));
        for (int i = 0; i < repeats; i++) {
            long delay = i * interval;
            if (delay == 0) play(player, "gold-rush-progress");
            else runLater(() -> play(player, "gold-rush-progress"), delay);
        }
        runLater(() -> play(player, "slot-exp"), Math.max(1, interval));
    }

    private void playTripleSequence(Player player, Symbol symbol) {
        switch (symbol) {
            case IRON_INGOT, EMERALD -> play(player, "slot-levelup");
            case DIAMOND -> play(player, "slot-challenge");
            default -> play(player, "slot-win");
        }
    }

    private void playSequence(Player player, String... soundKeys) {
        for (int i = 0; i < soundKeys.length; i++) {
            String sound = soundKeys[i];
            if (i == 0) play(player, sound);
            else runLater(() -> play(player, sound), i * 4L);
        }
    }

    private void readyEffects(SlotMachine machine) { particles(machine, Particle.ENCHANT, 12, 0.45, 0.03); }
    private void burstStart(SlotMachine machine) { particles(machine, Particle.ENCHANT, 20, 0.65, 0.08); }
    private void spinEffects(SlotMachine machine, boolean goldRush, boolean large) {
        particles(machine, Particle.ENCHANT, large ? 6 : 2, 0.5, 0.03);
        if (goldRush) {
            particles(machine, Particle.FLAME, large ? 7 : 2, 0.55, 0.03);
            particles(machine, Particle.WAX_ON, large ? 6 : 2, 0.55, 0.03);
        }
    }
    private void reelStopEffects(SlotMachine machine) { particles(machine, Particle.CRIT, 18, 0.55, 0.12); particles(machine, Particle.END_ROD, 8, 0.25, 0.04); }
    private void burstLoss(SlotMachine machine) { particles(machine, Particle.LARGE_SMOKE, 25, 0.6, 0.06); }
    private void burstWin(SlotMachine machine, boolean large) {
        particles(machine, Particle.HAPPY_VILLAGER, large ? 35 : 18, 0.75, 0.1);
        particles(machine, Particle.FIREWORK, large ? 45 : 20, 0.8, 0.16);
        if (large) particles(machine, Particle.TOTEM_OF_UNDYING, 35, 0.8, 0.14);
    }
    private void burstPayout(SlotMachine machine) { particles(machine, Particle.TOTEM_OF_UNDYING, 80, 1.0, 0.2); particles(machine, Particle.FIREWORK, 80, 1.0, 0.22); burstGold(machine); }
    private void burstGold(SlotMachine machine) { particles(machine, Particle.TOTEM_OF_UNDYING, 55, 0.9, 0.16); particles(machine, Particle.WAX_ON, 35, 0.9, 0.1); particles(machine, Particle.FLAME, 30, 0.8, 0.08); }
    private void particles(SlotMachine machine, Particle particle, int count, double spread, double speed) {
        Location center = center(machine); if (center == null) return;
        if (particle.getDataType() != Void.class) {
            if (unsupportedParticles.add(particle)) plugin.getLogger().warning("必須データ付きパーティクルをスキップしました: " + particle.getKey());
            return;
        }
        center.getWorld().spawnParticle(particle, center, count, spread, spread, spread, speed);
    }

    private Location center(SlotMachine machine) {
        Block shelf = nearestShelf(machine);
        return shelf == null ? null : shelf.getLocation().add(0.5, 0.7, 0.5);
    }
    private void play(Player player, String sound) { if (player.isOnline()) sounds.play(player, sound); }

    private EconomyResponse depositWithFallback(Player player, long amount) {
        if (!economy.hasAccount(player)) {
            boolean created = economy.createPlayerAccount(player);
            if (!created && !economy.hasAccount(player)) {
                plugin.getLogger().warning("Vault口座を作成できませんでした: player=" + player.getName()
                        + ", provider=" + economy.getName());
                return new EconomyResponse(0, economy.getBalance(player), EconomyResponse.ResponseType.FAILURE,
                        "Vault account is unavailable");
            }
        }
        double before = economy.getBalance(player);
        EconomyResponse primary = economy.depositPlayer(player, amount);
        if (primary.transactionSuccess() || credited(before, economy.getBalance(player), amount)) return primary.transactionSuccess()
                ? primary : new EconomyResponse(amount, economy.getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
        return primary;
    }

    private boolean credited(double before, double after, long amount) {
        double tolerance = Math.max(0.000001, Math.abs(amount) * 0.000000001);
        return Double.isFinite(before) && Double.isFinite(after) && after - before >= amount - tolerance;
    }

    private void loadPendingPayouts() {
        pendingPayouts.clear();
        if (!pendingPayoutFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(pendingPayoutFile);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return;
        for (String rawUuid : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(rawUuid);
                long amount = players.getLong(rawUuid + ".amount");
                if (amount <= 0) continue;
                pendingPayouts.put(uuid, new PendingPayout(players.getString(rawUuid + ".name", rawUuid), amount));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("pending-payouts.yml の不正なUUIDを無視しました: " + rawUuid);
            }
        }
        if (!pendingPayouts.isEmpty()) {
            plugin.getLogger().info("保留中のスロット払戻しを " + pendingPayouts.size() + " 人分読み込みました。");
        }
    }

    private void queuePayout(Player player, long amount) {
        PendingPayout current = pendingPayouts.get(player.getUniqueId());
        long total;
        try {
            total = Math.addExact(current == null ? 0 : current.amount(), amount);
        } catch (ArithmeticException exception) {
            total = Long.MAX_VALUE;
        }
        pendingPayouts.put(player.getUniqueId(), new PendingPayout(player.getName(), total));
        savePendingPayouts();
    }

    private void retryPendingPayouts() {
        if (pendingPayouts.isEmpty()) return;
        for (var entry : new ArrayList<>(pendingPayouts.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            PendingPayout pending = entry.getValue();
            EconomyResponse response = depositWithFallback(player, pending.amount());
            if (!response.transactionSuccess()) continue;
            pendingPayouts.remove(entry.getKey());
            savePendingPayouts();
            messages.send(player, "slots.pending-payout-paid", Map.of("payout", CurrencyFormatter.format(economy, pending.amount())));
            playSequence(player, "slot-payout", "slot-exp");
            plugin.getLogger().info("保留していたスロット払戻しを入金しました: player=" + player.getName()
                    + ", amount=" + pending.amount() + ", provider=" + economy.getName());
        }
    }

    private void savePendingPayouts() {
        YamlConfiguration data = new YamlConfiguration();
        for (var entry : pendingPayouts.entrySet()) {
            String path = "players." + entry.getKey();
            data.set(path + ".name", entry.getValue().playerName());
            data.set(path + ".amount", entry.getValue().amount());
        }
        File temporary = new File(plugin.getDataFolder(), "pending-payouts.yml.tmp");
        try {
            data.save(temporary);
            try {
                Files.move(temporary.toPath(), pendingPayoutFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), pendingPayoutFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("保留中のスロット払戻しを保存できませんでした: " + exception.getMessage());
        }
    }

    private boolean setShelf(SlotMachine machine, Symbol[] symbols) {
        Block block = nearestShelf(machine);
        if (block == null) return false;
        if (!(block.getState() instanceof Shelf shelf)) return false;
        for (int i = 0; i < 3; i++) shelf.getInventory().setItem(i, new ItemStack(symbols[i].material));
        return true;
    }

    private boolean hasNearbyPlayer(SlotMachine machine, double range) {
        Location center = center(machine); if (center == null) return false;
        double squared = range * range;
        return center.getWorld().getPlayers().stream().anyMatch(player -> player.getLocation().distanceSquared(center) <= squared);
    }
    private boolean hasSeatedPlayer(SlotMachine machine) {
        if (machine.chair() == null) return false;
        World world = Bukkit.getWorld(machine.chair().world()); if (world == null) return false;
        return world.getPlayers().stream().anyMatch(player -> {
            Seat seat = GSitAPI.getSeatByEntity(player);
            return seat != null && position(seat.getBlock()).equals(machine.chair());
        });
    }
    private FoundSlot findByChair(Block block) { BlockPosition position = position(block); for (Venue venue : venues.all()) for (SlotMachine machine : venue.slotMachines().values()) if (position.equals(machine.chair())) return new FoundSlot(venue, machine); return null; }
    private FoundSlot findByShelf(Block block) { BlockPosition position = position(block); for (Venue venue : venues.all()) for (SlotMachine machine : venue.slotMachines().values()) { Block linked = nearestShelf(venue, machine); if (linked != null && position.equals(position(linked))) return new FoundSlot(venue, machine); } return null; }
    private FoundSlot findByLever(Block block) { BlockPosition position = position(block); for (Venue venue : venues.all()) for (SlotMachine machine : venue.slotMachines().values()) { Block linked = nearestLever(venue, machine); if (linked != null && position.equals(position(linked))) return new FoundSlot(venue, machine); } return null; }
    private Venue venueAt(Location location) { return venues.all().stream().filter(venue -> venue.contains(location)).findFirst().orElse(null); }
    private BlockPosition position(Block block) { return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()); }
    private String key(Venue venue, SlotMachine machine) { return venue.id().toLowerCase(Locale.ROOT) + ":" + machine.id().toLowerCase(Locale.ROOT); }
    private void runLater(Runnable action, long delay) {
        BukkitTask[] taskHandle = new BukkitTask[1];
        taskHandle[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try { action.run(); } finally { tasks.remove(taskHandle[0]); }
        }, delay);
        tasks.add(taskHandle[0]);
    }

    public boolean isRegisteredBlock(Block block) { return findByShelf(block) != null || findByChair(block) != null || findByLever(block) != null; }

    public BlockPosition linkedShelf(Venue venue, SlotMachine machine) {
        Block block = nearestShelf(venue, machine);
        return block == null ? null : position(block);
    }

    public BlockPosition linkedLever(Venue venue, SlotMachine machine) {
        Block block = nearestLever(venue, machine);
        return block == null ? null : position(block);
    }

    public boolean isAutoLinked(Venue venue, SlotMachine machine) {
        return machine.complete() && nearestShelf(venue, machine) != null && nearestLever(venue, machine) != null;
    }

    public void invalidateLinkCache() { linkCache.clear(); }

    private Block nearestShelf(SlotMachine machine) {
        Venue venue = venueOf(machine);
        return venue == null ? null : nearestShelf(venue, machine);
    }

    private Block nearestLever(SlotMachine machine) {
        Venue venue = venueOf(machine);
        return venue == null ? null : nearestLever(venue, machine);
    }

    private Block nearestShelf(Venue venue, SlotMachine machine) { return blockAt(resolveLinks(venue, machine).shelf(), true); }
    private Block nearestLever(Venue venue, SlotMachine machine) { return blockAt(resolveLinks(venue, machine).lever(), false); }

    private CachedLinks resolveLinks(Venue venue, SlotMachine machine) {
        String cacheKey = key(venue, machine);
        CachedLinks cached = linkCache.get(cacheKey);
        if (cached != null && cached.expiresAtTick() > ticks
                && validCachedBlock(cached.shelf(), true) && validCachedBlock(cached.lever(), false)) return cached;

        Block shelf = scanNearestBlock(venue, machine, true);
        Block lever = scanNearestBlock(venue, machine, false);
        long ttl = Math.max(1, plugin.getConfig().getLong("slot-machine.auto-link-cache-ticks", 100));
        CachedLinks resolved = new CachedLinks(shelf == null ? null : position(shelf), lever == null ? null : position(lever), ticks + ttl);
        linkCache.put(cacheKey, resolved);
        return resolved;
    }

    private Block scanNearestBlock(Venue venue, SlotMachine machine, boolean shelf) {
        BlockPosition chair = machine.chair();
        if (chair == null) return null;
        World world = Bukkit.getWorld(chair.world());
        if (world == null) return null;
        double radius = Math.max(1, plugin.getConfig().getDouble("slot-machine.auto-link-radius", 3));
        double radiusSquared = radius * radius;
        int range = (int) Math.ceil(radius);
        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = chair.x() - range; x <= chair.x() + range; x++) {
            for (int y = chair.y() - range; y <= chair.y() + range; y++) {
                for (int z = chair.z() - range; z <= chair.z() + range; z++) {
                    double distance = squaredDistance(chair, x, y, z);
                    if (distance > radiusSquared || distance > bestDistance || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (shelf ? !(block.getState() instanceof Shelf) : block.getType() != Material.LEVER) continue;
                    if (!venue.contains(block.getLocation()) || !ownedBy(venue, machine, block)) continue;
                    if (distance < bestDistance || best == null || compareBlock(block, best) < 0) {
                        best = block;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private boolean validCachedBlock(BlockPosition position, boolean shelf) {
        return position == null || blockAt(position, shelf) != null;
    }

    private Block blockAt(BlockPosition position, boolean shelf) {
        if (position == null) return null;
        World world = Bukkit.getWorld(position.world());
        if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) return null;
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        return shelf ? block.getState() instanceof Shelf ? block : null : block.getType() == Material.LEVER ? block : null;
    }

    private boolean ownedBy(Venue venue, SlotMachine owner, Block block) {
        BlockPosition ownerChair = owner.chair();
        double ownerDistance = squaredDistance(ownerChair, block.getX(), block.getY(), block.getZ());
        for (SlotMachine other : venue.slotMachines().values()) {
            if (other == owner || other.chair() == null || !other.chair().world().equals(block.getWorld().getName())) continue;
            double otherDistance = squaredDistance(other.chair(), block.getX(), block.getY(), block.getZ());
            if (otherDistance < ownerDistance || (otherDistance == ownerDistance && other.id().compareToIgnoreCase(owner.id()) < 0)) return false;
        }
        return true;
    }

    private double squaredDistance(BlockPosition from, int x, int y, int z) {
        double dx = from.x() - x, dy = from.y() - y, dz = from.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private int compareBlock(Block first, Block second) {
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) return x;
        int y = Integer.compare(first.getY(), second.getY());
        return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
    }

    private Venue venueOf(SlotMachine machine) {
        for (Venue venue : venues.all()) if (venue.slotMachines().values().stream().anyMatch(candidate -> candidate == machine)) return venue;
        return null;
    }

    public void resetRuntime() {
        restoreVisualLevers();
        tasks.forEach(BukkitTask::cancel); tasks.clear();
        handledLeverClicks.clear();
        for (Venue venue : venues.all()) for (SlotMachine machine : venue.slotMachines().values()) resetLever(machine);
        for (GameSession session : new ArrayList<>(sessions.values())) {
            EconomyResponse response = depositWithFallback(session.player(), session.bet());
            if (!response.transactionSuccess()) {
                queuePayout(session.player(), session.bet());
                plugin.getLogger().warning("中断されたスロットの返金を保留しました: player=" + session.player().getName()
                        + ", venue=" + session.venue().id() + ", slot=" + session.machine().id()
                        + ", amount=" + session.bet() + ", provider=" + economy.getName()
                        + ", type=" + response.type + ", error=" + response.errorMessage);
                if (session.player().isOnline()) messages.send(session.player(), "slots.refund-queued", Map.of("bet", CurrencyFormatter.format(economy, session.bet())));
            } else if (session.player().isOnline()) {
                messages.send(session.player(), "slots.refund-on-disable", Map.of("bet", CurrencyFormatter.format(economy, session.bet())));
            }
            accounts.recordExpense(session.venue(), session.player(), session.bet(), "slot-refund");
            completionListener.accept(session.player(), session.venue());
        }
        sessions.clear(); playerSessions.clear();
        invalidateLinkCache();
    }

    private enum Symbol {
        COPPER_INGOT(Material.COPPER_INGOT, 0), IRON_INGOT(Material.IRON_INGOT, 2), EMERALD(Material.EMERALD, 3), DIAMOND(Material.DIAMOND, 5), GOLD_INGOT(Material.GOLD_INGOT, 0);
        private final Material material; private final double defaultMultiplier;
        Symbol(Material material, double defaultMultiplier) { this.material = material; this.defaultMultiplier = defaultMultiplier; }
    }
    private enum NormalOutcome {
        LOSS(25), TWO_MATCH(50), TRIPLE_COPPER(5), TRIPLE_IRON(10), TRIPLE_EMERALD(5), TRIPLE_DIAMOND(4), TRIPLE_GOLD(1);
        private final long defaultWeight;
        NormalOutcome(long defaultWeight) { this.defaultWeight = defaultWeight; }
    }
    private enum WinType { PAIR, TRIPLE, GOLD_RUSH }
    private record FoundSlot(Venue venue, SlotMachine machine) {}
    private record CachedLinks(BlockPosition shelf, BlockPosition lever, long expiresAtTick) {}
    private record LeverPulse(long id, Set<UUID> viewers) {}
    private record PendingPayout(String playerName, long amount) {}
    private static final class GameSession {
        private final Player player; private final Venue venue; private final SlotMachine machine; private final long bet; private long goldPot; private int goldChains;
        private GameSession(Player player, Venue venue, SlotMachine machine, long bet) { this.player = player; this.venue = venue; this.machine = machine; this.bet = bet; }
        Player player() { return player; } Venue venue() { return venue; } SlotMachine machine() { return machine; } long bet() { return bet; } long goldPot() { return goldPot; } void goldPot(long value) { goldPot = value; } int goldChains() { return goldChains; } void goldChains(int value) { goldChains = value; }
    }
}
