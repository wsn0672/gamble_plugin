package jp.wsn0672.gamblegate;

import jp.wsn0672.gamblegate.account.CasinoAccountManager;
import jp.wsn0672.gamblegate.command.CasinoAccountCommand;
import jp.wsn0672.gamblegate.command.HighLowCommand;
import jp.wsn0672.gamblegate.command.CrashCommand;
import jp.wsn0672.gamblegate.command.VenueCommand;
import jp.wsn0672.gamblegate.command.SlotCommand;
import jp.wsn0672.gamblegate.command.RouletteCommand;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.SoundService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.effects.RoofFireworkManager;
import jp.wsn0672.gamblegate.listener.GateListener;
import jp.wsn0672.gamblegate.highlow.HighLowManager;
import jp.wsn0672.gamblegate.crash.CrashManager;
import jp.wsn0672.gamblegate.listener.MobProtectionListener;
import jp.wsn0672.gamblegate.music.CasinoBgmManager;
import jp.wsn0672.gamblegate.guide.GameGuideManager;
import jp.wsn0672.gamblegate.vip.VipAccessManager;
import jp.wsn0672.gamblegate.pass.PassManager;
import jp.wsn0672.gamblegate.pass.PassMachineEffects;
import jp.wsn0672.gamblegate.scoreboard.CasinoScoreboardManager;
import jp.wsn0672.gamblegate.slot.SlotManager;
import jp.wsn0672.gamblegate.roulette.RouletteManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class GambleGatePlugin extends JavaPlugin {
    private MessageService messages;
    private VenueRepository venues;
    private Economy economy;
    private SlotManager slotManager;
    private HighLowManager highLowManager;
    private CrashManager crashManager;
    private RouletteManager rouletteManager;
    private CasinoScoreboardManager casinoScoreboard;
    private CasinoBgmManager casinoBgm;
    private PassMachineEffects passMachineEffects;
    private VipAccessManager vipAccessManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new MessageService(this);
        messages.load();
        venues = new VenueRepository(this);
        venues.load();
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            getLogger().severe("Vault対応の経済プラグインが見つからないため無効化します。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = registration.getProvider();
        CasinoAccountManager accounts = new CasinoAccountManager(this);
        accounts.ensureVenues(venues.all());
        SoundService sounds = new SoundService(this);
        PassManager passManager = new PassManager(this, venues, messages, sounds, economy, accounts);
        getServer().getPluginManager().registerEvents(passManager, this);
        getServer().getScheduler().runTaskTimer(this, passManager, 20L, 20L);
        passMachineEffects = new PassMachineEffects(this, venues, messages);
        getServer().getScheduler().runTaskTimer(this, passMachineEffects, 20L, 1L);
        RoofFireworkManager roofFireworks = new RoofFireworkManager(this, venues);
        getServer().getPluginManager().registerEvents(roofFireworks, this);
        GateListener listener = new GateListener(this, venues, messages, sounds, economy, passManager, accounts);
        passManager.setAdmissionChecker(listener::isAdmitted);
        getServer().getPluginManager().registerEvents(listener, this);
        slotManager = new SlotManager(this, venues, messages, sounds, economy, accounts);
        slotManager.setAdmissionChecker(listener::isAdmitted);
        slotManager.setCompletionListener(listener::onSlotGameCompleted);
        slotManager.setHouseLossListener(roofFireworks::celebrate);
        passManager.setInvalidationHandler(listener::requestPassEviction);
        casinoScoreboard = new CasinoScoreboardManager(this, messages, economy, passManager, slotManager);
        casinoBgm = new CasinoBgmManager(this, passManager::bgmVolumeMultiplier);
        listener.setAdmissionListener((player, venue) -> {
            casinoScoreboard.enter(player, venue);
            casinoBgm.enter(player, venue);
        });
        listener.setDepartureListener((player, venue) -> {
            casinoScoreboard.leave(player, venue);
            casinoBgm.leave(player, venue);
        });
        listener.setDepartureProfit(casinoScoreboard::formattedProfit);
        slotManager.setActivityListener(casinoScoreboard);
        getServer().getPluginManager().registerEvents(casinoScoreboard, this);
        long scoreboardUpdateTicks = Math.max(10, getConfig().getLong("casino-scoreboard.update-ticks", 20));
        getServer().getScheduler().runTaskTimer(this, casinoScoreboard, scoreboardUpdateTicks, scoreboardUpdateTicks);
        getServer().getPluginManager().registerEvents(slotManager, this);
        getServer().getScheduler().runTaskTimer(this, slotManager, 1L, 1L);
        highLowManager = new HighLowManager(this, venues, messages, sounds, economy, accounts);
        highLowManager.setAdmissionChecker(listener::isAdmitted);
        highLowManager.setHouseLossListener(roofFireworks::celebrate);
        highLowManager.setCompletionListener(listener::onSlotGameCompleted);
        getServer().getPluginManager().registerEvents(highLowManager, this);
        getServer().getScheduler().runTaskTimer(this, highLowManager, 5L, 5L);
        crashManager = new CrashManager(this, venues, messages, sounds, economy, accounts);
        crashManager.setAdmissionChecker(listener::isAdmitted);
        crashManager.setHouseLossListener(roofFireworks::celebrate);
        crashManager.setCompletionListener(listener::onSlotGameCompleted);
        crashManager.setActivityListener(casinoScoreboard);
        getServer().getPluginManager().registerEvents(crashManager, this);
        getServer().getScheduler().runTaskTimer(this, crashManager, 1L, 1L);
        rouletteManager = new RouletteManager(this, venues, messages, sounds, economy, accounts);
        rouletteManager.setAdmissionChecker(listener::isAdmitted);
        rouletteManager.setHouseLossListener(roofFireworks::celebrate);
        rouletteManager.setCompletionListener(listener::onSlotGameCompleted);
        rouletteManager.setActivityListener(casinoScoreboard);
        getServer().getPluginManager().registerEvents(rouletteManager, this);
        getServer().getScheduler().runTaskTimer(this, rouletteManager, 1L, 1L);
        listener.setSlotGameChecker((player, venue) -> slotManager.isPlaying(player, venue)
                || highLowManager.isPlaying(player, venue) || crashManager.isPlaying(player, venue)
                || rouletteManager.isPlaying(player, venue));
        vipAccessManager = new VipAccessManager(this, venues, messages, sounds);
        getServer().getPluginManager().registerEvents(vipAccessManager, this);
        getServer().getScheduler().runTaskTimer(this, vipAccessManager, 1L, 2L);
        GameGuideManager gameGuides = new GameGuideManager(this, venues, messages, sounds);
        getServer().getPluginManager().registerEvents(gameGuides, this);
        getServer().getScheduler().runTask(this, gameGuides::refreshSigns);
        MobProtectionListener mobProtection = new MobProtectionListener(this, venues);
        getServer().getPluginManager().registerEvents(mobProtection, this);
        getServer().getScheduler().runTask(this, mobProtection::removeProtectedMobs);
        VenueCommand command = new VenueCommand(this, venues, messages, listener, mobProtection, passManager, slotManager, highLowManager, crashManager, accounts, vipAccessManager);
        getCommand("gamblevenue").setExecutor(command);
        getCommand("gamblevenue").setTabCompleter(command);
        SlotCommand slotCommand = new SlotCommand(venues, messages, slotManager, gameGuides);
        getCommand("slot").setExecutor(slotCommand);
        getCommand("slot").setTabCompleter(slotCommand);
        getServer().getPluginManager().registerEvents(slotCommand, this);
        HighLowCommand highLowCommand = new HighLowCommand(venues, messages, highLowManager, gameGuides);
        getCommand("highlow").setExecutor(highLowCommand);
        getCommand("highlow").setTabCompleter(highLowCommand);
        CrashCommand crashCommand = new CrashCommand(venues, messages, crashManager, gameGuides);
        getCommand("crash").setExecutor(crashCommand);
        getCommand("crash").setTabCompleter(crashCommand);
        RouletteCommand rouletteCommand = new RouletteCommand(venues, messages, rouletteManager, gameGuides);
        getCommand("roulette").setExecutor(rouletteCommand);
        getCommand("roulette").setTabCompleter(rouletteCommand);
        getServer().getPluginManager().registerEvents(rouletteCommand, this);
        CasinoAccountCommand accountCommand = new CasinoAccountCommand(venues, accounts, messages, economy);
        getCommand("casinoaccount").setExecutor(accountCommand);
        getCommand("casinoaccount").setTabCompleter(accountCommand);
    }

    public void reloadAll() {
        venues.load();
        messages.load();
    }

    @Override
    public void onDisable() {
        if (passMachineEffects != null) passMachineEffects.shutdown();
        if (casinoBgm != null) casinoBgm.shutdown();
        if (vipAccessManager != null) vipAccessManager.resetRuntime();
        if (highLowManager != null) highLowManager.resetRuntime();
        if (crashManager != null) crashManager.resetRuntime();
        if (rouletteManager != null) rouletteManager.resetRuntime();
        if (slotManager != null) slotManager.resetRuntime();
        if (casinoScoreboard != null) casinoScoreboard.shutdown();
    }
}
