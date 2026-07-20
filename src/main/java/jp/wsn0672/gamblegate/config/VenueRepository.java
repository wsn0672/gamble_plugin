package jp.wsn0672.gamblegate.config;

import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.Gate;
import jp.wsn0672.gamblegate.model.HighLowMachine;
import jp.wsn0672.gamblegate.model.CrashMachine;
import jp.wsn0672.gamblegate.model.RouletteMachine;
import jp.wsn0672.gamblegate.model.RegionBox;
import jp.wsn0672.gamblegate.model.SavedLocation;
import jp.wsn0672.gamblegate.model.Venue;
import jp.wsn0672.gamblegate.model.SlotMachine;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VenueRepository {
    private final JavaPlugin plugin;
    private final Map<String, Venue> venues = new LinkedHashMap<>();

    public VenueRepository(JavaPlugin plugin) { this.plugin = plugin; }

    public void load() {
        plugin.reloadConfig();
        plugin.getConfig().options().copyDefaults(true);
        removeObsoleteConfig();
        plugin.saveConfig();
        venues.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("venues");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;
            long defaultOneTimePrice = amount(plugin.getConfig(), "pass-defaults.one-time-price", 5000);
            long defaultSubscriptionPrice = amount(plugin.getConfig(), "pass-defaults.subscription-price", 4500);
            long defaultTrialPrice = amount(plugin.getConfig(), "pass-defaults.trial-price", 500);
            Venue venue = new Venue(id, amount(section, "fee", 0));
            venue.logoutDestination(readLocation(section.getConfigurationSection("logout-destination")));
            venue.oneTimePassPrice(amount(section, "passes.one-time-price", defaultOneTimePrice));
            venue.subscriptionPassPrice(amount(section, "passes.subscription-price", defaultSubscriptionPrice));
            venue.trialPassPrice(amount(section, "passes.trial-price", defaultTrialPrice));
            ConfigurationSection regions = section.getConfigurationSection("regions");
            if (regions != null) for (String key : regions.getKeys(false)) {
                ConfigurationSection r = regions.getConfigurationSection(key);
                if (r != null) venue.regions().add(new RegionBox(r.getString("world", ""), r.getInt("min.x"), r.getInt("min.y"), r.getInt("min.z"), r.getInt("max.x"), r.getInt("max.y"), r.getInt("max.z")));
            }
            ConfigurationSection vipRegions = section.getConfigurationSection("vip-regions");
            if (vipRegions != null) for (String key : vipRegions.getKeys(false)) {
                ConfigurationSection r = vipRegions.getConfigurationSection(key);
                if (r != null) venue.vipRegions().add(new RegionBox(r.getString("world", ""), r.getInt("min.x"), r.getInt("min.y"), r.getInt("min.z"), r.getInt("max.x"), r.getInt("max.y"), r.getInt("max.z")));
            }
            ConfigurationSection gates = section.getConfigurationSection("gates");
            if (gates != null) for (String gateId : gates.getKeys(false)) {
                ConfigurationSection g = gates.getConfigurationSection(gateId);
                if (g == null) continue;
                Gate gate = new Gate(gateId);
                gate.entranceBlock(readBlock(g.getConfigurationSection("entrance.block")));
                gate.exitBlock(readBlock(g.getConfigurationSection("exit.block")));
                gate.entranceDestination(readLocation(g.getConfigurationSection("entrance.destination")));
                gate.exitDestination(readLocation(g.getConfigurationSection("exit.destination")));
                venue.gates().put(gateId.toLowerCase(), gate);
            }
            ConfigurationSection machines = section.getConfigurationSection("pass-machines");
            if (machines != null) for (String key : machines.getKeys(false)) {
                BlockPosition machine = readBlock(machines.getConfigurationSection(key));
                if (machine != null) venue.passMachines().add(machine);
            }
            ConfigurationSection bgmButtons = section.getConfigurationSection("bgm-buttons");
            if (bgmButtons != null) for (String key : bgmButtons.getKeys(false)) {
                BlockPosition button = readBlock(bgmButtons.getConfigurationSection(key));
                if (button != null) venue.bgmButtons().add(button);
            }
            ConfigurationSection vipDoors = section.getConfigurationSection("vip-doors");
            if (vipDoors != null) for (String key : vipDoors.getKeys(false)) {
                BlockPosition door = readBlock(vipDoors.getConfigurationSection(key));
                if (door != null) venue.vipDoors().add(door);
            }
            ConfigurationSection fireworkPoints = section.getConfigurationSection("firework-points");
            if (fireworkPoints != null) for (String key : fireworkPoints.getKeys(false)) {
                SavedLocation point = readLocation(fireworkPoints.getConfigurationSection(key));
                if (point != null) venue.fireworkLaunchPoints().add(point);
            }
            ConfigurationSection slots = section.getConfigurationSection("slot-machines");
            if (slots != null) for (String slotId : slots.getKeys(false)) {
                ConfigurationSection s = slots.getConfigurationSection(slotId);
                if (s == null) continue;
                SlotMachine slot = new SlotMachine(slotId);
                slot.chair(readBlock(s.getConfigurationSection("chair")));
                slot.bet(s.getLong("bet", plugin.getConfig().getLong("slot-machine.default-bet", 100)));
                venue.slotMachines().put(slotId.toLowerCase(), slot);
            }
            ConfigurationSection highLowMachines = section.getConfigurationSection("high-low-machines");
            if (highLowMachines != null) for (String machineId : highLowMachines.getKeys(false)) {
                ConfigurationSection machineSection = highLowMachines.getConfigurationSection(machineId);
                if (machineSection == null) continue;
                HighLowMachine machine = new HighLowMachine(machineId);
                machine.chair(readBlock(machineSection.getConfigurationSection("chair")));
                venue.highLowMachines().put(machineId.toLowerCase(), machine);
            }
            ConfigurationSection crashMachines = section.getConfigurationSection("crash-machines");
            if (crashMachines != null) for (String machineId : crashMachines.getKeys(false)) {
                ConfigurationSection machineSection = crashMachines.getConfigurationSection(machineId);
                if (machineSection == null) continue;
                CrashMachine machine = new CrashMachine(machineId);
                machine.chair(readBlock(machineSection.getConfigurationSection("chair")));
                venue.crashMachines().put(machineId.toLowerCase(), machine);
            }
            ConfigurationSection rouletteMachines = section.getConfigurationSection("roulette-machines");
            if (rouletteMachines != null) for (String machineId : rouletteMachines.getKeys(false)) {
                ConfigurationSection machineSection = rouletteMachines.getConfigurationSection(machineId);
                if (machineSection == null) continue;
                RouletteMachine machine = new RouletteMachine(machineId);
                machine.playButton(readBlock(machineSection.getConfigurationSection("play-button")));
                machine.viewingLocation(readLocation(machineSection.getConfigurationSection("viewing-location")));
                machine.returnLocation(readLocation(machineSection.getConfigurationSection("return-location")));
                machine.wagerDisplayLocation(readLocation(machineSection.getConfigurationSection("wager-display-location")));
                ConfigurationSection pockets = machineSection.getConfigurationSection("pockets");
                if (pockets != null) for (int i = 1; i <= 16; i++) {
                    BlockPosition pocket = readBlock(pockets.getConfigurationSection(String.valueOf(i)));
                    if (pocket != null) machine.pockets().add(pocket);
                }
                venue.rouletteMachines().put(machineId.toLowerCase(), machine);
            }
            ConfigurationSection guideSigns = section.getConfigurationSection("game-guide-signs");
            if (guideSigns != null) for (String game : guideSigns.getKeys(false)) {
                ConfigurationSection signs = guideSigns.getConfigurationSection(game);
                if (signs == null) continue;
                for (String index : signs.getKeys(false)) {
                    BlockPosition sign = readBlock(signs.getConfigurationSection(index));
                    if (sign != null) venue.gameGuideSigns().computeIfAbsent(game.toLowerCase(), ignored -> new java.util.ArrayList<>()).add(sign);
                }
            }
            venues.put(id.toLowerCase(), venue);
        }
    }

    public Collection<Venue> all() { return venues.values(); }
    public Venue get(String id) { return venues.get(id.toLowerCase()); }
    public boolean add(Venue venue) { return venues.putIfAbsent(venue.id().toLowerCase(), venue) == null; }
    public Venue remove(String id) { return venues.remove(id.toLowerCase()); }

    public void save() {
        plugin.getConfig().set("venues", null);
        for (Venue venue : venues.values()) {
            String base = "venues." + venue.id();
            plugin.getConfig().set(base + ".fee", venue.fee());
            plugin.getConfig().set(base + ".passes.one-time-price", venue.oneTimePassPrice());
            plugin.getConfig().set(base + ".passes.subscription-price", venue.subscriptionPassPrice());
            plugin.getConfig().set(base + ".passes.trial-price", venue.trialPassPrice());
            writeLocation(base + ".logout-destination", venue.logoutDestination());
            for (int i = 0; i < venue.regions().size(); i++) {
                RegionBox r = venue.regions().get(i);
                String path = base + ".regions." + i;
                plugin.getConfig().set(path + ".world", r.world());
                plugin.getConfig().set(path + ".min.x", r.minX()); plugin.getConfig().set(path + ".min.y", r.minY()); plugin.getConfig().set(path + ".min.z", r.minZ());
                plugin.getConfig().set(path + ".max.x", r.maxX()); plugin.getConfig().set(path + ".max.y", r.maxY()); plugin.getConfig().set(path + ".max.z", r.maxZ());
            }
            for (int i = 0; i < venue.vipRegions().size(); i++) {
                RegionBox r = venue.vipRegions().get(i);
                String path = base + ".vip-regions." + i;
                plugin.getConfig().set(path + ".world", r.world());
                plugin.getConfig().set(path + ".min.x", r.minX()); plugin.getConfig().set(path + ".min.y", r.minY()); plugin.getConfig().set(path + ".min.z", r.minZ());
                plugin.getConfig().set(path + ".max.x", r.maxX()); plugin.getConfig().set(path + ".max.y", r.maxY()); plugin.getConfig().set(path + ".max.z", r.maxZ());
            }
            for (Gate gate : venue.gates().values()) {
                String path = base + ".gates." + gate.id();
                writeBlock(path + ".entrance.block", gate.entranceBlock());
                writeBlock(path + ".exit.block", gate.exitBlock());
                writeLocation(path + ".entrance.destination", gate.entranceDestination());
                writeLocation(path + ".exit.destination", gate.exitDestination());
            }
            for (int i = 0; i < venue.passMachines().size(); i++) {
                writeBlock(base + ".pass-machines." + i, venue.passMachines().get(i));
            }
            for (int i = 0; i < venue.bgmButtons().size(); i++) {
                writeBlock(base + ".bgm-buttons." + i, venue.bgmButtons().get(i));
            }
            for (int i = 0; i < venue.vipDoors().size(); i++) writeBlock(base + ".vip-doors." + i, venue.vipDoors().get(i));
            for (int i = 0; i < venue.fireworkLaunchPoints().size(); i++) {
                writeLocation(base + ".firework-points." + i, venue.fireworkLaunchPoints().get(i));
            }
            for (SlotMachine slot : venue.slotMachines().values()) {
                String path = base + ".slot-machines." + slot.id();
                writeBlock(path + ".chair", slot.chair());
                plugin.getConfig().set(path + ".bet", slot.bet());
            }
            for (HighLowMachine machine : venue.highLowMachines().values()) {
                String path = base + ".high-low-machines." + machine.id();
                writeBlock(path + ".chair", machine.chair());
            }
            for (CrashMachine machine : venue.crashMachines().values()) {
                String path = base + ".crash-machines." + machine.id();
                writeBlock(path + ".chair", machine.chair());
            }
            for (RouletteMachine machine : venue.rouletteMachines().values()) {
                String path = base + ".roulette-machines." + machine.id();
                writeBlock(path + ".play-button", machine.playButton());
                writeLocation(path + ".viewing-location", machine.viewingLocation());
                writeLocation(path + ".return-location", machine.returnLocation());
                writeLocation(path + ".wager-display-location", machine.wagerDisplayLocation());
                for (int i = 0; i < machine.pockets().size(); i++)
                    writeBlock(path + ".pockets." + (i + 1), machine.pockets().get(i));
            }
            for (var entry : venue.gameGuideSigns().entrySet()) for (int i = 0; i < entry.getValue().size(); i++)
                writeBlock(base + ".game-guide-signs." + entry.getKey() + "." + i, entry.getValue().get(i));
        }
        plugin.saveConfig();
    }

    private void removeObsoleteConfig() {
        double highLowHeight = plugin.getConfig().getDouble("high-low.card-display-y-offset", 0.22);
        if (Math.abs(highLowHeight - 0.55) < 0.000001 || Math.abs(highLowHeight - 0.42) < 0.000001)
            plugin.getConfig().set("high-low.card-display-y-offset", 0.22);
        if (Math.abs(plugin.getConfig().getDouble("high-low.card-display-pitch", -10) + 30) < 0.000001)
            plugin.getConfig().set("high-low.card-display-pitch", -10);
        if (Math.abs(plugin.getConfig().getDouble("crash.display-y-offset", 0.22) - 0.65) < 0.000001)
            plugin.getConfig().set("crash.display-y-offset", 0.22);
        if (Math.abs(plugin.getConfig().getDouble("crash.display-pitch", -10) + 15) < 0.000001)
            plugin.getConfig().set("crash.display-pitch", -10);
        if (Math.abs(plugin.getConfig().getDouble("high-low.proximity-distance", 30) - 10) < 0.000001)
            plugin.getConfig().set("high-low.proximity-distance", 30);
        if (Math.abs(plugin.getConfig().getDouble("crash.proximity-distance", 30) - 10) < 0.000001)
            plugin.getConfig().set("crash.proximity-distance", 30);
        if (plugin.getConfig().getInt("crash.minimum-cashout-centi", 120) == 110)
            plugin.getConfig().set("crash.minimum-cashout-centi", 120);
        if (Math.abs(plugin.getConfig().getDouble("crash.growth-centi-per-update", 1.0 / 3.0) - 1.0) < 0.000001)
            plugin.getConfig().set("crash.growth-centi-per-update", 0.3333333333);
        // 全ゲームを基準RTP 1.0へ移行。旧ハイ＆ロー補正値は公平配当式では不要です。
        plugin.getConfig().set("high-low.house-edge", null);
        plugin.getConfig().set("high-low.initial-pot-multiplier", null);
        plugin.getConfig().set("high-low.streak-start-multiplier", null);
        plugin.getConfig().set("high-low.streak-bonus-per-win", null);
        plugin.getConfig().set("high-low.streak-bonus-max-multiplier", null);
        plugin.getConfig().set("slot-machine.dynamic-odds.maximum-weight-adjustment", null);
        double crashRtp = plugin.getConfig().getDouble("crash.rtp", 1.0);
        if (Math.abs(crashRtp - 0.88) < 0.000001 || Math.abs(crashRtp - 0.95) < 0.000001)
            plugin.getConfig().set("crash.rtp", 1.0);
        double crashAdjustment = plugin.getConfig().getDouble("crash.dynamic-odds.maximum-rtp-adjustment", 0.02);
        if (Math.abs(crashAdjustment - 0.03) < 0.000001 || Math.abs(crashAdjustment - 0.04) < 0.000001)
            plugin.getConfig().set("crash.dynamic-odds.maximum-rtp-adjustment", 0.02);
        if (Math.abs(plugin.getConfig().getDouble("roulette.payouts.half", 2.0) - 1.75) < 0.000001)
            plugin.getConfig().set("roulette.payouts.half", 2.0);
        if (Math.abs(plugin.getConfig().getDouble("roulette.payouts.quarter", 4.0) - 3.5) < 0.000001)
            plugin.getConfig().set("roulette.payouts.quarter", 4.0);
        if (Math.abs(plugin.getConfig().getDouble("roulette.payouts.exact", 16.0) - 14.0) < 0.000001)
            plugin.getConfig().set("roulette.payouts.exact", 16.0);
        // 16ポケットの公平配当（2/4/16倍）ではRTP 1.0が期待払戻し1倍です。
        if (Math.abs(plugin.getConfig().getDouble("roulette.rtp", 1.0) - 0.875) < 0.000001)
            plugin.getConfig().set("roulette.rtp", 1.0);
        if (Math.abs(plugin.getConfig().getDouble("roulette.dynamic-odds.maximum-rtp-adjustment", 0.02) - 0.03) < 0.000001)
            plugin.getConfig().set("roulette.dynamic-odds.maximum-rtp-adjustment", 0.02);
        plugin.getConfig().set("casino-account.initial-balance", null);
        plugin.getConfig().set("casino-account.emergency-stop-balance", null);
        ConfigurationSection oldBgm = plugin.getConfig().getConfigurationSection("high-low.bgm");
        if (oldBgm != null) {
            plugin.getConfig().set("casino-bgm.enabled", oldBgm.getBoolean("enabled", true));
            double oldVolume = oldBgm.getDouble("volume", 0.35);
            plugin.getConfig().set("casino-bgm.volume", Math.abs(oldVolume - 0.35) < 0.000001 ? 0.65 : oldVolume);
            plugin.getConfig().set("casino-bgm.max-notes-per-tick", oldBgm.getInt("max-notes-per-tick", 12));
            plugin.getConfig().set("high-low.bgm", null);
        }
        plugin.getConfig().set("casino-bgm.hearing-distance", null);
        plugin.getConfig().set("roof-fireworks.min-delay-ticks", null);
        plugin.getConfig().set("roof-fireworks.max-delay-ticks", null);
        plugin.getConfig().set("roof-fireworks.flight-ticks", null);
        plugin.getConfig().set("roof-fireworks.large-ball-points", null);
        plugin.getConfig().set("roof-fireworks.min-shots", null);
        plugin.getConfig().set("roof-fireworks.max-shots", null);
        plugin.getConfig().set("crash.sound-interval-ticks", null);
        plugin.getConfig().set("vip.max-guests-per-host", null);
        for (String path : new String[]{
                "sounds.slot-jackpot",
                "sounds.slot-iron",
                "sounds.slot-emerald",
                "sounds.slot-diamond",
                "sounds.gold-rush"
        }) plugin.getConfig().set(path, null);

        ConfigurationSection venueRoot = plugin.getConfig().getConfigurationSection("venues");
        if (venueRoot == null) return;
        for (String venueId : venueRoot.getKeys(false)) {
            ConfigurationSection slots = venueRoot.getConfigurationSection(venueId + ".slot-machines");
            if (slots != null) for (String slotId : slots.getKeys(false)) {
                slots.set(slotId + ".shelf", null);
                slots.set(slotId + ".lever", null);
            }
            ConfigurationSection highLow = venueRoot.getConfigurationSection(venueId + ".high-low-machines");
            if (highLow != null) for (String machineId : highLow.getKeys(false)) highLow.set(machineId + ".bet", null);
        }
    }

    private BlockPosition readBlock(ConfigurationSection s) {
        return s == null ? null : new BlockPosition(s.getString("world", ""), s.getInt("x"), s.getInt("y"), s.getInt("z"));
    }
    private SavedLocation readLocation(ConfigurationSection s) {
        return s == null ? null : new SavedLocation(s.getString("world", ""), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }
    private long amount(ConfigurationSection section, String path, long fallback) {
        Object raw = section.get(path);
        if (!(raw instanceof Number number)) return fallback;
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0) return fallback;
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(value);
    }
    private void writeBlock(String path, BlockPosition p) {
        if (p == null) return;
        plugin.getConfig().set(path + ".world", p.world()); plugin.getConfig().set(path + ".x", p.x()); plugin.getConfig().set(path + ".y", p.y()); plugin.getConfig().set(path + ".z", p.z());
    }
    private void writeLocation(String path, SavedLocation l) {
        if (l == null) return;
        plugin.getConfig().set(path + ".world", l.world()); plugin.getConfig().set(path + ".x", l.x()); plugin.getConfig().set(path + ".y", l.y()); plugin.getConfig().set(path + ".z", l.z()); plugin.getConfig().set(path + ".yaw", l.yaw()); plugin.getConfig().set(path + ".pitch", l.pitch());
    }
}
