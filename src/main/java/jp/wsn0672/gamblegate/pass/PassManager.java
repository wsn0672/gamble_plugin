package jp.wsn0672.gamblegate.pass;

import jp.wsn0672.gamblegate.account.CasinoAccountManager;
import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.config.CurrencyFormatter;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.SoundService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.Venue;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

public final class PassManager implements Listener, Runnable {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault());
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final SoundService sounds;
    private final Economy economy;
    private final CasinoAccountManager accounts;
    private final File file;
    private final Map<UUID, MenuContext> menus = new HashMap<>();
    private final Set<String> notifiedExpirations = new HashSet<>();
    private YamlConfiguration data;
    private BiPredicate<Player, Venue> admissionChecker = (player, venue) -> false;
    private BiConsumer<UUID, Venue> invalidationHandler = (playerId, venue) -> {};

    public PassManager(GambleGatePlugin plugin, VenueRepository venues, MessageService messages, SoundService sounds, Economy economy, CasinoAccountManager accounts) {
        this.plugin = plugin; this.venues = venues; this.messages = messages; this.sounds = sounds; this.economy = economy;
        this.accounts = accounts;
        this.file = new File(plugin.getDataFolder(), "passes.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public boolean hasActivePass(Player player, Venue venue) {
        return expiresAt(player.getUniqueId(), venue.id()) > System.currentTimeMillis() && type(player.getUniqueId(), venue.id()) != null;
    }

    public String activePassName(Player player, Venue venue) {
        PassType type = type(player.getUniqueId(), venue.id());
        if (type == null) return "";
        if (type == PassType.SUBSCRIPTION && !autoRenew(player.getUniqueId(), venue.id())) {
            return messages.text("passes.subscription-cancelled-name");
        }
        return messages.text(type.messagePath());
    }

    public long activePassExpiresAt(Player player, Venue venue) {
        return hasActivePass(player, venue) ? expiresAt(player.getUniqueId(), venue.id()) : 0;
    }

    public double bgmVolumeMultiplier(UUID playerId) {
        return switch (bgmMode(playerId)) {
            case ON -> 1.0;
            case QUIET -> Math.max(0, Math.min(1, plugin.getConfig().getDouble("casino-bgm.quiet-volume-multiplier", 0.35)));
            case OFF -> 0.0;
        };
    }

    public boolean hasActiveSubscription(Player player, Venue venue) {
        return type(player.getUniqueId(), venue.id()) == PassType.SUBSCRIPTION
                && autoRenew(player.getUniqueId(), venue.id())
                && expiresAt(player.getUniqueId(), venue.id()) > System.currentTimeMillis();
    }

    public boolean revokePass(UUID playerId, String venueId) {
        if (type(playerId, venueId) == null) return false;
        clearPass(playerId, venueId);
        save();
        Venue venue = venues.get(venueId);
        if (venue != null) invalidationHandler.accept(playerId, venue);
        return true;
    }

    public boolean expirePass(UUID playerId, String venueId) {
        PassType passType = type(playerId, venueId);
        if (passType == null) return false;
        data.set(base(playerId, venueId) + ".expires-at", System.currentTimeMillis() - 1);
        save();
        Venue venue = venues.get(venueId);
        if (venue != null) notifyExpiration(playerId, venue, passType);
        return true;
    }

    public void reload() {
        data = YamlConfiguration.loadConfiguration(file);
        menus.clear();
        notifiedExpirations.clear();
    }

    public void setAdmissionChecker(BiPredicate<Player, Venue> admissionChecker) {
        this.admissionChecker = admissionChecker;
    }

    public void setInvalidationHandler(BiConsumer<UUID, Venue> invalidationHandler) {
        this.invalidationHandler = invalidationHandler;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Player player : Bukkit.getOnlinePlayers()) for (Venue venue : venues.all()) {
            UUID playerId = player.getUniqueId();
            PassType passType = type(playerId, venue.id());
            if (passType == null || expiresAt(playerId, venue.id()) > now) continue;
            boolean waitingForNextLoginRenewal = passType == PassType.SUBSCRIPTION && autoRenew(playerId, venue.id());
            if (!waitingForNextLoginRenewal) {
                clearPass(playerId, venue.id());
                changed = true;
            }
            notifyExpiration(playerId, venue, passType);
        }
        if (changed) save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        processRenewals(event.getPlayer());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        BlockPosition clicked = new BlockPosition(event.getClickedBlock().getWorld().getName(), event.getClickedBlock().getX(), event.getClickedBlock().getY(), event.getClickedBlock().getZ());
        Venue bgmVenue = venues.all().stream().filter(v -> v.bgmButtons().contains(clicked)).findFirst().orElse(null);
        if (bgmVenue != null) {
            event.setCancelled(true);
            if (!admissionChecker.test(event.getPlayer(), bgmVenue)) {
                messages.send(event.getPlayer(), "passes.bgm-must-be-admitted");
                sounds.play(event.getPlayer(), "error");
                return;
            }
            cycleBgmMode(event.getPlayer());
            return;
        }
        Venue venue = venues.all().stream().filter(v -> v.passMachines().contains(clicked)).findFirst().orElse(null);
        if (venue == null) return;
        event.setCancelled(true);
        if (!admissionChecker.test(event.getPlayer(), venue)) {
            messages.send(event.getPlayer(), "passes.must-be-admitted");
            sounds.play(event.getPlayer(), "error");
            return;
        }
        openMenu(event.getPlayer(), venue);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MenuContext context = menus.get(player.getUniqueId());
        if (context == null || !context.inventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        if (context.mode() == MenuMode.SUBSCRIPTION_MANAGE) {
            if (event.getRawSlot() == 15) cancelSubscription(player, context.venue());
            else if (event.getRawSlot() == 22) player.closeInventory();
            return;
        }
        if (context.mode() == MenuMode.ACTIVE_PASS) {
            if (event.getRawSlot() == 22) player.closeInventory();
            return;
        }
        if (context.mode() == MenuMode.PURCHASE_CONFIRM) {
            if (event.getRawSlot() == 15 && context.selectedType() != null) purchase(player, context.venue(), context.selectedType());
            else if (event.getRawSlot() == 11) openMenu(player, context.venue());
            else if (event.getRawSlot() == 22) player.closeInventory();
            return;
        }
        PassType type = switch (event.getRawSlot()) {
            case 11 -> PassType.ONE_TIME;
            case 13 -> PassType.SUBSCRIPTION;
            case 15 -> PassType.TRIAL;
            default -> null;
        };
        if (type != null) openPurchaseConfirmation(player, context.venue(), type);
        else if (event.getRawSlot() == 22) player.closeInventory();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        MenuContext context = menus.get(event.getPlayer().getUniqueId());
        if (context != null && context.inventory().equals(event.getInventory())) menus.remove(event.getPlayer().getUniqueId());
    }

    private void openMenu(Player player, Venue venue) {
        if (hasActiveSubscription(player, venue)) {
            openSubscriptionManagement(player, venue);
            return;
        }
        if (hasActivePass(player, venue)) {
            openActivePassStatus(player, venue);
            return;
        }
        Inventory inventory = Bukkit.createInventory(null, 27, messages.text("passes.gui-title"));
        inventory.setItem(11, passItem(Material.PAPER, PassType.ONE_TIME, venue, player));
        inventory.setItem(13, passItem(Material.DIAMOND, PassType.SUBSCRIPTION, venue, player));
        inventory.setItem(15, passItem(Material.CLOCK, PassType.TRIAL, venue, player));
        inventory.setItem(22, item(Material.BARRIER, messages.text("passes.cancel-name"), List.of()));
        menus.put(player.getUniqueId(), new MenuContext(venue, inventory, MenuMode.PURCHASE, null));
        player.openInventory(inventory);
        sounds.play(player, "gui-open");
    }

    private void openSubscriptionManagement(Player player, Venue venue) {
        long expires = expiresAt(player.getUniqueId(), venue.id());
        Inventory inventory = Bukkit.createInventory(null, 27, messages.text("passes.manage-title"));
        inventory.setItem(13, item(Material.CLOCK, messages.text("passes.subscription-status-name"),
                List.of(messages.text("passes.expires-lore", Map.of("expires", format(expires))))));
        inventory.setItem(15, item(Material.BARRIER, messages.text("passes.cancel-subscription-name"),
                List.of(messages.text("passes.cancel-subscription-lore"))));
        inventory.setItem(22, item(Material.OAK_DOOR, messages.text("passes.cancel-name"), List.of()));
        menus.put(player.getUniqueId(), new MenuContext(venue, inventory, MenuMode.SUBSCRIPTION_MANAGE, null));
        player.openInventory(inventory);
        sounds.play(player, "gui-open");
    }

    private void openActivePassStatus(Player player, Venue venue) {
        long expires = expiresAt(player.getUniqueId(), venue.id());
        Inventory inventory = Bukkit.createInventory(null, 27, messages.text("passes.active-title"));
        inventory.setItem(13, item(Material.CLOCK, messages.text("passes.active-status-name"), List.of(
                activePassName(player, venue),
                messages.text("passes.expires-lore", Map.of("expires", format(expires)))
        )));
        inventory.setItem(22, item(Material.OAK_DOOR, messages.text("passes.cancel-name"), List.of()));
        menus.put(player.getUniqueId(), new MenuContext(venue, inventory, MenuMode.ACTIVE_PASS, null));
        player.openInventory(inventory);
        sounds.play(player, "gui-open");
    }

    private void openPurchaseConfirmation(Player player, Venue venue, PassType type) {
        if (hasActivePass(player, venue)) {
            showActivePassBlocked(player, venue);
            openMenu(player, venue);
            return;
        }
        long price = price(venue, type);
        Inventory inventory = Bukkit.createInventory(null, 27, messages.text("passes.confirm-title"));
        inventory.setItem(11, item(Material.BARRIER, messages.text("passes.back-name"), List.of()));
        List<String> lore = new ArrayList<>();
        lore.add(messages.text(type.messagePath()));
        lore.add(messages.text("passes.price-lore", Map.of("price", CurrencyFormatter.format(economy, price))));
        if (type == PassType.SUBSCRIPTION) lore.add(messages.text("passes.subscription-lore"));
        if (type == PassType.TRIAL) lore.add(messages.text("passes.trial-lore"));
        inventory.setItem(15, item(Material.EMERALD, messages.text("passes.confirm-purchase-name"), lore));
        inventory.setItem(22, item(Material.OAK_DOOR, messages.text("passes.cancel-name"), List.of()));
        menus.put(player.getUniqueId(), new MenuContext(venue, inventory, MenuMode.PURCHASE_CONFIRM, type));
        player.openInventory(inventory);
        sounds.play(player, "gui-open");
    }

    private ItemStack passItem(Material material, PassType type, Venue venue, Player player) {
        long price = price(venue, type);
        List<String> lore = new ArrayList<>();
        lore.add(messages.text("passes.price-lore", Map.of("price", CurrencyFormatter.format(economy, price))));
        if (type == PassType.SUBSCRIPTION) lore.add(messages.text("passes.subscription-lore"));
        if (type == PassType.TRIAL) lore.add(messages.text("passes.trial-lore"));
        if (hasActivePass(player, venue)) lore.add(messages.text("passes.expires-lore", Map.of("expires", format(expiresAt(player.getUniqueId(), venue.id())))));
        return item(material, messages.text(type.messagePath()), lore);
    }

    private void purchase(Player player, Venue venue, PassType type) {
        if (!accounts.isOpen(venue)) {
            player.closeInventory();
            messages.send(player, "account.purchase-closed");
            sounds.play(player, "error");
            return;
        }
        if (hasActivePass(player, venue)) {
            showActivePassBlocked(player, venue);
            openMenu(player, venue);
            return;
        }
        if (type == PassType.TRIAL && trialUsed(player.getUniqueId(), venue.id())) {
            messages.send(player, "passes.trial-used"); sounds.play(player, "error"); return;
        }
        long price = price(venue, type);
        if (!economy.has(player, price)) {
            messages.send(player, "passes.insufficient-funds", Map.of("price", CurrencyFormatter.format(economy, price))); sounds.play(player, "error"); return;
        }
        EconomyResponse response = economy.withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            messages.send(player, "passes.payment-failed"); sounds.play(player, "error"); return;
        }
        long now = System.currentTimeMillis();
        long expires = Math.max(now, expiresAt(player.getUniqueId(), venue.id())) + type.durationMillis();
        setPass(player.getUniqueId(), venue.id(), type, expires);
        if (type == PassType.TRIAL) setTrialUsed(player.getUniqueId(), venue.id(), true);
        save();
        accounts.recordIncome(venue, player, price, "pass-purchase");
        player.closeInventory();
        String passName = messages.text(type.messagePath());
        Map<String, Object> replacements = Map.of("pass", passName, "price", CurrencyFormatter.format(economy, price), "expires", format(expires));
        messages.send(player, "passes.purchased", replacements);
        player.sendTitle(messages.text("passes.purchase-title", replacements), messages.text("passes.purchase-subtitle", replacements), 10, 60, 10);
        sounds.play(player, "entry-success");
    }

    private void showActivePassBlocked(Player player, Venue venue) {
        messages.send(player, "passes.active-pass-blocks-purchase", Map.of(
                "pass", activePassName(player, venue),
                "expires", format(expiresAt(player.getUniqueId(), venue.id()))
        ));
        sounds.play(player, "error");
    }

    private void cancelSubscription(Player player, Venue venue) {
        if (!hasActiveSubscription(player, venue)) {
            player.closeInventory();
            return;
        }
        long expires = expiresAt(player.getUniqueId(), venue.id());
        setAutoRenew(player.getUniqueId(), venue.id(), false);
        save();
        player.closeInventory();
        messages.send(player, "passes.subscription-cancelled", Map.of("venue", venue.id(), "expires", format(expires)));
        sounds.play(player, "exit-success");
    }

    private void processRenewals(Player player) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Venue venue : venues.all()) {
            PassType type = type(player.getUniqueId(), venue.id());
            long expires = expiresAt(player.getUniqueId(), venue.id());
            if (type == null || expires > now) continue;
            if (type == PassType.SUBSCRIPTION && autoRenew(player.getUniqueId(), venue.id())) {
                long price = venue.subscriptionPassPrice();
                if (economy.has(player, price)) {
                    EconomyResponse response = economy.withdrawPlayer(player, price);
                    if (response.transactionSuccess()) {
                        long next = now + PassType.SUBSCRIPTION.durationMillis();
                        setPass(player.getUniqueId(), venue.id(), PassType.SUBSCRIPTION, next);
                        accounts.recordIncome(venue, player, price, "pass-renewal");
                        messages.send(player, "passes.subscription-renewed", Map.of("venue", venue.id(), "price", CurrencyFormatter.format(economy, price), "expires", format(next)));
                        changed = true;
                        continue;
                    }
                }
                clearPass(player.getUniqueId(), venue.id());
                messages.send(player, "passes.subscription-expired", Map.of("venue", venue.id(), "price", CurrencyFormatter.format(economy, price)));
                sounds.play(player, "error");
            } else {
                clearPass(player.getUniqueId(), venue.id());
                messages.send(player, "passes.pass-expired", Map.of("venue", venue.id(), "pass", messages.text(type.messagePath())));
            }
            changed = true;
        }
        if (changed) save();
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta(); meta.setDisplayName(name); if (!lore.isEmpty()) meta.setLore(lore); item.setItemMeta(meta);
        return item;
    }

    private void cycleBgmMode(Player player) {
        BgmMode next = bgmMode(player.getUniqueId()).next();
        data.set(playerSettingsBase(player.getUniqueId()) + ".bgm-mode", next.name());
        save();
        messages.send(player, "passes.bgm-changed", Map.of("mode", messages.text(next.messagePath())));
        sounds.play(player, "gui-open");
    }

    private long price(Venue venue, PassType type) {
        return switch (type) { case ONE_TIME -> venue.oneTimePassPrice(); case SUBSCRIPTION -> venue.subscriptionPassPrice(); case TRIAL -> venue.trialPassPrice(); };
    }
    private String base(UUID uuid, String venue) { return "players." + uuid + ".venues." + venue.toLowerCase(); }
    private String playerSettingsBase(UUID uuid) { return "players." + uuid + ".settings"; }
    private BgmMode bgmMode(UUID uuid) {
        String raw = data.getString(playerSettingsBase(uuid) + ".bgm-mode", BgmMode.ON.name());
        if (raw == null) return BgmMode.ON;
        try { return BgmMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return BgmMode.ON; }
    }
    private PassType type(UUID uuid, String venue) { String raw = data.getString(base(uuid, venue) + ".type"); try { return raw == null ? null : PassType.valueOf(raw); } catch (IllegalArgumentException e) { return null; } }
    private long expiresAt(UUID uuid, String venue) { return data.getLong(base(uuid, venue) + ".expires-at", 0); }
    private boolean trialUsed(UUID uuid, String venue) { return data.getBoolean(base(uuid, venue) + ".trial-used", false); }
    private boolean autoRenew(UUID uuid, String venue) { return data.getBoolean(base(uuid, venue) + ".auto-renew", type(uuid, venue) == PassType.SUBSCRIPTION); }
    private void setTrialUsed(UUID uuid, String venue, boolean value) { data.set(base(uuid, venue) + ".trial-used", value); }
    private void setAutoRenew(UUID uuid, String venue, boolean value) { data.set(base(uuid, venue) + ".auto-renew", value); }
    private void setPass(UUID uuid, String venue, PassType type, long expires) { data.set(base(uuid, venue) + ".type", type.name()); data.set(base(uuid, venue) + ".expires-at", expires); setAutoRenew(uuid, venue, type == PassType.SUBSCRIPTION); notifiedExpirations.remove(expirationKey(uuid, venue)); }
    private void clearPass(UUID uuid, String venue) { data.set(base(uuid, venue) + ".type", null); data.set(base(uuid, venue) + ".expires-at", null); data.set(base(uuid, venue) + ".auto-renew", null); }
    private String format(long timestamp) { return DATE_FORMAT.format(Instant.ofEpochMilli(timestamp)); }
    private String expirationKey(UUID uuid, String venue) { return uuid + ":" + venue.toLowerCase(); }

    private void notifyExpiration(UUID playerId, Venue venue, PassType passType) {
        if (!notifiedExpirations.add(expirationKey(playerId, venue.id()))) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            messages.send(player, "passes.pass-expired", Map.of("venue", venue.id(), "pass", messages.text(passType.messagePath())));
            sounds.play(player, "error");
        }
        invalidationHandler.accept(playerId, venue);
    }

    private void save() {
        File temporary = new File(plugin.getDataFolder(), "passes.yml.tmp");
        try {
            data.save(temporary);
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) { plugin.getLogger().severe("passes.yml の保存に失敗しました: " + exception.getMessage()); }
    }

    private enum MenuMode { PURCHASE, PURCHASE_CONFIRM, ACTIVE_PASS, SUBSCRIPTION_MANAGE }
    private enum BgmMode {
        ON("passes.bgm-mode-on"), QUIET("passes.bgm-mode-quiet"), OFF("passes.bgm-mode-off");

        private final String messagePath;
        BgmMode(String messagePath) { this.messagePath = messagePath; }
        private String messagePath() { return messagePath; }
        private BgmMode next() { return switch (this) { case ON -> QUIET; case QUIET -> OFF; case OFF -> ON; }; }
    }
    private record MenuContext(Venue venue, Inventory inventory, MenuMode mode, PassType selectedType) {}
}
