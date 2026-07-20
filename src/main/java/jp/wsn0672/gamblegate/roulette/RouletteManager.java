package jp.wsn0672.gamblegate.roulette;

import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.account.CasinoAccountManager;
import jp.wsn0672.gamblegate.config.CurrencyFormatter;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.SoundService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.RouletteMachine;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
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
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

public final class RouletteManager implements Listener, Runnable {
    private static final int[] NUMBER_SLOTS = {2, 3, 4, 5, 6, 16, 26, 34, 42, 41, 40, 39, 38, 28, 18, 10};
    private static final int[] BET_SLOTS = {46, 48, 50};
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final SoundService sounds;
    private final Economy economy;
    private final CasinoAccountManager accounts;
    private final Random random = new Random();
    private final Map<String, UUID> occupied = new HashMap<>();
    private final Map<String, Deque<QueuedPlayer>> queues = new HashMap<>();
    private final Map<UUID, String> playerQueues = new HashMap<>();
    private final Map<UUID, MenuContext> menus = new HashMap<>();
    private final Map<String, GameSession> sessions = new HashMap<>();
    private final Map<UUID, String> playerSessions = new HashMap<>();
    private final Map<UUID, PendingPayout> pendingPayouts = new HashMap<>();
    private final Map<String, TextDisplay> startDisplays = new HashMap<>();
    private final Map<String, List<TextDisplay>> numberDisplays = new HashMap<>();
    private final Map<String, TextDisplay> wagerDisplays = new HashMap<>();
    private final File pendingFile;
    private BiPredicate<Player, Venue> admissionChecker = (player, venue) -> false;
    private BiConsumer<Player, Venue> completionListener = (player, venue) -> {};
    private BiConsumer<Venue, Long> houseLossListener = (venue, profit) -> {};
    private SlotActivityListener activityListener = SlotActivityListener.NONE;
    private long ticks;
    private boolean resetting;

    public RouletteManager(GambleGatePlugin plugin, VenueRepository venues, MessageService messages, SoundService sounds,
                           Economy economy, CasinoAccountManager accounts) {
        this.plugin = plugin; this.venues = venues; this.messages = messages; this.sounds = sounds;
        this.economy = economy; this.accounts = accounts;
        pendingFile = new File(plugin.getDataFolder(), "pending-roulette-payouts.yml");
        loadPendingPayouts();
    }

    public void setAdmissionChecker(BiPredicate<Player, Venue> checker) { admissionChecker = checker; }
    public void setCompletionListener(BiConsumer<Player, Venue> listener) { completionListener = listener == null ? (p, v) -> {} : listener; }
    public void setHouseLossListener(BiConsumer<Venue, Long> listener) { houseLossListener = listener == null ? (venue, profit) -> {} : listener; }
    public void setActivityListener(SlotActivityListener listener) { activityListener = listener == null ? SlotActivityListener.NONE : listener; }

    public boolean isPlaying(Venue venue, RouletteMachine machine) {
        String machineKey = key(venue, machine);
        return occupied.containsKey(machineKey) || queueSize(machineKey) > 0;
    }
    public boolean isPlaying(Player player, Venue venue) {
        String venuePrefix = venue.id().toLowerCase(Locale.ROOT) + ":";
        if (occupied.entrySet().stream().anyMatch(entry -> entry.getValue().equals(player.getUniqueId())
                && entry.getKey().startsWith(venuePrefix))) return true;
        String sessionKey = playerSessions.get(player.getUniqueId());
        if (sessionKey != null) {
            GameSession session = sessions.get(sessionKey);
            if (session != null && session.venue().id().equalsIgnoreCase(venue.id())) return true;
        }
        MenuContext menu = menus.get(player.getUniqueId());
        return menu != null && menu.venue().id().equalsIgnoreCase(venue.id());
    }

    @Override
    public void run() {
        ticks++;
        int retry = Math.max(20, plugin.getConfig().getInt("roulette.pending-payout-retry-ticks", 200));
        if (ticks % retry == 0) retryPendingPayouts();
        if (ticks % 20 == 0) {
            cleanupQueues();
            expireInactiveMenus();
            refreshDisplays();
        }
        for (GameSession session : new ArrayList<>(sessions.values())) advance(session);
    }

    public void refreshDisplays() {
        for (Venue venue : venues.all()) for (RouletteMachine machine : venue.rouletteMachines().values()) {
            String machineKey = key(venue, machine);
            if (!machine.complete()) { removeDisplays(machineKey); continue; }
            TextDisplay start = startDisplays.get(machineKey);
            if (start == null || !start.isValid()) {
                start = createStartDisplay(venue, machine);
                if (start != null) startDisplays.put(machineKey, start);
            } else start.setText(startDisplayText(venue, machine));
            List<TextDisplay> numbers = numberDisplays.get(machineKey);
            boolean invalid = numbers == null || numbers.size() != 16 || numbers.stream().anyMatch(display -> display == null || !display.isValid());
            if (invalid) {
                if (numbers != null) numbers.forEach(this::removeDisplay);
                List<TextDisplay> created = createNumberDisplays(machine);
                if (created.size() == 16) numberDisplays.put(machineKey, created);
                else { created.forEach(this::removeDisplay); numberDisplays.remove(machineKey); }
            }
        }
        startDisplays.keySet().removeIf(machineKey -> {
            if (machineExists(machineKey)) return false;
            removeDisplay(startDisplays.get(machineKey)); return true;
        });
        numberDisplays.keySet().removeIf(machineKey -> {
            if (machineExists(machineKey)) return false;
            numberDisplays.get(machineKey).forEach(this::removeDisplay); return true;
        });
        for (MenuContext context : menus.values()) updateWagerDisplay(context.venue(), context.machine(),
                Bukkit.getPlayer(context.playerId()), context.choice(), context.bet());
        for (GameSession session : sessions.values()) updateWagerDisplay(session.venue(), session.machine(),
                session.player(), session.choice(), session.bet());
        wagerDisplays.keySet().removeIf(machineKey -> {
            if (machineExists(machineKey) && occupied.containsKey(machineKey)) return false;
            removeDisplay(wagerDisplays.get(machineKey)); return true;
        });
    }

    private TextDisplay createStartDisplay(Venue venue, RouletteMachine machine) {
        BlockPosition button = machine.playButton();
        World world = button == null ? null : Bukkit.getWorld(button.world());
        if (world == null || !world.isChunkLoaded(button.x() >> 4, button.z() >> 4)) return null;
        double offset = plugin.getConfig().getDouble("roulette.start-display-y-offset", 1.15);
        Location location = new Location(world, button.x() + 0.5, button.y() + offset, button.z() + 0.5);
        return createTextDisplay(location, startDisplayText(venue, machine),
                (float) plugin.getConfig().getDouble("roulette.start-display-scale", 0.9));
    }

    private String startDisplayText(Venue venue, RouletteMachine machine) {
        String machineKey = key(venue, machine);
        UUID currentId = occupied.get(machineKey);
        if (currentId == null) return messages.text("roulette.start-display");
        Player current = Bukkit.getPlayer(currentId);
        String playerName = current == null ? messages.text("roulette.queue-player-unknown") : current.getName();
        String status = messages.text("roulette.start-display-busy", Map.of(
                "player", playerName,
                "count", queueSize(machineKey) + 1
        ));
        return status + "\n" + messages.text("roulette.start-display-action");
    }

    private List<TextDisplay> createNumberDisplays(RouletteMachine machine) {
        List<TextDisplay> displays = new ArrayList<>();
        double offset = plugin.getConfig().getDouble("roulette.number-display-y-offset", 1.48);
        float scale = (float) plugin.getConfig().getDouble("roulette.number-display-scale", 0.72);
        for (int i = 0; i < machine.pockets().size(); i++) {
            BlockPosition pocket = machine.pockets().get(i);
            World world = Bukkit.getWorld(pocket.world());
            if (world == null || !world.isChunkLoaded(pocket.x() >> 4, pocket.z() >> 4)) return displays;
            Location location = new Location(world, pocket.x() + 0.5, pocket.y() + offset, pocket.z() + 0.5);
            String path = i % 2 == 0 ? "roulette.number-display-red" : "roulette.number-display-black";
            TextDisplay display = createTextDisplay(location, messages.text(path, Map.of("number", i + 1)), scale);
            if (display == null) return displays;
            displays.add(display);
        }
        return displays;
    }

    private TextDisplay createTextDisplay(Location location, String text, float scale) {
        try {
            return location.getWorld().spawn(location, TextDisplay.class, display -> {
                display.setText(text);
                display.setBillboard(Display.Billboard.CENTER);
                display.setSeeThrough(false);
                display.setShadowed(true);
                display.setGlowing(false);
                display.setPersistent(false);
                display.setViewRange((float) Math.max(0.1, plugin.getConfig().getDouble("roulette.display-view-range", 0.65)));
                float safeScale = Math.max(0.1f, Math.min(3, scale));
                display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(safeScale, safeScale, safeScale), new AxisAngle4f()));
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("ルーレットのホログラムを生成できませんでした: " + exception.getMessage());
            return null;
        }
    }

    private boolean machineExists(String machineKey) {
        for (Venue venue : venues.all()) for (RouletteMachine machine : venue.rouletteMachines().values())
            if (key(venue, machine).equals(machineKey) && machine.complete()) return true;
        return false;
    }

    private void removeDisplays(String machineKey) {
        removeDisplay(startDisplays.remove(machineKey));
        List<TextDisplay> numbers = numberDisplays.remove(machineKey);
        if (numbers != null) numbers.forEach(this::removeDisplay);
    }

    private void removeDisplay(TextDisplay display) { if (display != null && display.isValid()) display.remove(); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        FoundMachine found = findByButton(event.getClickedBlock());
        if (found == null) return;
        event.setCancelled(true);
        enter(event.getPlayer(), found.venue(), found.machine());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MenuContext context = menus.get(player.getUniqueId());
        if (context == null || !context.inventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        int raw = event.getRawSlot();
        for (int i = 0; i < NUMBER_SLOTS.length; i++) if (raw == NUMBER_SLOTS[i]) {
            context.touch(ticks); context.choice(exact(i + 1)); redraw(context); sounds.play(player, "roulette-select"); return;
        }
        WagerChoice choice = switch (raw) {
            case 20 -> half(ChoiceKind.RED);
            case 21 -> half(ChoiceKind.BLACK);
            case 22 -> half(ChoiceKind.ODD);
            case 23 -> half(ChoiceKind.EVEN);
            case 24 -> half(ChoiceKind.LOW);
            case 29 -> half(ChoiceKind.HIGH);
            case 30 -> quarter(1);
            case 31 -> quarter(2);
            case 32 -> quarter(3);
            case 33 -> quarter(4);
            default -> null;
        };
        if (choice != null) { context.touch(ticks); context.choice(choice); redraw(context); sounds.play(player, "roulette-select"); return; }
        List<Long> bets = bets();
        for (int i = 0; i < BET_SLOTS.length && i < bets.size(); i++) if (raw == BET_SLOTS[i]) {
            context.touch(ticks); context.bet(bets.get(i)); redraw(context); sounds.play(player, "roulette-select"); return;
        }
        if (raw == 52) {
            context.touch(ticks);
            if (context.choice() == null) { messages.send(player, "roulette.choose-first"); sounds.play(player, "error"); return; }
            start(context);
        } else if (raw == 53) player.closeInventory();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        MenuContext context = menus.get(event.getPlayer().getUniqueId());
        if (context == null || !context.inventory().equals(event.getInventory())) return;
        menus.remove(event.getPlayer().getUniqueId());
        if (resetting) return;
        Player player = (Player) event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (accounts.isOpen(context.venue()) && admissionChecker.test(player, context.venue()))
                returnPlayer(player, context.machine());
            completionListener.accept(player, context.venue());
            releaseAndPromote(context.venue(), context.machine(), context.playerId());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        FoundMachine found = findRegistered(event.getBlock());
        if (found == null) return;
        event.setCancelled(true); messages.send(event.getPlayer(), "roulette.registered-block"); sounds.play(event.getPlayer(), "error");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        MenuContext menu = menus.remove(event.getPlayer().getUniqueId());
        if (menu != null) releaseAndPromote(menu.venue(), menu.machine(), menu.playerId());
        removeFromQueue(event.getPlayer().getUniqueId(), false);
        String sessionKey = playerSessions.get(event.getPlayer().getUniqueId());
        GameSession session = sessionKey == null ? null : sessions.get(sessionKey);
        if (session != null) finish(session, false);
        else if (menu == null) releaseTransitioningPlayer(event.getPlayer().getUniqueId());
    }

    private void releaseTransitioningPlayer(UUID playerId) {
        for (Venue venue : venues.all()) for (RouletteMachine machine : venue.rouletteMachines().values()) {
            if (!playerId.equals(occupied.get(key(venue, machine)))) continue;
            releaseAndPromote(venue, machine, playerId);
            return;
        }
    }

    private void enter(Player player, Venue venue, RouletteMachine machine) {
        if (!machine.complete()) { messages.send(player, "roulette.incomplete"); sounds.play(player, "error"); return; }
        if (!accounts.isOpen(venue)) { messages.send(player, "roulette.closed"); sounds.play(player, "error"); return; }
        if (!admissionChecker.test(player, venue)) { messages.send(player, "roulette.not-admitted"); sounds.play(player, "error"); return; }
        String machineKey = key(venue, machine);
        if (occupied.containsValue(player.getUniqueId()) || menus.containsKey(player.getUniqueId())
                || playerSessions.containsKey(player.getUniqueId())) {
            messages.send(player, "roulette.already-playing"); return;
        }
        String queuedMachine = playerQueues.get(player.getUniqueId());
        if (queuedMachine != null) {
            int position = queuePosition(queuedMachine, player.getUniqueId());
            messages.send(player, "roulette.queue-already", Map.of("position", Math.max(1, position)));
            return;
        }
        Location viewing = machine.viewingLocation().toBukkit();
        if (viewing == null || !player.teleport(viewing, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            messages.send(player, "roulette.teleport-failed"); sounds.play(player, "error"); return;
        }
        UUID current = occupied.putIfAbsent(machineKey, player.getUniqueId());
        if (current != null) {
            Deque<QueuedPlayer> queue = queues.computeIfAbsent(machineKey, ignored -> new ArrayDeque<>());
            queue.addLast(new QueuedPlayer(player.getUniqueId(), venue, machine));
            playerQueues.put(player.getUniqueId(), machineKey);
            int position = queue.size();
            messages.send(player, "roulette.queue-joined", Map.of("position", position, "count", position));
            player.sendTitle(messages.text("roulette.queue-title"),
                    messages.text("roulette.queue-subtitle", Map.of("position", position)), 5, 45, 10);
            sounds.play(player, "roulette-select");
            updateStartDisplay(venue, machine);
            return;
        }
        updateStartDisplay(venue, machine);
        scheduleMenuOpen(player, venue, machine, 10L);
    }

    private void scheduleMenuOpen(Player player, Venue venue, RouletteMachine machine, long delay) {
        String machineKey = key(venue, machine);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !admissionChecker.test(player, venue)) {
                releaseAndPromote(venue, machine, player.getUniqueId()); return;
            }
            if (!player.getUniqueId().equals(occupied.get(machineKey))) return;
            openMenu(player, venue, machine);
        }, Math.max(1, delay));
    }

    private void openMenu(Player player, Venue venue, RouletteMachine machine) {
        if (!player.getUniqueId().equals(occupied.get(key(venue, machine)))
                || menus.containsKey(player.getUniqueId()) || playerSessions.containsKey(player.getUniqueId())) return;
        Inventory inventory = Bukkit.createInventory(null, 54, messages.text("roulette.gui-title"));
        List<Long> bets = bets();
        MenuContext context = new MenuContext(player.getUniqueId(), venue, machine, inventory, bets.getFirst(), ticks);
        menus.put(player.getUniqueId(), context); redraw(context);
        player.openInventory(inventory); sounds.play(player, "gui-open");
        messages.send(player, "roulette.gui-opened");
    }

    private void redraw(MenuContext context) {
        Inventory inventory = context.inventory(); inventory.clear();
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        for (int i = 0; i < NUMBER_SLOTS.length; i++) {
            int number = i + 1; boolean selected = context.choice() != null && context.choice().kind() == ChoiceKind.EXACT && context.choice().option() == number;
            inventory.setItem(NUMBER_SLOTS[i], item(number % 2 == 1 ? Material.RED_CONCRETE : Material.BLACK_CONCRETE,
                    messages.text("roulette.number-name", Map.of("number", number)), choiceLore(context, exact(number)), selected));
        }
        putChoice(inventory, 20, Material.RED_DYE, context, half(ChoiceKind.RED));
        putChoice(inventory, 21, Material.BLACK_DYE, context, half(ChoiceKind.BLACK));
        putChoice(inventory, 22, Material.AMETHYST_SHARD, context, half(ChoiceKind.ODD));
        putChoice(inventory, 23, Material.QUARTZ, context, half(ChoiceKind.EVEN));
        putChoice(inventory, 24, Material.ARROW, context, half(ChoiceKind.LOW));
        putChoice(inventory, 29, Material.FIREWORK_ROCKET, context, half(ChoiceKind.HIGH));
        putChoice(inventory, 30, Material.COPPER_INGOT, context, quarter(1));
        putChoice(inventory, 31, Material.IRON_INGOT, context, quarter(2));
        putChoice(inventory, 32, Material.GOLD_INGOT, context, quarter(3));
        putChoice(inventory, 33, Material.DIAMOND, context, quarter(4));
        List<Long> bets = bets(); Material[] materials = {Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK};
        for (int i = 0; i < BET_SLOTS.length && i < bets.size(); i++) {
            long bet = bets.get(i);
            inventory.setItem(BET_SLOTS[i], item(materials[i], messages.text("roulette.bet-name", Map.of("bet", money(bet))),
                    List.of(messages.text(context.bet() == bet ? "roulette.selected-lore" : "roulette.click-lore")), context.bet() == bet));
        }
        String choice = context.choice() == null ? messages.text("roulette.not-selected") : choiceName(context.choice());
        long payout = context.choice() == null ? 0 : payout(context.bet(), context.choice().multiplier());
        inventory.setItem(52, item(Material.LIME_CONCRETE, messages.text("roulette.confirm-name"), List.of(
                messages.text("roulette.summary-choice", Map.of("choice", choice)),
                messages.text("roulette.summary-bet", Map.of("bet", money(context.bet()))),
                messages.text("roulette.summary-payout", Map.of("payout", money(payout))))));
        inventory.setItem(53, item(Material.BARRIER, messages.text("roulette.cancel-name"), List.of()));
        updateWagerDisplay(context.venue(), context.machine(), Bukkit.getPlayer(context.playerId()), context.choice(), context.bet());
    }

    private void updateWagerDisplay(Venue venue, RouletteMachine machine, Player player, WagerChoice choice, long bet) {
        String machineKey = key(venue, machine);
        Location location = machine.wagerDisplayLocation() == null ? null : machine.wagerDisplayLocation().toBukkit();
        if (location == null || location.getWorld() == null || player == null) {
            removeDisplay(wagerDisplays.remove(machineKey));
            return;
        }
        TextDisplay display = wagerDisplays.get(machineKey);
        if (display == null || !display.isValid() || display.getWorld() != location.getWorld()) {
            removeDisplay(display);
            display = createWagerDisplay(location);
            if (display == null) { wagerDisplays.remove(machineKey); return; }
            wagerDisplays.put(machineKey, display);
        }
        String selected = choice == null ? messages.text("roulette.not-selected") : choiceName(choice);
        display.setText(messages.text("roulette.wager-display-details", Map.of(
                "player", player.getName(), "choice", selected, "bet", money(bet))));
    }

    private TextDisplay createWagerDisplay(Location location) {
        try {
            return location.getWorld().spawn(location, TextDisplay.class, display -> {
                display.setBillboard(Display.Billboard.CENTER);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setLineWidth(200);
                display.setSeeThrough(false);
                display.setShadowed(true);
                display.setGlowing(false);
                display.setPersistent(false);
                display.setViewRange((float) Math.max(0.1, plugin.getConfig().getDouble("roulette.wager-display-view-range", 0.65)));
                float scale = (float) Math.max(0.1, Math.min(3, plugin.getConfig().getDouble("roulette.wager-display-scale", 1.0)));
                display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(scale, scale, scale), new AxisAngle4f()));
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("ルーレットの観戦用賭け情報を生成できませんでした: " + exception.getMessage());
            return null;
        }
    }

    private void putChoice(Inventory inventory, int slot, Material material, MenuContext context, WagerChoice choice) {
        boolean selected = choice.equals(context.choice());
        inventory.setItem(slot, item(material, choiceName(choice), choiceLore(context, choice), selected));
    }

    private List<String> choiceLore(MenuContext context, WagerChoice choice) {
        return List.of(messages.text("roulette.multiplier-lore", Map.of("multiplier", decimal(choice.multiplier()))),
                messages.text("roulette.payout-lore", Map.of("payout", money(payout(context.bet(), choice.multiplier())))),
                messages.text(choice.equals(context.choice()) ? "roulette.selected-lore" : "roulette.click-lore"));
    }

    private void start(MenuContext context) {
        Player player = Bukkit.getPlayer(context.playerId());
        if (player == null || !player.isOnline()) return;
        String machineKey = key(context.venue(), context.machine());
        if (!context.playerId().equals(occupied.get(machineKey)) || !accounts.isOpen(context.venue()) || !admissionChecker.test(player, context.venue())) {
            failAndReturn(context, "roulette.start-invalid", Map.of()); return;
        }
        long bet = context.bet();
        if (!economy.has(player, bet)) { failAndReturn(context, "roulette.insufficient-funds", Map.of("bet", money(bet))); return; }
        EconomyResponse response = economy.withdrawPlayer(player, bet);
        if (!response.transactionSuccess()) { failAndReturn(context, "roulette.payment-failed", Map.of()); return; }
        accounts.recordIncome(context.venue(), player, bet, "roulette-bet");
        int result = drawResult(context.venue(), context.choice());
        ItemDisplay ball = createBall(context.machine());
        if (ball == null) {
            refund(player, context.venue(), bet); failAndReturn(context, "roulette.ball-failed", Map.of()); return;
        }
        menus.remove(player.getUniqueId()); player.closeInventory();
        int minimumTicks = Math.max(60, plugin.getConfig().getInt("roulette.min-spin-ticks", 140));
        int maximumTicks = Math.max(minimumTicks, plugin.getConfig().getInt("roulette.max-spin-ticks", 200));
        int totalTicks = minimumTicks + random.nextInt(maximumTicks - minimumTicks + 1);
        int minimumLaps = Math.max(2, plugin.getConfig().getInt("roulette.min-laps", 5));
        int maximumLaps = Math.max(minimumLaps, plugin.getConfig().getInt("roulette.max-laps", 8));
        int laps = minimumLaps + random.nextInt(maximumLaps - minimumLaps + 1);
        double startUnit = random.nextDouble() * 16.0;
        int direction = random.nextBoolean() ? 1 : -1;
        double endUnit = endUnit(startUnit, result - 1, direction, laps);
        GameSession session = new GameSession(player, context.venue(), context.machine(), context.choice(), bet, result,
                ball, totalTicks, startUnit, endUnit);
        sessions.put(machineKey, session); playerSessions.put(player.getUniqueId(), machineKey);
        updateWagerDisplay(context.venue(), context.machine(), player, context.choice(), bet);
        activityListener.onGameStarted(player, context.venue(), bet);
        messages.send(player, "roulette.started", Map.of("bet", money(bet), "choice", choiceName(context.choice())));
        player.sendTitle(messages.text("roulette.start-title"), messages.text("roulette.start-subtitle", Map.of("choice", choiceName(context.choice()))), 5, 35, 5);
        sounds.play(player, "roulette-start");
    }

    private void failAndReturn(MenuContext context, String messagePath, Map<String, ?> values) {
        Player player = Bukkit.getPlayer(context.playerId());
        menus.remove(context.playerId());
        if (player == null || !player.isOnline()) {
            releaseAndPromote(context.venue(), context.machine(), context.playerId());
            return;
        }
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            returnPlayer(player, context.machine());
            completionListener.accept(player, context.venue());
            releaseAndPromote(context.venue(), context.machine(), context.playerId());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                messages.send(player, messagePath, values);
                sounds.play(player, "error");
            }, 1L);
        });
    }

    private void advance(GameSession session) {
        if (sessions.get(key(session.venue(), session.machine())) != session) return;
        Player player = session.player();
        if (!player.isOnline() || !accounts.isOpen(session.venue()) || !admissionChecker.test(player, session.venue())) {
            finish(session, false); return;
        }
        if (session.resolved()) {
            session.resultTicks(session.resultTicks() + 1);
            if (session.resultTicks() >= Math.max(20, plugin.getConfig().getInt("roulette.result-display-ticks", 60))) finish(session, true);
            return;
        }
        session.tick(session.tick() + 1);
        double progress = Math.min(1, session.tick() / (double) session.totalTicks());
        double eased = 1.0 - Math.pow(1.0 - progress, 3.15);
        double unit = session.startUnit() + (session.endUnit() - session.startUnit()) * eased;
        Location location = pathLocation(session.machine(), unit);
        if (location == null) { finish(session, true); return; }
        session.ball().teleport(location);
        int pocket = Math.floorMod((int) Math.floor(unit), 16);
        if (pocket != session.lastPocket()) {
            session.lastPocket(pocket); sounds.play(player, "roulette-tick");
        }
        int particleInterval = Math.max(1, plugin.getConfig().getInt("roulette.particle-interval-ticks", 3));
        if (session.tick() % particleInterval == 0)
            location.getWorld().spawnParticle(Particle.END_ROD, location, 1, 0.025, 0.025, 0.025, 0);
        if (session.tick() >= session.totalTicks()) resolve(session);
    }

    private void resolve(GameSession session) {
        session.resolved(true);
        Location stop = pocketLocation(session.machine().pockets().get(session.result() - 1), false);
        if (stop != null) session.ball().teleport(stop);
        boolean won = session.choice().matches(session.result());
        long payout = won ? payout(session.bet(), session.choice().multiplier()) : 0;
        Map<String, Object> values = Map.of("number", session.result(), "color", colorName(session.result()),
                "result_color", resultColor(session.result()), "choice", choiceName(session.choice()), "payout", money(payout));
        if (won) {
            EconomyResponse response = depositWithFallback(session.player(), payout);
            if (!response.transactionSuccess()) {
                queuePayout(session.player(), payout);
                messages.send(session.player(), "roulette.payout-queued", Map.of("payout", money(payout)));
            }
            accounts.recordExpense(session.venue(), session.player(), payout, "roulette-payout");
            if (payout > session.bet()) houseLossListener.accept(session.venue(), payout - session.bet());
            messages.send(session.player(), "roulette.won", values);
            session.player().sendTitle(messages.text("roulette.win-title", values), messages.text("roulette.win-subtitle", values), 5, 60, 15);
            sounds.play(session.player(), session.choice().kind() == ChoiceKind.EXACT ? "roulette-jackpot" : "roulette-win");
            if (stop != null) stop.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, stop, 25, 0.35, 0.25, 0.35, 0.08);
            activityListener.onGameResult(session.player(), session.venue(), messages.text("scoreboard.result-roulette-win", values), payout);
        } else {
            messages.send(session.player(), "roulette.lost", values);
            session.player().sendTitle(messages.text("roulette.lose-title", values), messages.text("roulette.lose-subtitle", values), 5, 60, 15);
            sounds.play(session.player(), "roulette-lose");
            if (stop != null) stop.getWorld().spawnParticle(Particle.SMOKE, stop, 10, 0.25, 0.15, 0.25, 0.01);
            activityListener.onGameResult(session.player(), session.venue(), messages.text("scoreboard.result-roulette-lose", values), 0);
        }
    }

    private int drawResult(Venue venue, WagerChoice choice) {
        double winChance = Math.max(0, Math.min(1, effectiveRtp(venue) / choice.multiplier()));
        boolean win = random.nextDouble() < winChance;
        List<Integer> candidates = new ArrayList<>();
        for (int number = 1; number <= 16; number++) if (choice.matches(number) == win) candidates.add(number);
        return candidates.get(random.nextInt(candidates.size()));
    }

    private double effectiveRtp(Venue venue) {
        double base = Math.max(0.50, Math.min(1.20, plugin.getConfig().getDouble("roulette.rtp", 1.0)));
        if (!plugin.getConfig().getBoolean("roulette.dynamic-odds.enabled", true)) return base;
        CasinoAccountManager.AccountSnapshot account = accounts.snapshot(venue);
        double income = Math.max(0, (double) account.totalIncome()), expense = Math.max(0, (double) account.totalExpense());
        double turnover = income + expense;
        if (!Double.isFinite(turnover) || turnover <= 0) return base;
        double minimum = Math.max(0, plugin.getConfig().getDouble("roulette.dynamic-odds.minimum-turnover", 100_000));
        double strength = minimum <= 0 ? 1 : Math.min(1, turnover / minimum);
        double margin = (income - expense) / turnover;
        double fullMargin = Math.max(0.0001, plugin.getConfig().getDouble("roulette.dynamic-odds.full-adjustment-profit-margin", 0.30));
        double direction = Math.max(-1, Math.min(1, margin / fullMargin));
        double maximum = Math.max(0, Math.min(0.20, plugin.getConfig().getDouble("roulette.dynamic-odds.maximum-rtp-adjustment", 0.03)));
        return Math.max(0.50, Math.min(1.20, base + direction * maximum * strength));
    }

    private ItemDisplay createBall(RouletteMachine machine) {
        Location location = pathLocation(machine, 0);
        if (location == null) return null;
        try {
            return location.getWorld().spawn(location, ItemDisplay.class, display -> {
                display.setItemStack(new ItemStack(Material.SNOWBALL));
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                display.setBillboard(Display.Billboard.CENTER);
                display.setBrightness(new Display.Brightness(15, 15));
                display.setGlowing(true); display.setViewRange(2.0f); display.setTeleportDuration(1);
                float scale = (float) Math.max(0.1, Math.min(2, plugin.getConfig().getDouble("roulette.ball-scale", 0.65)));
                display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(scale, scale, scale), new AxisAngle4f()));
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("ルーレットの玉を生成できませんでした: " + exception.getMessage());
            return null;
        }
    }

    private Location pathLocation(RouletteMachine machine, double unit) {
        if (machine.pockets().size() != 16) return null;
        int base = (int) Math.floor(unit); double fraction = unit - Math.floor(unit);
        Location p0 = pocketLocation(machine.pockets().get(Math.floorMod(base - 1, 16)), true);
        Location p1 = pocketLocation(machine.pockets().get(Math.floorMod(base, 16)), true);
        Location p2 = pocketLocation(machine.pockets().get(Math.floorMod(base + 1, 16)), true);
        Location p3 = pocketLocation(machine.pockets().get(Math.floorMod(base + 2, 16)), true);
        if (p0 == null || p1 == null || p2 == null || p3 == null || p0.getWorld() != p1.getWorld()) return null;
        double x = catmull(p0.getX(), p1.getX(), p2.getX(), p3.getX(), fraction);
        double y = catmull(p0.getY(), p1.getY(), p2.getY(), p3.getY(), fraction)
                + Math.sin(Math.PI * fraction) * Math.max(0, plugin.getConfig().getDouble("roulette.ball-hop-height", 0.08));
        double z = catmull(p0.getZ(), p1.getZ(), p2.getZ(), p3.getZ(), fraction);
        return new Location(p1.getWorld(), x, y, z);
    }

    private Location pocketLocation(BlockPosition pocket, boolean path) {
        World world = Bukkit.getWorld(pocket.world()); if (world == null) return null;
        double height = plugin.getConfig().getDouble("roulette.ball-height", 1.12);
        if (!path) height += plugin.getConfig().getDouble("roulette.stop-height-offset", 0.0);
        return new Location(world, pocket.x() + 0.5, pocket.y() + height, pocket.z() + 0.5);
    }

    private double catmull(double p0, double p1, double p2, double p3, double t) {
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }

    private double endUnit(double start, int target, int direction, int laps) {
        double end = target;
        if (direction > 0) { while (end <= start) end += 16; return end + 16.0 * (laps - 1); }
        while (end >= start) end -= 16;
        return end - 16.0 * (laps - 1);
    }

    private void finish(GameSession session, boolean returnToTable) {
        String machineKey = key(session.venue(), session.machine());
        if (sessions.remove(machineKey) != session) return;
        playerSessions.remove(session.player().getUniqueId());
        if (session.ball() != null && session.ball().isValid()) session.ball().remove();
        if (returnToTable && session.player().isOnline()) returnPlayer(session.player(), session.machine());
        completionListener.accept(session.player(), session.venue());
        releaseAndPromote(session.venue(), session.machine(), session.player().getUniqueId());
    }

    private void returnPlayer(Player player, RouletteMachine machine) {
        Location destination = machine.returnLocation() == null ? null : machine.returnLocation().toBukkit();
        if (destination != null && player.isOnline()) player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private void releaseAndPromote(Venue venue, RouletteMachine machine, UUID playerId) {
        String machineKey = key(venue, machine);
        if (!occupied.remove(machineKey, playerId)) return;
        removeDisplay(wagerDisplays.remove(machineKey));
        promoteNext(venue, machine);
    }

    private void promoteNext(Venue venue, RouletteMachine machine) {
        String machineKey = key(venue, machine);
        Deque<QueuedPlayer> queue = queues.get(machineKey);
        while (queue != null && !queue.isEmpty()) {
            QueuedPlayer queued = queue.removeFirst();
            playerQueues.remove(queued.playerId(), machineKey);
            Player player = Bukkit.getPlayer(queued.playerId());
            if (player == null || !player.isOnline() || !machine.complete()
                    || !admissionChecker.test(player, venue)
                    || occupied.containsValue(player.getUniqueId())
                    || menus.containsKey(player.getUniqueId()) || playerSessions.containsKey(player.getUniqueId())) continue;
            Location viewing = machine.viewingLocation() == null ? null : machine.viewingLocation().toBukkit();
            if (viewing == null || !player.teleport(viewing, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                messages.send(player, "roulette.teleport-failed"); sounds.play(player, "error");
                completionListener.accept(player, venue);
                continue;
            }
            occupied.put(machineKey, player.getUniqueId());
            messages.send(player, "roulette.queue-turn");
            player.sendTitle(messages.text("roulette.queue-turn-title"), messages.text("roulette.queue-turn-subtitle"), 5, 45, 10);
            notifyQueuePositions(machineKey);
            updateStartDisplay(venue, machine);
            scheduleMenuOpen(player, venue, machine, 5L);
            if (queue.isEmpty()) queues.remove(machineKey);
            return;
        }
        queues.remove(machineKey);
        updateStartDisplay(venue, machine);
    }

    private void cleanupQueues() {
        for (var entry : new ArrayList<>(queues.entrySet())) {
            String machineKey = entry.getKey();
            Deque<QueuedPlayer> queue = entry.getValue();
            boolean changed = false;
            for (QueuedPlayer queued : new ArrayList<>(queue)) {
                Player player = Bukkit.getPlayer(queued.playerId());
                if (player != null && player.isOnline() && admissionChecker.test(player, queued.venue())) continue;
                queue.remove(queued);
                playerQueues.remove(queued.playerId(), machineKey);
                changed = true;
            }
            if (queue.isEmpty()) queues.remove(machineKey);
            if (!occupied.containsKey(machineKey) && !queue.isEmpty()) {
                QueuedPlayer first = queue.peekFirst();
                promoteNext(first.venue(), first.machine());
            } else if (changed && !queue.isEmpty()) {
                notifyQueuePositions(machineKey);
                QueuedPlayer first = queue.peekFirst();
                updateStartDisplay(first.venue(), first.machine());
            }
        }
    }

    private void expireInactiveMenus() {
        long timeoutTicks = Math.max(20L, plugin.getConfig().getLong("roulette.selection-timeout-ticks", 1_200L));
        for (MenuContext context : new ArrayList<>(menus.values())) {
            if (ticks - context.lastActivityTick() < timeoutTicks || !menus.remove(context.playerId(), context)) continue;
            Player player = Bukkit.getPlayer(context.playerId());
            if (player == null || !player.isOnline()) {
                releaseAndPromote(context.venue(), context.machine(), context.playerId());
                continue;
            }
            player.closeInventory();
            returnPlayer(player, context.machine());
            completionListener.accept(player, context.venue());
            releaseAndPromote(context.venue(), context.machine(), context.playerId());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                messages.send(player, "roulette.selection-timeout");
                player.sendTitle(messages.text("roulette.selection-timeout-title"),
                        messages.text("roulette.selection-timeout-subtitle"), 5, 40, 10);
                sounds.play(player, "error");
            }, 1L);
        }
    }

    private void removeFromQueue(UUID playerId, boolean notify) {
        String machineKey = playerQueues.remove(playerId);
        if (machineKey == null) return;
        Deque<QueuedPlayer> queue = queues.get(machineKey);
        if (queue == null) return;
        QueuedPlayer removed = null;
        for (QueuedPlayer queued : new ArrayList<>(queue)) if (queued.playerId().equals(playerId)) {
            queue.remove(queued); removed = queued; break;
        }
        if (notify) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) messages.send(player, "roulette.queue-left");
        }
        if (queue.isEmpty()) queues.remove(machineKey);
        else notifyQueuePositions(machineKey);
        if (removed != null) updateStartDisplay(removed.venue(), removed.machine());
    }

    private void notifyQueuePositions(String machineKey) {
        Deque<QueuedPlayer> queue = queues.get(machineKey);
        if (queue == null) return;
        int position = 0;
        for (QueuedPlayer queued : queue) {
            position++;
            Player player = Bukkit.getPlayer(queued.playerId());
            if (player != null && player.isOnline())
                messages.send(player, "roulette.queue-position", Map.of("position", position));
        }
    }

    private int queuePosition(String machineKey, UUID playerId) {
        Deque<QueuedPlayer> queue = queues.get(machineKey);
        if (queue == null) return -1;
        int position = 0;
        for (QueuedPlayer queued : queue) {
            position++;
            if (queued.playerId().equals(playerId)) return position;
        }
        return -1;
    }

    private int queueSize(String machineKey) {
        Deque<QueuedPlayer> queue = queues.get(machineKey);
        return queue == null ? 0 : queue.size();
    }

    private void updateStartDisplay(Venue venue, RouletteMachine machine) {
        TextDisplay display = startDisplays.get(key(venue, machine));
        if (display != null && display.isValid()) display.setText(startDisplayText(venue, machine));
    }
    private FoundMachine findByButton(Block block) {
        BlockPosition position = position(block);
        for (Venue venue : venues.all()) for (RouletteMachine machine : venue.rouletteMachines().values())
            if (position.equals(machine.playButton())) return new FoundMachine(venue, machine);
        return null;
    }
    private FoundMachine findRegistered(Block block) {
        BlockPosition position = position(block);
        for (Venue venue : venues.all()) for (RouletteMachine machine : venue.rouletteMachines().values())
            if (position.equals(machine.playButton()) || machine.pockets().contains(position)) return new FoundMachine(venue, machine);
        return null;
    }
    private BlockPosition position(Block block) { return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()); }
    private String key(Venue venue, RouletteMachine machine) { return venue.id().toLowerCase(Locale.ROOT) + ":" + machine.id().toLowerCase(Locale.ROOT); }

    private List<Long> bets() {
        List<Long> configured = plugin.getConfig().getLongList("roulette.bets").stream().filter(value -> value > 0).limit(3).toList();
        return configured.size() == 3 ? configured : List.of(1_000L, 10_000L, 50_000L);
    }
    private WagerChoice half(ChoiceKind kind) {
        return new WagerChoice(kind, 0, configuredMultiplier("roulette.payouts.half", 2.0));
    }
    private WagerChoice quarter(int quarter) {
        return new WagerChoice(ChoiceKind.QUARTER, quarter, configuredMultiplier("roulette.payouts.quarter", 4.0));
    }
    private WagerChoice exact(int number) {
        return new WagerChoice(ChoiceKind.EXACT, number, configuredMultiplier("roulette.payouts.exact", 16.0));
    }
    private double configuredMultiplier(String path, double fallback) {
        return Math.max(0.01, Math.min(1000, plugin.getConfig().getDouble(path, fallback)));
    }
    private long payout(long bet, double multiplier) {
        long cap = Math.max(1, plugin.getConfig().getLong("roulette.max-payout", 100_000_000));
        double value = bet * multiplier;
        return !Double.isFinite(value) || value >= cap ? cap : Math.max(0, (long) Math.floor(value));
    }
    private String money(long amount) { return CurrencyFormatter.format(economy, amount); }
    private String decimal(double value) { return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", ""); }
    private String choiceName(WagerChoice choice) {
        return switch (choice.kind()) {
            case EXACT -> messages.text("roulette.choice-exact", Map.of("number", choice.option()));
            case QUARTER -> messages.text("roulette.choice-quarter", Map.of("from", (choice.option() - 1) * 4 + 1, "to", choice.option() * 4));
            default -> messages.text("roulette.choice-" + choice.kind().name().toLowerCase(Locale.ROOT));
        };
    }
    private String colorName(int number) {
        return messages.text(number % 2 == 1 ? "roulette.color-red" : "roulette.color-black") + ChatColor.RESET;
    }
    private ChatColor resultColor(int number) { return number % 2 == 1 ? ChatColor.RED : ChatColor.GRAY; }

    private ItemStack item(Material material, String name, List<String> lore) { return item(material, name, lore, false); }
    private ItemStack item(Material material, String name, List<String> lore, boolean glowing) {
        ItemStack stack = new ItemStack(material); ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (!lore.isEmpty()) meta.setLore(lore.stream().map(line -> ChatColor.translateAlternateColorCodes('&', line)).toList());
        if (glowing) meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta); return stack;
    }

    private EconomyResponse depositWithFallback(Player player, long amount) {
        if (!economy.hasAccount(player)) economy.createPlayerAccount(player);
        double before = economy.getBalance(player);
        EconomyResponse response = economy.depositPlayer(player, amount);
        double after = economy.getBalance(player), tolerance = Math.max(0.000001, Math.abs(amount) * 0.000000001);
        if (response.transactionSuccess() || (Double.isFinite(before) && Double.isFinite(after) && after - before >= amount - tolerance))
            return response.transactionSuccess() ? response : new EconomyResponse(amount, after, EconomyResponse.ResponseType.SUCCESS, null);
        return response;
    }
    private void refund(Player player, Venue venue, long amount) {
        EconomyResponse response = depositWithFallback(player, amount);
        if (!response.transactionSuccess()) queuePayout(player, amount);
        accounts.recordExpense(venue, player, amount, "roulette-refund");
    }
    private void queuePayout(Player player, long amount) {
        PendingPayout current = pendingPayouts.get(player.getUniqueId());
        long total;
        try { total = Math.addExact(current == null ? 0 : current.amount(), amount); } catch (ArithmeticException exception) { total = Long.MAX_VALUE; }
        pendingPayouts.put(player.getUniqueId(), new PendingPayout(player.getName(), total)); savePendingPayouts();
    }
    private void retryPendingPayouts() {
        for (var entry : new ArrayList<>(pendingPayouts.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey()); if (player == null || !player.isOnline()) continue;
            EconomyResponse response = depositWithFallback(player, entry.getValue().amount()); if (!response.transactionSuccess()) continue;
            long payout = entry.getValue().amount(); pendingPayouts.remove(entry.getKey()); savePendingPayouts();
            messages.send(player, "roulette.pending-payout-paid", Map.of("payout", money(payout)));
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
        File temporary = new File(plugin.getDataFolder(), "pending-roulette-payouts.yml.tmp");
        try {
            data.save(temporary);
            try { Files.move(temporary.toPath(), pendingFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary.toPath(), pendingFile.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) { plugin.getLogger().severe("ルーレット保留払戻しの保存に失敗しました: " + exception.getMessage()); }
    }

    public void resetRuntime() {
        resetting = true;
        for (MenuContext context : new ArrayList<>(menus.values())) {
            Player player = Bukkit.getPlayer(context.playerId());
            if (player != null) {
                player.closeInventory();
                returnPlayer(player, context.machine());
                completionListener.accept(player, context.venue());
            }
        }
        menus.clear();
        for (Deque<QueuedPlayer> queue : queues.values()) for (QueuedPlayer queued : queue) {
            Player player = Bukkit.getPlayer(queued.playerId());
            if (player == null || !player.isOnline()) continue;
            returnPlayer(player, queued.machine());
            messages.send(player, "roulette.queue-reset");
            completionListener.accept(player, queued.venue());
        }
        queues.clear(); playerQueues.clear();
        for (GameSession session : new ArrayList<>(sessions.values())) {
            if (session.ball() != null && session.ball().isValid()) session.ball().remove();
            if (!session.resolved()) {
                EconomyResponse response = depositWithFallback(session.player(), session.bet());
                if (!response.transactionSuccess()) queuePayout(session.player(), session.bet());
                accounts.recordExpense(session.venue(), session.player(), session.bet(), "roulette-disable-refund");
                if (session.player().isOnline()) messages.send(session.player(), "roulette.refund-on-disable", Map.of("bet", money(session.bet())));
            }
            completionListener.accept(session.player(), session.venue());
        }
        sessions.clear(); playerSessions.clear(); occupied.clear();
        startDisplays.values().forEach(this::removeDisplay); startDisplays.clear();
        numberDisplays.values().forEach(displays -> displays.forEach(this::removeDisplay)); numberDisplays.clear();
        wagerDisplays.values().forEach(this::removeDisplay); wagerDisplays.clear();
        resetting = false;
    }

    private enum ChoiceKind { RED, BLACK, ODD, EVEN, LOW, HIGH, QUARTER, EXACT }
    private record WagerChoice(ChoiceKind kind, int option, double multiplier) {
        boolean matches(int number) { return switch (kind) {
            case RED -> number % 2 == 1;
            case BLACK -> number % 2 == 0;
            case ODD -> number % 2 == 1;
            case EVEN -> number % 2 == 0;
            case LOW -> number <= 8;
            case HIGH -> number >= 9;
            case QUARTER -> number >= (option - 1) * 4 + 1 && number <= option * 4;
            case EXACT -> number == option;
        }; }
    }
    private static final class MenuContext {
        private final UUID playerId; private final Venue venue; private final RouletteMachine machine; private final Inventory inventory;
        private long bet; private WagerChoice choice; private long lastActivityTick;
        private MenuContext(UUID playerId, Venue venue, RouletteMachine machine, Inventory inventory, long bet, long openedTick) {
            this.playerId = playerId; this.venue = venue; this.machine = machine; this.inventory = inventory; this.bet = bet;
            this.lastActivityTick = openedTick;
        }
        UUID playerId() { return playerId; } Venue venue() { return venue; } RouletteMachine machine() { return machine; } Inventory inventory() { return inventory; }
        long bet() { return bet; } void bet(long value) { bet = value; } WagerChoice choice() { return choice; } void choice(WagerChoice value) { choice = value; }
        long lastActivityTick() { return lastActivityTick; } void touch(long tick) { lastActivityTick = tick; }
    }
    private static final class GameSession {
        private final Player player; private final Venue venue; private final RouletteMachine machine; private final WagerChoice choice;
        private final long bet; private final int result; private final ItemDisplay ball; private final int totalTicks; private final double startUnit; private final double endUnit;
        private int tick; private int lastPocket = -1; private boolean resolved; private int resultTicks;
        private GameSession(Player player, Venue venue, RouletteMachine machine, WagerChoice choice, long bet, int result,
                            ItemDisplay ball, int totalTicks, double startUnit, double endUnit) {
            this.player = player; this.venue = venue; this.machine = machine; this.choice = choice; this.bet = bet; this.result = result;
            this.ball = ball; this.totalTicks = totalTicks; this.startUnit = startUnit; this.endUnit = endUnit;
        }
        Player player() { return player; } Venue venue() { return venue; } RouletteMachine machine() { return machine; } WagerChoice choice() { return choice; }
        long bet() { return bet; } int result() { return result; } ItemDisplay ball() { return ball; } int totalTicks() { return totalTicks; }
        double startUnit() { return startUnit; } double endUnit() { return endUnit; } int tick() { return tick; } void tick(int value) { tick = value; }
        int lastPocket() { return lastPocket; } void lastPocket(int value) { lastPocket = value; } boolean resolved() { return resolved; }
        void resolved(boolean value) { resolved = value; } int resultTicks() { return resultTicks; } void resultTicks(int value) { resultTicks = value; }
    }
    private record FoundMachine(Venue venue, RouletteMachine machine) {}
    private record QueuedPlayer(UUID playerId, Venue venue, RouletteMachine machine) {}
    private record PendingPayout(String name, long amount) {}
}
