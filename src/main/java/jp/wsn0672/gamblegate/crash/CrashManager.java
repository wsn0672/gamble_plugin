package jp.wsn0672.gamblegate.crash;

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
import jp.wsn0672.gamblegate.model.CrashMachine;
import jp.wsn0672.gamblegate.model.Venue;
import jp.wsn0672.gamblegate.scoreboard.SlotActivityListener;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
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

public final class CrashManager implements Listener, Runnable {
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final SoundService sounds;
    private final Economy economy;
    private final CasinoAccountManager accounts;
    private final Random random = new Random();
    private final Map<String, GameSession> sessions = new HashMap<>();
    private final Map<UUID, String> playerSessions = new HashMap<>();
    private final Map<UUID, MenuContext> menus = new HashMap<>();
    private final Map<String, TextDisplay> idleDisplays = new HashMap<>();
    private final Map<String, GuideDisplay> guideDisplays = new HashMap<>();
    private final Map<String, TextDisplay> resultDisplays = new HashMap<>();
    private final Map<UUID, PendingPayout> pendingPayouts = new HashMap<>();
    private final File pendingFile;
    private BiPredicate<Player, Venue> admissionChecker = (player, venue) -> false;
    private BiConsumer<Player, Venue> completionListener = (player, venue) -> {};
    private BiConsumer<Venue, Long> houseLossListener = (venue, profit) -> {};
    private SlotActivityListener activityListener = SlotActivityListener.NONE;
    private long ticks;
    private boolean resetting;

    public CrashManager(GambleGatePlugin plugin, VenueRepository venues, MessageService messages, SoundService sounds,
                        Economy economy, CasinoAccountManager accounts) {
        this.plugin = plugin; this.venues = venues; this.messages = messages; this.sounds = sounds;
        this.economy = economy; this.accounts = accounts;
        pendingFile = new File(plugin.getDataFolder(), "pending-crash-payouts.yml");
        loadPendingPayouts();
    }

    public void setAdmissionChecker(BiPredicate<Player, Venue> checker) { admissionChecker = checker; }
    public void setCompletionListener(BiConsumer<Player, Venue> listener) { completionListener = listener == null ? (p, v) -> {} : listener; }
    public void setHouseLossListener(BiConsumer<Venue, Long> listener) { houseLossListener = listener == null ? (venue, profit) -> {} : listener; }
    public void setActivityListener(SlotActivityListener listener) { activityListener = listener == null ? SlotActivityListener.NONE : listener; }
    public boolean isPlaying(Venue venue, CrashMachine machine) {
        String machineKey = key(venue, machine);
        return sessions.containsKey(machineKey) || resultDisplays.containsKey(machineKey);
    }
    public boolean isPlaying(Player player, Venue venue) {
        String sessionKey = playerSessions.get(player.getUniqueId());
        GameSession session = sessionKey == null ? null : sessions.get(sessionKey);
        return session != null && session.venue().id().equalsIgnoreCase(venue.id());
    }

    @Override
    public void run() {
        ticks++;
        int retry = Math.max(20, plugin.getConfig().getInt("crash.pending-payout-retry-ticks", 200));
        if (ticks % retry == 0) retryPendingPayouts();
        int updateTicks = Math.max(1, plugin.getConfig().getInt("crash.update-ticks", 2));
        if (ticks % updateTicks == 0) for (GameSession session : new ArrayList<>(sessions.values())) advance(session);
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
        if (found != null) Bukkit.getScheduler().runTaskLater(plugin, () -> showGuide(player, found.venue(), found.machine()), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || !isButton(event.getClickedBlock().getType())) return;
        FoundMachine found = findByButton(event.getClickedBlock());
        if (found == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        GameSession active = sessions.get(key(found.venue(), found.machine()));
        if (active != null) {
            if (!active.player().getUniqueId().equals(player.getUniqueId())) {
                messages.send(player, "crash.busy"); sounds.play(player, "error"); return;
            }
            cashOut(active, false);
            return;
        }
        if (resultDisplays.containsKey(key(found.venue(), found.machine()))) {
            messages.send(player, "crash.result-wait"); sounds.play(player, "error"); return;
        }
        openBetMenu(player, found.venue(), found.machine());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MenuContext context = menus.get(player.getUniqueId());
        if (context == null || !context.inventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        int index = switch (event.getRawSlot()) { case 11 -> 0; case 13 -> 1; case 15 -> 2; default -> -1; };
        if (index >= 0) {
            List<Long> bets = bets();
            if (index < bets.size()) start(player, context.venue(), context.machine(), bets.get(index));
        } else if (event.getRawSlot() == 22) player.closeInventory();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        MenuContext context = menus.get(event.getPlayer().getUniqueId());
        if (context != null && context.inventory().equals(event.getInventory())) menus.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        FoundMachine found = findByChair(event.getBlock());
        if (found == null && isButton(event.getBlock().getType())) found = findByButton(event.getBlock());
        if (found != null && isPlaying(found.venue(), found.machine())) {
            event.setCancelled(true); messages.send(event.getPlayer(), "crash.busy"); sounds.play(event.getPlayer(), "error");
            return;
        }
        if (found != null || isButton(event.getBlock().getType())) Bukkit.getScheduler().runTask(plugin, this::refreshDisplays);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isButton(event.getBlockPlaced().getType())) Bukkit.getScheduler().runTask(plugin, this::refreshDisplays);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menus.remove(event.getPlayer().getUniqueId());
        String sessionKey = playerSessions.get(event.getPlayer().getUniqueId());
        GameSession session = sessionKey == null ? null : sessions.get(sessionKey);
        if (session != null) bust(session, false);
    }

    private void openBetMenu(Player player, Venue venue, CrashMachine machine) {
        if (!isAutoLinked(venue, machine)) { messages.send(player, "crash.auto-link-missing"); sounds.play(player, "error"); return; }
        if (!accounts.isOpen(venue)) { messages.send(player, "crash.game-closed"); sounds.play(player, "error"); return; }
        if (!admissionChecker.test(player, venue)) { messages.send(player, "crash.not-admitted"); sounds.play(player, "error"); return; }
        if (!isSeated(player, machine)) { messages.send(player, "crash.not-sitting"); sounds.play(player, "error"); return; }
        if (playerSessions.containsKey(player.getUniqueId())) { messages.send(player, "crash.already-playing"); return; }
        Inventory inventory = Bukkit.createInventory(null, 27, messages.text("crash.gui-title"));
        List<Long> bets = bets();
        int[] slots = {11, 13, 15}; Material[] materials = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND};
        for (int i = 0; i < Math.min(3, bets.size()); i++) inventory.setItem(slots[i], item(materials[i], messages.text("crash.bet-name", Map.of("bet", money(bets.get(i))))));
        inventory.setItem(22, item(Material.BARRIER, messages.text("crash.cancel-name")));
        menus.put(player.getUniqueId(), new MenuContext(venue, machine, inventory));
        player.openInventory(inventory); sounds.play(player, "gui-open");
    }

    private void start(Player player, Venue venue, CrashMachine machine, long bet) {
        if (sessions.containsKey(key(venue, machine))) { player.closeInventory(); messages.send(player, "crash.busy"); sounds.play(player, "error"); return; }
        if (!accounts.isOpen(venue) || !admissionChecker.test(player, venue) || !isSeated(player, machine)) {
            player.closeInventory(); messages.send(player, "crash.start-invalid"); sounds.play(player, "error"); return;
        }
        if (playerSessions.containsKey(player.getUniqueId())) { player.closeInventory(); messages.send(player, "crash.already-playing"); return; }
        if (!economy.has(player, bet)) { messages.send(player, "crash.insufficient-funds", Map.of("bet", money(bet))); sounds.play(player, "error"); return; }
        EconomyResponse response = economy.withdrawPlayer(player, bet);
        if (!response.transactionSuccess()) { messages.send(player, "crash.payment-failed"); sounds.play(player, "error"); return; }
        accounts.recordIncome(venue, player, bet, "crash-bet");
        player.closeInventory(); removeGuide(key(venue, machine)); removeIdleDisplay(key(venue, machine));
        TextDisplay display = createDisplay(venue, machine, runningText(100, bet));
        if (display == null) {
            refund(player, venue, bet, "crash-display-refund");
            messages.send(player, "crash.display-failed"); sounds.play(player, "error"); return;
        }
        int crashAt = drawCrashMultiplier(venue);
        GameSession session = new GameSession(player, venue, machine, bet, crashAt, display);
        sessions.put(key(venue, machine), session); playerSessions.put(player.getUniqueId(), key(venue, machine));
        activityListener.onGameStarted(player, venue, bet);
        messages.send(player, "crash.started", Map.of("bet", money(bet)));
        player.sendTitle(messages.text("crash.start-title"), messages.text("crash.start-subtitle", Map.of("bet", money(bet))), 5, 35, 5);
        sounds.play(player, "crash-start");
    }

    private void advance(GameSession session) {
        if (sessions.get(key(session.venue(), session.machine())) != session) return;
        Player player = session.player();
        if (!player.isOnline() || !accounts.isOpen(session.venue()) || !admissionChecker.test(player, session.venue())) {
            bust(session, false); return;
        }
        if (!isSeated(player, session.machine())) { forfeitByLeavingSeat(session); return; }
        int maximum = maxMultiplier();
        double configuredGrowth = switch (stage(session.multiplierCenti())) {
            case SAFE, WARNING -> plugin.getConfig().getDouble("crash.growth-centi-per-update", 1.0 / 3.0);
            case CASHOUT -> plugin.getConfig().getDouble("crash.cashout-growth-centi-per-update", 2.0 / 3.0);
            case EXTREME -> plugin.getConfig().getDouble("crash.extreme-growth-centi-per-update", 1.0);
        };
        double progress = Math.max(0.01, configuredGrowth)
                * Math.pow(session.multiplierCenti() / 100.0, Math.max(0, plugin.getConfig().getDouble("crash.growth-acceleration", 0.35)));
        session.growthRemainder(session.growthRemainder() + progress);
        int increment = (int) Math.floor(session.growthRemainder());
        if (increment <= 0) {
            progressEffects(session, session.multiplierCenti());
            return;
        }
        session.growthRemainder(session.growthRemainder() - increment);
        int next = Math.min(maximum, session.multiplierCenti() + increment);
        if (next >= session.crashAtCenti()) { session.multiplierCenti(session.crashAtCenti()); bust(session, true); return; }
        session.multiplierCenti(next);
        session.display().setText(runningText(next, session.bet()));
        progressEffects(session, next);
        if (next >= maximum) cashOut(session, true);
    }

    private void cashOut(GameSession session, boolean forced) {
        if (sessions.get(key(session.venue(), session.machine())) != session) return;
        int minimum = Math.max(100, plugin.getConfig().getInt("crash.minimum-cashout-centi", 120));
        if (!forced && session.multiplierCenti() < minimum) {
            messages.send(session.player(), "crash.too-early", Map.of("minimum", multiplier(minimum)));
            sounds.play(session.player(), "error"); return;
        }
        long payout = payout(session.bet(), session.multiplierCenti());
        EconomyResponse response = depositWithFallback(session.player(), payout);
        if (!response.transactionSuccess()) {
            queuePayout(session.player(), payout);
            messages.send(session.player(), "crash.payout-queued", Map.of("payout", money(payout)));
        }
        accounts.recordExpense(session.venue(), session.player(), payout, "crash-payout");
        if (payout > session.bet()) houseLossListener.accept(session.venue(), payout - session.bet());
        Map<String, Object> values = Map.of("payout", money(payout), "multiplier", multiplier(session.multiplierCenti()));
        messages.send(session.player(), forced ? "crash.forced-cashout" : "crash.cashout", values);
        session.player().sendTitle(messages.text("crash.cashout-title", values), messages.text("crash.cashout-subtitle", values), 5, 55, 15);
        sounds.play(session.player(), "crash-cashout");
        activityListener.onGameResult(session.player(), session.venue(), messages.text("scoreboard.result-crash-cashout", Map.of("multiplier", multiplier(session.multiplierCenti()))), payout);
        session.display().setText(messages.text("crash.display-cashout", values));
        finish(session, true);
    }

    private void bust(GameSession session, boolean announce) {
        if (sessions.get(key(session.venue(), session.machine())) != session) return;
        if (!announce) { finish(session, false); return; }
        Map<String, Object> values = Map.of("bet", money(session.bet()), "multiplier", multiplier(session.crashAtCenti()));
        if (session.player().isOnline()) {
            messages.send(session.player(), "crash.bust", values);
            session.player().sendTitle(messages.text("crash.bust-title", values), messages.text("crash.bust-subtitle", values), 0, 55, 15);
            sounds.play(session.player(), "crash-bust");
            spawnSideParticles(session.display(), Particle.EXPLOSION, 1, 0.12, 0.10, 0.12, 0);
        }
        session.display().setText(messages.text("crash.display-bust", values));
        activityListener.onGameResult(session.player(), session.venue(), messages.text("scoreboard.result-crash-bust", Map.of("multiplier", multiplier(session.crashAtCenti()))), 0);
        finish(session, true);
    }

    private void forfeitByLeavingSeat(GameSession session) {
        if (sessions.get(key(session.venue(), session.machine())) != session) return;
        messages.send(session.player(), "crash.left-seat", Map.of("bet", money(session.bet())));
        sounds.play(session.player(), "error");
        activityListener.onGameResult(session.player(), session.venue(), messages.text("scoreboard.result-crash-left-seat"), 0);
        finish(session, false);
    }

    private void finish(GameSession session, boolean linger) {
        String sessionKey = key(session.venue(), session.machine());
        sessions.remove(sessionKey); playerSessions.remove(session.player().getUniqueId());
        completionListener.accept(session.player(), session.venue());
        if (!linger || session.display() == null || !session.display().isValid()) {
            if (session.display() != null && session.display().isValid()) session.display().remove();
            return;
        }
        resultDisplays.put(sessionKey, session.display());
        long delay = Math.max(10, plugin.getConfig().getLong("crash.result-display-ticks", 40));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (resultDisplays.get(sessionKey) != session.display()) return;
            resultDisplays.remove(sessionKey);
            if (session.display().isValid()) session.display().remove();
            if (!resetting) refreshIdleDisplays();
        }, delay);
    }

    private int drawCrashMultiplier(Venue venue) {
        double rtp = effectiveRtp(venue);
        int safeUntil = Math.max(100, plugin.getConfig().getInt("crash.safe-until-centi", 108));
        int cashout = Math.max(safeUntil + 2, plugin.getConfig().getInt("crash.minimum-cashout-centi", 120));
        double surviveCashout = Math.max(0, Math.min(1, rtp / (cashout / 100.0)));
        if (random.nextDouble() >= surviveCashout) {
            int dangerousValues = Math.max(1, cashout - safeUntil - 1);
            return safeUntil + 1 + random.nextInt(dangerousValues);
        }
        double denominator = Math.max(0.000000001, 1.0 - random.nextDouble());
        double rawCenti = cashout / denominator;
        long centi = (long) Math.floor(rawCenti) + 1;
        int maximum = maxMultiplier();
        return (int) Math.max((long) cashout + 1, Math.min((long) maximum + 1, centi));
    }

    private void progressEffects(GameSession session, int centi) {
        Player player = session.player(); Stage stage = stage(centi);
        if (stage != Stage.SAFE && !session.dangerAnnounced()) {
            session.dangerAnnounced(true);
            player.sendTitle(messages.text("crash.danger-title"), messages.text("crash.danger-subtitle"), 0, 25, 5);
            sounds.play(player, "crash-warning");
        }
        int cashout = Math.max(100, plugin.getConfig().getInt("crash.minimum-cashout-centi", 120));
        if (centi >= cashout && !session.cashoutAnnounced()) {
            session.cashoutAnnounced(true);
            player.sendTitle(messages.text("crash.unlocked-title"), messages.text("crash.unlocked-subtitle", Map.of("minimum", multiplier(cashout))), 0, 30, 5);
            sounds.play(player, "crash-unlocked");
        }
        int interval = switch (stage) { case SAFE -> 10; case WARNING -> 4; case CASHOUT -> 6; case EXTREME -> 3; };
        if (ticks % interval == 0) sounds.play(player, switch (stage) {
            case SAFE -> "crash-tick"; case WARNING -> "crash-warning-tick"; case CASHOUT -> "crash-danger-tick"; case EXTREME -> "crash-extreme-tick";
        });
        if (stage == Stage.WARNING && ticks % 6 == 0) spawnSideParticles(session.display(), Particle.SMOKE, 1, 0.08, 0.04, 0.08, 0);
        else if (stage == Stage.CASHOUT && ticks % 6 == 0) spawnSideParticles(session.display(), Particle.END_ROD, 1, 0.10, 0.05, 0.10, 0);
        else if (stage == Stage.EXTREME && ticks % 4 == 0) {
            spawnSideParticles(session.display(), Particle.FLAME, 1, 0.10, 0.08, 0.10, 0.01);
            spawnSideParticles(session.display(), Particle.LARGE_SMOKE, 1, 0.08, 0.05, 0.08, 0);
        }
    }

    private void spawnSideParticles(TextDisplay display, Particle particle, int count,
                                    double offsetX, double offsetY, double offsetZ, double extra) {
        double distance = Math.max(0.2, plugin.getConfig().getDouble("crash.particle-side-offset", 1.0));
        double yaw = Math.toRadians(display.getYaw());
        double sideX = Math.cos(yaw) * distance;
        double sideZ = Math.sin(yaw) * distance;
        Location center = display.getLocation();
        Location left = center.clone().add(sideX, 0, sideZ);
        Location right = center.clone().subtract(sideX, 0, sideZ);
        center.getWorld().spawnParticle(particle, left, count, offsetX, offsetY, offsetZ, extra);
        center.getWorld().spawnParticle(particle, right, count, offsetX, offsetY, offsetZ, extra);
    }

    private double effectiveRtp(Venue venue) {
        double base = Math.max(0.50, Math.min(1.20, plugin.getConfig().getDouble("crash.rtp", 1.0)));
        if (!plugin.getConfig().getBoolean("crash.dynamic-odds.enabled", true)) return base;
        CasinoAccountManager.AccountSnapshot account = accounts.snapshot(venue);
        double income = Math.max(0, (double) account.totalIncome()), expense = Math.max(0, (double) account.totalExpense());
        double turnover = income + expense;
        if (!Double.isFinite(turnover) || turnover <= 0) return base;
        double minimum = Math.max(0, plugin.getConfig().getDouble("crash.dynamic-odds.minimum-turnover", 100_000));
        double strength = minimum <= 0 ? 1 : Math.min(1, turnover / minimum);
        double margin = (income - expense) / turnover;
        double fullMargin = Math.max(0.0001, plugin.getConfig().getDouble("crash.dynamic-odds.full-adjustment-profit-margin", 0.20));
        double direction = Math.max(-1, Math.min(1, margin / fullMargin));
        double maximum = Math.max(0, Math.min(0.20, plugin.getConfig().getDouble("crash.dynamic-odds.maximum-rtp-adjustment", 0.04)));
        return Math.max(0.50, Math.min(1.20, base + direction * maximum * strength));
    }

    public boolean isAutoLinked(Venue venue, CrashMachine machine) { return machine.complete() && nearestButton(venue, machine) != null; }

    public void sendLinkStatus(CommandSender sender, Venue venue, CrashMachine machine) {
        Block button = nearestButton(venue, machine);
        messages.send(sender, button == null ? "crash-admin.link-missing" : "crash-admin.link-success", Map.of("button", location(button)));
    }

    public void refreshDisplays() {
        for (TextDisplay display : idleDisplays.values()) if (display != null && display.isValid()) display.remove();
        idleDisplays.clear();
        for (GuideDisplay guide : guideDisplays.values()) if (guide.display() != null && guide.display().isValid()) guide.display().remove();
        guideDisplays.clear();
    }

    private void showGuide(Player player, Venue venue, CrashMachine machine) {
        String machineKey = key(venue, machine);
        if (!player.isOnline() || sessions.containsKey(machineKey) || !isSeated(player, machine) || !admissionChecker.test(player, venue) || !accounts.isOpen(venue)) return;
        if (!isAutoLinked(venue, machine)) { messages.send(player, "crash.auto-link-missing"); sounds.play(player, "error"); return; }
        removeIdleDisplay(machineKey); removeGuide(machineKey);
        TextDisplay display = createDisplay(venue, machine, messages.text("crash.guide-display"), plugin.getConfig().getDouble("crash.guide-display-scale", 0.9));
        if (display != null) guideDisplays.put(machineKey, new GuideDisplay(player.getUniqueId(), machine, display));
        messages.send(player, "crash.sit-guide"); sounds.play(player, "crash-ready");
    }

    private void refreshIdleDisplays() {
        Set<String> current = new HashSet<>();
        for (Venue venue : venues.all()) for (CrashMachine machine : venue.crashMachines().values()) {
            String machineKey = key(venue, machine); current.add(machineKey);
            boolean show = !sessions.containsKey(machineKey) && !resultDisplays.containsKey(machineKey)
                    && !guideDisplays.containsKey(machineKey) && isAutoLinked(venue, machine) && hasNearbyPlayer(machine);
            if (!show) { removeIdleDisplay(machineKey); continue; }
            if (!idleDisplays.containsKey(machineKey)) {
                TextDisplay display = createDisplay(venue, machine, idleText(), plugin.getConfig().getDouble("crash.idle-display-scale", 1.0));
                if (display != null) idleDisplays.put(machineKey, display);
            } else idleDisplays.get(machineKey).setText(idleText());
        }
        for (String key : new ArrayList<>(idleDisplays.keySet())) if (!current.contains(key)) removeIdleDisplay(key);
    }

    private TextDisplay createDisplay(Venue venue, CrashMachine machine, String text) {
        return createDisplay(venue, machine, text, plugin.getConfig().getDouble("crash.display-scale", 1.25));
    }

    private TextDisplay createDisplay(Venue venue, CrashMachine machine, String text, double configuredScale) {
        Block button = nearestButton(venue, machine);
        if (button == null || machine.chair() == null) return null;
        BlockPosition chair = machine.chair();
        int dx = Integer.compare(button.getX(), chair.x()), dz = Integer.compare(button.getZ(), chair.z());
        Location location = button.getLocation().add(0.5 + dx, plugin.getConfig().getDouble("crash.display-y-offset", 0.22), 0.5 + dz);
        TextDisplay display = button.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setText(text); entity.setBillboard(Display.Billboard.FIXED); entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setShadowed(true); entity.setSeeThrough(false); entity.setLineWidth(120); entity.setPersistent(false);
            entity.setViewRange((float) Math.max(1.0, plugin.getConfig().getDouble("crash.display-view-range", 2.0)));
            float scale = (float) Math.max(0.25, configuredScale);
            var transformation = entity.getTransformation(); transformation.getScale().set(scale, scale, scale); entity.setTransformation(transformation);
        });
        double toChairX = chair.x() + 0.5 - location.getX(), toChairZ = chair.z() + 0.5 - location.getZ();
        display.setRotation((float) Math.toDegrees(Math.atan2(-toChairX, toChairZ)), (float) plugin.getConfig().getDouble("crash.display-pitch", -10));
        return display;
    }

    private FoundMachine findByButton(Block block) {
        BlockPosition clicked = position(block);
        for (Venue venue : venues.all()) for (CrashMachine machine : venue.crashMachines().values()) {
            Block linked = nearestButton(venue, machine);
            if (linked != null && clicked.equals(position(linked))) return new FoundMachine(venue, machine);
        }
        return null;
    }

    private FoundMachine findByChair(Block block) {
        BlockPosition clicked = position(block);
        for (Venue venue : venues.all()) for (CrashMachine machine : venue.crashMachines().values()) if (clicked.equals(machine.chair())) return new FoundMachine(venue, machine);
        return null;
    }

    private Block nearestButton(Venue venue, CrashMachine machine) {
        BlockPosition chair = machine.chair(); if (chair == null) return null;
        World world = Bukkit.getWorld(chair.world()); if (world == null) return null;
        double radius = Math.max(1, plugin.getConfig().getDouble("crash.auto-link-radius", 4));
        int range = (int) Math.ceil(radius); double bestDistance = Double.MAX_VALUE; Block best = null;
        for (int x = chair.x() - range; x <= chair.x() + range; x++) for (int y = chair.y() - range; y <= chair.y() + range; y++) for (int z = chair.z() - range; z <= chair.z() + range; z++) {
            if (!straight(chair, x, z) || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
            double distance = distanceSquared(chair, x, y, z);
            if (distance > radius * radius || distance >= bestDistance) continue;
            Block block = world.getBlockAt(x, y, z);
            if (!isButton(block.getType()) || !venue.contains(block.getLocation()) || !ownedBy(venue, machine, block)) continue;
            best = block; bestDistance = distance;
        }
        return best;
    }

    private boolean ownedBy(Venue venue, CrashMachine owner, Block block) {
        double ownerDistance = distanceSquared(owner.chair(), block.getX(), block.getY(), block.getZ());
        for (CrashMachine other : venue.crashMachines().values()) {
            if (other == owner || other.chair() == null || !other.chair().world().equals(block.getWorld().getName())) continue;
            double otherDistance = distanceSquared(other.chair(), block.getX(), block.getY(), block.getZ());
            if (otherDistance < ownerDistance || (otherDistance == ownerDistance && other.id().compareToIgnoreCase(owner.id()) < 0)) return false;
        }
        return true;
    }

    private boolean straight(BlockPosition chair, int x, int z) { return (x == chair.x()) != (z == chair.z()); }
    private boolean isButton(Material material) { return material.name().endsWith("_BUTTON"); }
    private boolean isSeated(Player player, CrashMachine machine) { Seat seat = GSitAPI.getSeatByEntity(player); return seat != null && position(seat.getBlock()).equals(machine.chair()); }
    private boolean hasNearbyPlayer(CrashMachine machine) {
        if (machine.chair() == null) return false; World world = Bukkit.getWorld(machine.chair().world()); if (world == null) return false;
        Location center = new Location(world, machine.chair().x() + 0.5, machine.chair().y() + 0.5, machine.chair().z() + 0.5);
        double range = Math.max(1, plugin.getConfig().getDouble("crash.proximity-distance", 30));
        return world.getPlayers().stream().anyMatch(player -> player.getLocation().distanceSquared(center) <= range * range);
    }

    private List<Long> bets() {
        List<Long> values = plugin.getConfig().getLongList("crash.bets").stream().filter(value -> value > 0).distinct().limit(3).toList();
        return values.size() == 3 ? values : List.of(100L, 1000L, 10000L);
    }

    private String idleText() {
        List<String> variants = new ArrayList<>();
        variants.add(messages.text("crash.idle-display"));
        variants.addAll(messages.lines("crash.idle-displays", Map.of(
                "bets", bets().stream().map(this::money).reduce((first, second) -> first + " / " + second).orElse(""),
                "minimum", multiplier(Math.max(100, plugin.getConfig().getInt("crash.minimum-cashout-centi", 120)))
        )));
        long interval = Math.max(20, plugin.getConfig().getLong("crash.idle-display-cycle-ticks", 60));
        return variants.get((int) ((ticks / interval) % variants.size()));
    }

    private ItemStack item(Material material, String name) { ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(name); item.setItemMeta(meta); return item; }
    private String runningText(int centi, long bet) {
        Stage stage = stage(centi);
        return messages.text("crash.running-display", Map.of(
                "multiplier", multiplier(centi), "payout", money(payout(bet, centi)),
                "color", stage.color));
    }
    private Stage stage(int centi) {
        int safe = Math.max(100, plugin.getConfig().getInt("crash.safe-until-centi", 108));
        int cashout = Math.max(safe + 2, plugin.getConfig().getInt("crash.minimum-cashout-centi", 120));
        int extreme = Math.max(cashout + 1, plugin.getConfig().getInt("crash.extreme-from-centi", 200));
        if (centi <= safe) return Stage.SAFE;
        if (centi < cashout) return Stage.WARNING;
        return centi < extreme ? Stage.CASHOUT : Stage.EXTREME;
    }
    private String multiplier(int centi) { return String.format(Locale.ROOT, "%.2f", centi / 100.0); }
    private int maxMultiplier() { return Math.max(120, plugin.getConfig().getInt("crash.maximum-multiplier-centi", 10000)); }
    private long payout(long bet, int centi) {
        BigInteger value = BigInteger.valueOf(bet).multiply(BigInteger.valueOf(centi)).divide(BigInteger.valueOf(100));
        long cap = Math.max(1, plugin.getConfig().getLong("crash.max-payout", 100_000_000));
        return value.compareTo(BigInteger.valueOf(cap)) >= 0 ? cap : value.longValue();
    }
    private String money(long amount) { return CurrencyFormatter.format(economy, amount); }
    private String key(Venue venue, CrashMachine machine) { return venue.id().toLowerCase(Locale.ROOT) + ":" + machine.id().toLowerCase(Locale.ROOT); }
    private BlockPosition position(Block block) { return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()); }
    private double distanceSquared(BlockPosition from, int x, int y, int z) { double dx = from.x() - x, dy = from.y() - y, dz = from.z() - z; return dx * dx + dy * dy + dz * dz; }
    private String location(Block block) { return block == null ? "未検出" : block.getWorld().getName() + " " + block.getX() + " " + block.getY() + " " + block.getZ(); }

    private void removeGuide(String key) { GuideDisplay guide = guideDisplays.remove(key); if (guide != null && guide.display().isValid()) guide.display().remove(); }
    private void removeIdleDisplay(String key) { TextDisplay display = idleDisplays.remove(key); if (display != null && display.isValid()) display.remove(); }

    private EconomyResponse depositWithFallback(Player player, long amount) {
        if (!economy.hasAccount(player)) economy.createPlayerAccount(player);
        double before = economy.getBalance(player); EconomyResponse response = economy.depositPlayer(player, amount);
        double tolerance = Math.max(0.000001, amount * 0.000000001);
        if (response.transactionSuccess() || economy.getBalance(player) - before >= amount - tolerance) return response.transactionSuccess()
                ? response : new EconomyResponse(amount, economy.getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
        return response;
    }

    private void refund(Player player, Venue venue, long amount, String category) {
        EconomyResponse response = depositWithFallback(player, amount);
        if (!response.transactionSuccess()) queuePayout(player, amount);
        accounts.recordExpense(venue, player, amount, category);
    }

    private void queuePayout(Player player, long amount) {
        PendingPayout current = pendingPayouts.get(player.getUniqueId()); long total;
        try { total = Math.addExact(current == null ? 0 : current.amount(), amount); } catch (ArithmeticException exception) { total = Long.MAX_VALUE; }
        pendingPayouts.put(player.getUniqueId(), new PendingPayout(player.getName(), total)); savePendingPayouts();
    }

    private void retryPendingPayouts() {
        for (var entry : new ArrayList<>(pendingPayouts.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey()); if (player == null || !player.isOnline()) continue;
            EconomyResponse response = depositWithFallback(player, entry.getValue().amount()); if (!response.transactionSuccess()) continue;
            long payout = entry.getValue().amount(); pendingPayouts.remove(entry.getKey()); savePendingPayouts();
            messages.send(player, "crash.pending-payout-paid", Map.of("payout", money(payout)));
        }
    }

    private void loadPendingPayouts() {
        if (!pendingFile.exists()) return; YamlConfiguration data = YamlConfiguration.loadConfiguration(pendingFile);
        ConfigurationSection players = data.getConfigurationSection("players"); if (players == null) return;
        for (String raw : players.getKeys(false)) try {
            UUID uuid = UUID.fromString(raw); long amount = players.getLong(raw + ".amount");
            if (amount > 0) pendingPayouts.put(uuid, new PendingPayout(players.getString(raw + ".name", raw), amount));
        } catch (IllegalArgumentException ignored) {}
    }

    private void savePendingPayouts() {
        YamlConfiguration data = new YamlConfiguration();
        for (var entry : pendingPayouts.entrySet()) { String path = "players." + entry.getKey(); data.set(path + ".name", entry.getValue().name()); data.set(path + ".amount", entry.getValue().amount()); }
        File temporary = new File(plugin.getDataFolder(), "pending-crash-payouts.yml.tmp");
        try {
            data.save(temporary);
            try { Files.move(temporary.toPath(), pendingFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary.toPath(), pendingFile.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) { plugin.getLogger().severe("クラッシュ保留払戻しの保存に失敗しました: " + exception.getMessage()); }
    }

    public void resetRuntime() {
        resetting = true;
        for (GameSession session : new ArrayList<>(sessions.values())) {
            refund(session.player(), session.venue(), session.bet(), "crash-disable-refund");
            if (session.player().isOnline()) messages.send(session.player(), "crash.refund-on-disable", Map.of("bet", money(session.bet())));
            finish(session, false);
        }
        sessions.clear(); playerSessions.clear(); menus.clear();
        for (TextDisplay display : idleDisplays.values()) if (display != null && display.isValid()) display.remove(); idleDisplays.clear();
        for (GuideDisplay guide : guideDisplays.values()) if (guide.display().isValid()) guide.display().remove(); guideDisplays.clear();
        for (TextDisplay display : resultDisplays.values()) if (display != null && display.isValid()) display.remove(); resultDisplays.clear();
        resetting = false;
    }

    private record FoundMachine(Venue venue, CrashMachine machine) {}
    private record MenuContext(Venue venue, CrashMachine machine, Inventory inventory) {}
    private record GuideDisplay(UUID playerId, CrashMachine machine, TextDisplay display) {}
    private record PendingPayout(String name, long amount) {}
    private enum Stage {
        SAFE("&a"), WARNING("&e"), CASHOUT("&6"), EXTREME("&c");
        private final String color;
        Stage(String color) { this.color = color; }
    }
    private static final class GameSession {
        private final Player player; private final Venue venue; private final CrashMachine machine; private final long bet; private final int crashAtCenti; private final TextDisplay display;
        private int multiplierCenti = 100;
        private double growthRemainder;
        private boolean dangerAnnounced; private boolean cashoutAnnounced;
        private GameSession(Player player, Venue venue, CrashMachine machine, long bet, int crashAtCenti, TextDisplay display) {
            this.player = player; this.venue = venue; this.machine = machine; this.bet = bet; this.crashAtCenti = crashAtCenti; this.display = display;
        }
        Player player() { return player; } Venue venue() { return venue; } CrashMachine machine() { return machine; } long bet() { return bet; }
        int crashAtCenti() { return crashAtCenti; } TextDisplay display() { return display; }
        int multiplierCenti() { return multiplierCenti; } void multiplierCenti(int value) { multiplierCenti = value; }
        double growthRemainder() { return growthRemainder; } void growthRemainder(double value) { growthRemainder = value; }
        boolean dangerAnnounced() { return dangerAnnounced; } void dangerAnnounced(boolean value) { dangerAnnounced = value; }
        boolean cashoutAnnounced() { return cashoutAnnounced; } void cashoutAnnounced(boolean value) { cashoutAnnounced = value; }
    }
}
