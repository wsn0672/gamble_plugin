package jp.wsn0672.gamblegate.listener;

import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.account.CasinoAccountManager;
import jp.wsn0672.gamblegate.config.CurrencyFormatter;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.SoundService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.Gate;
import jp.wsn0672.gamblegate.model.Venue;
import jp.wsn0672.gamblegate.pass.PassManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

public final class GateListener implements Listener {
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final SoundService sounds;
    private final Economy economy;
    private final PassManager passManager;
    private final CasinoAccountManager accounts;
    private final Map<UUID, String> admitted = new HashMap<>();
    private final Map<UUID, MenuContext> menus = new HashMap<>();
    private final Map<UUID, String> standingGate = new HashMap<>();
    private final Map<UUID, Long> lastMenuAt = new HashMap<>();
    private final Map<UUID, String> pendingPassEvictions = new HashMap<>();
    private final Map<UUID, String> pendingPassAdmissions = new HashMap<>();
    private BiPredicate<Player, Venue> slotGameChecker = (player, venue) -> false;
    private BiConsumer<Player, Venue> admissionListener = (player, venue) -> {};
    private BiConsumer<Player, Venue> departureListener = (player, venue) -> {};
    private BiFunction<Player, Venue, String> departureProfit = (player, venue) -> "";

    public GateListener(GambleGatePlugin plugin, VenueRepository venues, MessageService messages, SoundService sounds, Economy economy, PassManager passManager, CasinoAccountManager accounts) {
        this.plugin = plugin; this.venues = venues; this.messages = messages; this.sounds = sounds; this.economy = economy; this.passManager = passManager;
        this.accounts = accounts;
    }

    public void reloadRuntimeState() {
        Map<UUID, MenuContext> openMenus = new HashMap<>(menus);
        menus.clear();
        for (UUID playerId : openMenus.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) player.closeInventory();
        }

        Map<UUID, String> previousAdmissions = new HashMap<>(admitted);
        admitted.clear();
        for (var entry : previousAdmissions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            Venue venue = venues.get(entry.getValue());
            if (venue != null && accounts.isOpen(venue) && insideExitGrace(venue, player.getLocation())) {
                admitted.put(player.getUniqueId(), venue.id());
                admissionListener.accept(player, venue);
            } else {
                departureListener.accept(player, venue);
            }
        }
        standingGate.clear(); lastMenuAt.clear(); pendingPassEvictions.clear(); pendingPassAdmissions.clear();
        for (Venue venue : venues.all()) if (!accounts.isOpen(venue)) emergencyClose(venue);
    }

    public void setSlotGameChecker(BiPredicate<Player, Venue> checker) { slotGameChecker = checker; }
    public void setAdmissionListener(BiConsumer<Player, Venue> listener) { admissionListener = listener; }
    public void setDepartureListener(BiConsumer<Player, Venue> listener) { departureListener = listener; }
    public void setDepartureProfit(BiFunction<Player, Venue, String> provider) { departureProfit = provider; }

    public void requestPassEviction(UUID playerId, Venue venue) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !isAdmitted(player, venue)) return;
        if (slotGameChecker.test(player, venue)) {
            pendingPassEvictions.put(playerId, venue.id());
            messages.send(player, "passes.eviction-after-game");
            return;
        }
        evictForInvalidPass(player, venue);
    }

    public void onSlotGameCompleted(Player player, Venue venue) {
        String pendingVenue = pendingPassEvictions.get(player.getUniqueId());
        if (pendingVenue == null || !pendingVenue.equalsIgnoreCase(venue.id())) return;
        pendingPassEvictions.remove(player.getUniqueId());
        if (passManager.hasActivePass(player, venue) || !isAdmitted(player, venue)) return;
        evictForInvalidPass(player, venue);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) return;
        Player player = event.getPlayer();
        Venue destination = venueAt(event.getTo());
        Venue origin = venueAt(event.getFrom());
        if (destination != null && origin == null && !isAdmitted(player, destination)) {
            if (!accounts.isOpen(destination)) {
                if (!player.hasPermission("gamblegate.bypass")) {
                    event.setTo(event.getFrom());
                    denyClosed(player);
                    return;
                }
            } else if (passManager.hasActivePass(player, destination)) {
                // OPなどがbypass権限を持っていても、パスがある場合は正規入場として確定させる。
                pendingPassAdmissions.put(player.getUniqueId(), destination.id());
            } else if (!player.hasPermission("gamblegate.bypass")) {
                    event.setTo(event.getFrom());
                    warnIntrusion(player);
                    return;
            }
        }
        detectGate(player, event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMoveComplete(PlayerMoveEvent event) {
        if (event.isCancelled()) {
            pendingPassAdmissions.remove(event.getPlayer().getUniqueId());
            return;
        }
        if (event.getTo() == null) return;
        // ゲート判定中にテレポートした場合、元の徒歩イベントが持つ古い到着地点で
        // 退出判定をすると、入場直後に入場状態が解除されてしまうため無視する。
        if (!sameBlock(event.getPlayer().getLocation(), event.getTo())) return;
        completePendingPassAdmission(event.getPlayer(), event.getTo());
        admitSuccessfulPassCrossing(event.getPlayer(), event.getFrom(), event.getTo());
        if (isExitMenuOpen(event.getPlayer())) return;
        String current = admitted.get(event.getPlayer().getUniqueId());
        Venue currentVenue = current == null ? null : venues.get(current);
        if (currentVenue == null) {
            if (current != null) admitted.remove(event.getPlayer().getUniqueId());
            return;
        }
        if (!insideExitGrace(currentVenue, event.getTo())) markExited(event.getPlayer(), currentVenue);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        Venue destination = venueAt(event.getTo());
        Venue origin = venueAt(event.getFrom());
        if (destination != null && origin == null && !isAdmitted(event.getPlayer(), destination)) {
            if (!accounts.isOpen(destination)) {
                if (!event.getPlayer().hasPermission("gamblegate.bypass")) {
                    event.setCancelled(true);
                    denyClosed(event.getPlayer());
                    return;
                }
            } else if (passManager.hasActivePass(event.getPlayer(), destination)) {
                // bypass権限よりパス入場を優先し、スロット等を使える入場状態にする。
                pendingPassAdmissions.put(event.getPlayer().getUniqueId(), destination.id());
            } else if (!event.getPlayer().hasPermission("gamblegate.bypass")) {
                event.setCancelled(true);
                warnIntrusion(event.getPlayer());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleportComplete(PlayerTeleportEvent event) {
        if (event.isCancelled()) {
            pendingPassAdmissions.remove(event.getPlayer().getUniqueId());
            return;
        }
        if (event.getTo() == null) return;
        completePendingPassAdmission(event.getPlayer(), event.getTo());
        admitSuccessfulPassCrossing(event.getPlayer(), event.getFrom(), event.getTo());
        if (isExitMenuOpen(event.getPlayer())) return;
        String current = admitted.get(event.getPlayer().getUniqueId());
        Venue currentVenue = current == null ? null : venues.get(current);
        if (currentVenue != null && !insideExitGrace(currentVenue, event.getTo())) markExited(event.getPlayer(), currentVenue);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Venue inside = venueAt(player.getLocation());
        if (inside != null && !accounts.isOpen(inside)) {
            Bukkit.getScheduler().runTask(plugin, () -> emergencyClose(inside));
            return;
        }
        if (inside != null && !isAdmitted(player, inside)) {
            Location destination = logoutDestination(inside, player);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (destination != null && player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                    player.sendTitle(messages.text("exit.logout-return-title"), messages.text("exit.logout-return-subtitle"), 10, 60, 10);
                    messages.send(player, "exit.logout-return-message");
                    sounds.play(player, "exit-success");
                } else {
                    messages.send(player, "errors.logout-teleport-failed");
                    sounds.play(player, "error");
                }
            });
            return;
        }
        if (player.hasPermission("gamblegate.bypass")) return;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String venueId = admitted.get(playerId);
        Venue venue = venueId == null ? null : venues.get(venueId);
        departureListener.accept(event.getPlayer(), venue);
        admitted.remove(playerId);
        menus.remove(playerId);
        standingGate.remove(playerId);
        lastMenuAt.remove(playerId);
        pendingPassEvictions.remove(playerId);
        pendingPassAdmissions.remove(playerId);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MenuContext context = menus.get(player.getUniqueId());
        if (context == null || !event.getView().getTopInventory().equals(context.inventory())) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= context.inventory().getSize()) return;
        if (context.type() == MenuType.ENTRY && event.getRawSlot() == 15) enter(player, context.venue(), context.gate());
        else if (context.type() == MenuType.EXIT && event.getRawSlot() == 15) exit(player, context.venue(), context.gate());
        else if (event.getRawSlot() == 11) player.closeInventory();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        MenuContext context = menus.get(event.getPlayer().getUniqueId());
        if (context == null || !context.inventory().equals(event.getInventory())) return;
        menus.remove(event.getPlayer().getUniqueId());
        if (context.type() == MenuType.EXIT && event.getPlayer() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                Venue currentVenue = venues.get(context.venue().id());
                if (currentVenue != null && !insideExitGrace(currentVenue, player.getLocation())) markExited(player, currentVenue);
            });
        }
    }

    private void detectGate(Player player, Location location) {
        FoundGate found = findGate(location);
        String key = found == null ? null : gateKey(found.venue(), found.gate(), found.side());
        String previous = standingGate.put(player.getUniqueId(), key);
        if (found == null) { standingGate.remove(player.getUniqueId()); return; }
        if (key.equals(previous)) return;
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("gate-cooldown-ms", 1000);
        if (now - lastMenuAt.getOrDefault(player.getUniqueId(), 0L) < cooldown) return;
        lastMenuAt.put(player.getUniqueId(), now);
        if (!found.gate().complete() || !validSides(found.venue(), found.gate())) {
            messages.send(player, "errors.incomplete-gate");
            return;
        }
        if (found.side() == Side.ENTRANCE && !accounts.isOpen(found.venue())) {
            denyClosed(player);
            return;
        }
        if (found.side() == Side.ENTRANCE && passManager.hasActivePass(player, found.venue())) enterWithPass(player, found.venue(), found.gate());
        else if (found.side() == Side.ENTRANCE) openEntry(player, found.venue(), found.gate());
        else if (isAdmitted(player, found.venue()) && passManager.hasActivePass(player, found.venue())) exit(player, found.venue(), found.gate());
        else if (isAdmitted(player, found.venue())) openExit(player, found.venue(), found.gate());
    }

    private void openEntry(Player player, Venue venue, Gate gate) {
        String fee = CurrencyFormatter.format(economy, venue.fee());
        Inventory inventory = Bukkit.createInventory(null, 27, messages.text("entry.title"));
        inventory.setItem(11, item(Material.BARRIER, messages.text("entry.cancel"), List.of()));
        inventory.setItem(15, item(Material.EMERALD, messages.text("entry.confirm", Map.of("fee", fee)), List.of()));
        menus.put(player.getUniqueId(), new MenuContext(MenuType.ENTRY, venue, gate, inventory));
        player.openInventory(inventory);
        sounds.play(player, "gui-open");
    }

    private void openExit(Player player, Venue venue, Gate gate) {
        String fee = CurrencyFormatter.format(economy, venue.fee());
        Inventory inventory = Bukkit.createInventory(null, 27, messages.text("exit.title"));
        inventory.setItem(11, item(Material.EMERALD, messages.text("exit.stay"), List.of()));
        List<String> warning = passManager.hasActivePass(player, venue)
                ? messages.lines("exit.pass-warning", Map.of("pass", passManager.activePassName(player, venue)))
                : messages.lines("exit.warning", Map.of("fee", fee));
        inventory.setItem(15, item(Material.IRON_DOOR, messages.text("exit.confirm"), warning));
        menus.put(player.getUniqueId(), new MenuContext(MenuType.EXIT, venue, gate, inventory));
        player.openInventory(inventory);
        sounds.play(player, "gui-open");
    }

    private void enter(Player player, Venue venue, Gate gate) {
        player.closeInventory();
        if (!accounts.isOpen(venue)) { denyClosed(player); return; }
        if (!gate.complete() || !validSides(venue, gate)) { messages.send(player, "errors.incomplete-gate"); return; }
        if (!economy.has(player, venue.fee())) {
            messages.send(player, "errors.insufficient-funds", Map.of("fee", CurrencyFormatter.format(economy, venue.fee()))); sounds.play(player, "error"); return;
        }
        EconomyResponse response = economy.withdrawPlayer(player, venue.fee());
        if (!response.transactionSuccess()) { messages.send(player, "errors.payment-failed"); return; }
        admitted.put(player.getUniqueId(), venue.id());
        Location destination = gate.entranceDestination().toBukkit();
        if (destination == null || !player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            admitted.remove(player.getUniqueId());
            economy.depositPlayer(player, venue.fee());
            messages.send(player, "errors.teleport-failed");
            return;
        }
        standingGate.put(player.getUniqueId(), gateKey(venue, gate, Side.EXIT));
        accounts.recordIncome(venue, player, venue.fee(), "entry-fee");
        String fee = CurrencyFormatter.format(economy, venue.fee());
        admissionListener.accept(player, venue);
        messages.send(player, "entry.paid", Map.of("fee", fee));
        player.sendTitle(
                messages.text("entry.success-title", Map.of("fee", fee)),
                messages.text("entry.success-subtitle", Map.of("fee", fee)),
                10, 60, 10
        );
        sounds.play(player, "entry-success");
    }

    private void enterWithPass(Player player, Venue venue, Gate gate) {
        if (!accounts.isOpen(venue)) { denyClosed(player); return; }
        if (!gate.complete() || !validSides(venue, gate)) { messages.send(player, "errors.incomplete-gate"); return; }
        // テレポートイベント中の不正侵入判定にも正規入場として扱わせる。
        admitted.put(player.getUniqueId(), venue.id());
        Location destination = gate.entranceDestination().toBukkit();
        if (destination == null || !player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            admitted.remove(player.getUniqueId());
            messages.send(player, "errors.pass-teleport-failed");
            sounds.play(player, "error");
            return;
        }
        // 他プラグインのテレポートイベント処理後にも入場状態を確実に残す。
        admitted.put(player.getUniqueId(), venue.id());
        standingGate.put(player.getUniqueId(), gateKey(venue, gate, Side.EXIT));
        announcePassAdmission(player, venue);
    }

    private boolean admitPassCrossing(Player player, Venue venue) {
        if (!accounts.isOpen(venue) || !passManager.hasActivePass(player, venue)) return false;
        admitted.put(player.getUniqueId(), venue.id());
        announcePassAdmission(player, venue);
        return true;
    }

    private void announcePassAdmission(Player player, Venue venue) {
        String pass = passManager.activePassName(player, venue);
        admissionListener.accept(player, venue);
        player.sendTitle(
                messages.text("entry.pass-success-title", Map.of("pass", pass, "venue", venue.id())),
                messages.text("entry.pass-success-subtitle", Map.of("pass", pass, "venue", venue.id())),
                10, 60, 10
        );
        messages.send(player, "entry.pass-success-message", Map.of("pass", pass, "venue", venue.id()));
        sounds.play(player, "entry-success");
    }

    private void admitSuccessfulPassCrossing(Player player, Location from, Location to) {
        Venue destination = venueAt(to);
        if (destination == null || venueAt(from) != null || isAdmitted(player, destination)) return;
        admitPassCrossing(player, destination);
    }

    private void completePendingPassAdmission(Player player, Location destinationLocation) {
        String venueId = pendingPassAdmissions.remove(player.getUniqueId());
        if (venueId == null) return;
        Venue venue = venues.get(venueId);
        if (venue == null || !venue.contains(destinationLocation) || isAdmitted(player, venue)) return;
        if (!accounts.isOpen(venue)) { denyClosed(player); return; }
        if (!passManager.hasActivePass(player, venue)) {
            warnIntrusion(player);
            return;
        }
        admitPassCrossing(player, venue);
    }

    private void exit(Player player, Venue venue, Gate gate) {
        player.closeInventory();
        Location destination = gate.exitDestination().toBukkit();
        if (destination == null) { messages.send(player, "errors.incomplete-gate"); return; }
        if (player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            standingGate.put(player.getUniqueId(), gateKey(venue, gate, Side.ENTRANCE));
            markExited(player, venue);
        } else {
            admitted.put(player.getUniqueId(), venue.id());
            messages.send(player, "errors.exit-teleport-failed");
            sounds.play(player, "error");
        }
    }

    private boolean validSides(Venue venue, Gate gate) {
        Location in = gate.entranceDestination().toBukkit();
        Location out = gate.exitDestination().toBukkit();
        return in != null && out != null && venue.contains(in) && !venue.contains(out);
    }

    public boolean isAdmitted(Player player, Venue venue) { return venue.id().equalsIgnoreCase(admitted.get(player.getUniqueId())); }
    private Venue venueAt(Location location) { return venues.all().stream().filter(v -> v.contains(location)).findFirst().orElse(null); }

    private FoundGate findGate(Location location) {
        for (Venue venue : venues.all()) for (Gate gate : venue.gates().values()) {
            if (isInsideGateColumn(location, gate.entranceBlock())) return new FoundGate(venue, gate, Side.ENTRANCE);
            if (isInsideGateColumn(location, gate.exitBlock())) return new FoundGate(venue, gate, Side.EXIT);
        }
        return null;
    }

    private boolean isInsideGateColumn(Location location, BlockPosition gateBlock) {
        if (gateBlock == null || location.getWorld() == null || !gateBlock.world().equals(location.getWorld().getName())) return false;
        if (location.getBlockX() != gateBlock.x() || location.getBlockZ() != gateBlock.z()) return false;
        double heightAboveBlock = location.getY() - (gateBlock.y() + 1.0);
        // 通常のジャンプ頂点（約1.25ブロック）も確実に拾える余裕を持たせる。
        return heightAboveBlock >= -0.1 && heightAboveBlock <= 1.5;
    }

    private void warnIntrusion(Player player) {
        player.sendTitle(messages.text("security.title"), messages.text("security.subtitle"), 10, 50, 10);
        messages.send(player, "security.message");
        sounds.play(player, "security-warning");
    }

    private void denyClosed(Player player) {
        player.sendTitle(messages.text("account.closed-title"), messages.text("account.closed-subtitle"), 10, 60, 10);
        messages.send(player, "account.closed-message");
        sounds.play(player, "error");
    }

    public void emergencyClose(Venue venue) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean wasAdmitted = isAdmitted(player, venue);
            if (!wasAdmitted && !insideExitGrace(venue, player.getLocation())) continue;
            UUID playerId = player.getUniqueId();
            menus.remove(playerId);
            player.closeInventory();
            admitted.remove(playerId);
            standingGate.remove(playerId);
            lastMenuAt.remove(playerId);
            pendingPassEvictions.remove(playerId);
            pendingPassAdmissions.remove(playerId);
            if (wasAdmitted) departureListener.accept(player, venue);

            Location destination = logoutDestination(venue, player);
            if (destination == null || !player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                messages.send(player, "errors.logout-teleport-failed");
                sounds.play(player, "error");
                continue;
            }
            player.sendTitle(messages.text("account.emergency-title"), messages.text("account.emergency-subtitle"), 10, 80, 20);
            messages.send(player, "account.emergency-message");
            sounds.play(player, "security-warning");
        }
    }

    private void markExited(Player player, Venue venue) {
        if (!isAdmitted(player, venue)) return;
        admitted.remove(player.getUniqueId());
        String profit = departureProfit.apply(player, venue);
        departureListener.accept(player, venue);
        String fee = CurrencyFormatter.format(economy, venue.fee());
        if (passManager.hasActivePass(player, venue)) {
            String pass = passManager.activePassName(player, venue);
            player.sendTitle(messages.text("exit.left-title"), messages.text("exit.profit-subtitle", Map.of("profit", profit)), 10, 60, 10);
            messages.send(player, "exit.pass-left-message", Map.of("pass", pass));
        } else {
            player.sendTitle(messages.text("exit.left-title"), messages.text("exit.profit-subtitle", Map.of("profit", profit)), 10, 60, 10);
            messages.send(player, "exit.left-message", Map.of("fee", fee));
        }
        sounds.play(player, "exit-success");
    }

    private boolean isExitMenuOpen(Player player) {
        MenuContext context = menus.get(player.getUniqueId());
        return context != null
                && context.type() == MenuType.EXIT
                && context.inventory().equals(player.getOpenInventory().getTopInventory());
    }

    private boolean insideExitGrace(Venue venue, Location location) {
        int margin = Math.max(0, plugin.getConfig().getInt("exit-grace-blocks", 1));
        return venue.contains(location, margin);
    }

    private Location logoutDestination(Venue venue, Player player) {
        Location configured = venue.logoutDestination() == null ? null : venue.logoutDestination().toBukkit();
        if (configured != null && venueAt(configured) == null) return configured;
        for (Gate gate : venue.gates().values()) {
            if (gate.exitDestination() == null) continue;
            Location gateExit = gate.exitDestination().toBukkit();
            if (gateExit != null && venueAt(gateExit) == null) return gateExit;
        }
        Location spawn = player.getWorld().getSpawnLocation();
        return venueAt(spawn) == null ? spawn : null;
    }

    private void evictForInvalidPass(Player player, Venue venue) {
        Location destination = logoutDestination(venue, player);
        if (destination == null) {
            messages.send(player, "errors.logout-teleport-failed");
            sounds.play(player, "error");
            return;
        }
        UUID playerId = player.getUniqueId();
        admitted.remove(playerId);
        pendingPassEvictions.remove(playerId);
        player.closeInventory();
        if (!player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            admitted.put(playerId, venue.id());
            messages.send(player, "errors.logout-teleport-failed");
            sounds.play(player, "error");
            return;
        }
        departureListener.accept(player, venue);
        player.sendTitle(messages.text("passes.evicted-title"), messages.text("passes.evicted-subtitle"), 10, 60, 10);
        messages.send(player, "passes.evicted-message");
        sounds.play(player, "exit-success");
    }

    private String gateKey(Venue venue, Gate gate, Side side) {
        return venue.id() + ":" + gate.id() + ":" + side;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (!lore.isEmpty()) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getWorld().equals(b.getWorld()) && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private enum Side { ENTRANCE, EXIT }
    private enum MenuType { ENTRY, EXIT }
    private record FoundGate(Venue venue, Gate gate, Side side) {}
    private record MenuContext(MenuType type, Venue venue, Gate gate, Inventory inventory) {}
}
