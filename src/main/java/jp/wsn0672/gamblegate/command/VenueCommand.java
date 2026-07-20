package jp.wsn0672.gamblegate.command;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import jp.wsn0672.gamblegate.account.CasinoAccountManager;
import jp.wsn0672.gamblegate.GambleGatePlugin;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.listener.GateListener;
import jp.wsn0672.gamblegate.highlow.HighLowManager;
import jp.wsn0672.gamblegate.crash.CrashManager;
import jp.wsn0672.gamblegate.listener.MobProtectionListener;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.Gate;
import jp.wsn0672.gamblegate.model.RegionBox;
import jp.wsn0672.gamblegate.model.SavedLocation;
import jp.wsn0672.gamblegate.model.Venue;
import jp.wsn0672.gamblegate.pass.PassManager;
import jp.wsn0672.gamblegate.pass.PassType;
import jp.wsn0672.gamblegate.slot.SlotManager;
import jp.wsn0672.gamblegate.vip.VipAccessManager;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VenueCommand implements CommandExecutor, TabCompleter {
    private final GambleGatePlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final GateListener listener;
    private final MobProtectionListener mobProtection;
    private final PassManager passManager;
    private final SlotManager slotManager;
    private final HighLowManager highLowManager;
    private final CrashManager crashManager;
    private final CasinoAccountManager accounts;
    private final VipAccessManager vipAccess;

    public VenueCommand(GambleGatePlugin plugin, VenueRepository venues, MessageService messages, GateListener listener, MobProtectionListener mobProtection, PassManager passManager, SlotManager slotManager, HighLowManager highLowManager, CrashManager crashManager, CasinoAccountManager accounts, VipAccessManager vipAccess) {
        this.plugin = plugin; this.venues = venues; this.messages = messages; this.listener = listener; this.mobProtection = mobProtection; this.passManager = passManager; this.slotManager = slotManager; this.highLowManager = highLowManager; this.crashManager = crashManager; this.accounts = accounts; this.vipAccess = vipAccess;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gamblegate.admin")) { messages.send(sender, "errors.no-permission"); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            vipAccess.resetRuntime(); crashManager.resetRuntime(); highLowManager.resetRuntime(); slotManager.resetRuntime(); venues.load(); messages.load(); passManager.reload(); accounts.ensureVenues(venues.all()); listener.reloadRuntimeState();
            mobProtection.removeProtectedMobs();
            messages.send(sender, "admin.reloaded"); return true;
        }
        if (sub.equals("create")) return create(sender, args);
        if (sub.equals("pass")) return managePlayerPass(sender, args);
        if (args.length < 2) { help(sender); return true; }
        Venue venue = venues.get(args[1]);
        if (venue == null) { messages.send(sender, "errors.venue-not-found", Map.of("venue", args[1])); return true; }
        return switch (sub) {
            case "delete" -> delete(sender, venue);
            case "setfee" -> setFee(sender, venue, args);
            case "addregion" -> addRegion(sender, venue);
            case "clearregions" -> clearRegions(sender, venue);
            case "addvipregion" -> addVipRegion(sender, venue);
            case "clearvipregions" -> clearVipRegions(sender, venue);
            case "addvipdoor" -> addVipDoor(sender, venue);
            case "clearvipdoors" -> clearVipDoors(sender, venue);
            case "creategate" -> createGate(sender, venue, args);
            case "setgateblock" -> setGateBlock(sender, venue, args);
            case "setdestination" -> setDestination(sender, venue, args);
            case "setlogoutdestination" -> setLogoutDestination(sender, venue, args);
            case "addpassmachine" -> addPassMachine(sender, venue);
            case "clearpassmachines" -> clearPassMachines(sender, venue);
            case "addbgmbutton" -> addBgmButton(sender, venue);
            case "clearbgmbuttons" -> clearBgmButtons(sender, venue);
            case "addfireworkpoint" -> addFireworkPoint(sender, venue);
            case "clearfireworkpoints" -> clearFireworkPoints(sender, venue);
            case "setpassprice" -> setPassPrice(sender, venue, args);
            case "info" -> info(sender, venue);
            default -> { help(sender); yield true; }
        };
    }

    private boolean create(CommandSender sender, String[] args) {
        if (args.length != 3) { help(sender); return true; }
        if (!validId(args[1])) { messages.send(sender, "errors.invalid-id"); return true; }
        Long fee = amount(args[2]);
        if (fee == null) { messages.send(sender, "errors.invalid-number"); return true; }
        Venue venue = new Venue(args[1], fee);
        venue.oneTimePassPrice(configAmount("pass-defaults.one-time-price", 5000));
        venue.subscriptionPassPrice(configAmount("pass-defaults.subscription-price", 4500));
        venue.trialPassPrice(configAmount("pass-defaults.trial-price", 500));
        if (!venues.add(venue)) { messages.send(sender, "admin.already-exists"); return true; }
        venues.save();
        accounts.ensureVenues(venues.all());
        messages.send(sender, "admin.created", Map.of("venue", venue.id(), "fee", fee));
        return true;
    }

    private boolean delete(CommandSender sender, Venue venue) {
        venues.remove(venue.id()); venues.save();
        messages.send(sender, "admin.deleted", Map.of("venue", venue.id())); return true;
    }

    private boolean setFee(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 3) { help(sender); return true; }
        Long value = amount(args[2]);
        if (value == null) { messages.send(sender, "errors.invalid-number"); return true; }
        venue.fee(value); venues.save();
        messages.send(sender, "admin.fee-set", Map.of("venue", venue.id(), "fee", value)); return true;
    }

    private boolean addRegion(CommandSender sender, Venue venue) {
        if (!(sender instanceof org.bukkit.entity.Player bukkitPlayer)) { messages.send(sender, "errors.player-only"); return true; }
        try {
            Player actor = BukkitAdapter.adapt(bukkitPlayer);
            Region selection = WorldEdit.getInstance().getSessionManager().get(actor).getSelection(actor.getWorld());
            if (!(selection instanceof CuboidRegion cuboid)) { messages.send(sender, "errors.worldedit-selection"); return true; }
            String world = bukkitPlayer.getWorld().getName();
            if (!sameVenueWorld(venue, world)) { messages.send(sender, "errors.different-world"); return true; }
            var min = cuboid.getMinimumPoint(); var max = cuboid.getMaximumPoint();
            venue.regions().add(new RegionBox(world, min.x(), min.y(), min.z(), max.x(), max.y(), max.z()));
            venues.save();
            mobProtection.removeProtectedMobs();
            messages.send(sender, "admin.region-added", Map.of("count", venue.regions().size()));
        } catch (IncompleteRegionException exception) {
            messages.send(sender, "errors.worldedit-selection");
        }
        return true;
    }

    private boolean clearRegions(CommandSender sender, Venue venue) {
        venue.regions().clear(); venues.save(); messages.send(sender, "admin.regions-cleared"); return true;
    }

    private boolean addVipRegion(CommandSender sender, Venue venue) {
        if (!(sender instanceof org.bukkit.entity.Player bukkitPlayer)) { messages.send(sender, "errors.player-only"); return true; }
        try {
            Player actor = BukkitAdapter.adapt(bukkitPlayer);
            Region selection = WorldEdit.getInstance().getSessionManager().get(actor).getSelection(actor.getWorld());
            if (!(selection instanceof CuboidRegion cuboid)) { messages.send(sender, "errors.worldedit-selection"); return true; }
            String world = bukkitPlayer.getWorld().getName();
            if (!sameVenueWorld(venue, world)) { messages.send(sender, "errors.different-world"); return true; }
            var min = cuboid.getMinimumPoint(); var max = cuboid.getMaximumPoint();
            if (!vipSelectionInsideVenue(venue, world, min.x(), min.y(), min.z(), max.x(), max.y(), max.z())) {
                messages.send(sender, "errors.vip-region-outside"); return true;
            }
            venue.vipRegions().add(new RegionBox(world, min.x(), min.y(), min.z(), max.x(), max.y(), max.z()));
            venues.save(); messages.send(sender, "admin.vip-region-added", Map.of("count", venue.vipRegions().size()));
        } catch (IncompleteRegionException exception) { messages.send(sender, "errors.worldedit-selection"); }
        return true;
    }

    private boolean clearVipRegions(CommandSender sender, Venue venue) {
        venue.vipRegions().clear(); venues.save(); messages.send(sender, "admin.vip-regions-cleared"); return true;
    }

    private boolean addVipDoor(CommandSender sender, Venue venue) {
        if (!(sender instanceof org.bukkit.entity.Player player)) { messages.send(sender, "errors.player-only"); return true; }
        Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType() != Material.IRON_DOOR || !(target.getBlockData() instanceof Door door)) { messages.send(sender, "errors.look-at-iron-door"); return true; }
        if (door.getHalf() == Bisected.Half.TOP) target = target.getRelative(BlockFace.DOWN);
        if (!venue.contains(target.getLocation())) { messages.send(sender, "errors.vip-door-outside"); return true; }
        Door baseDoor = (Door) target.getBlockData();
        List<Block> targets = new ArrayList<>(); targets.add(target);
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block adjacent = target.getRelative(face);
            if (adjacent.getType() != Material.IRON_DOOR || !(adjacent.getBlockData() instanceof Door adjacentDoor)
                    || adjacentDoor.getHalf() != Bisected.Half.BOTTOM || adjacentDoor.getFacing() != baseDoor.getFacing()
                    || adjacentDoor.getHinge() == baseDoor.getHinge()) continue;
            targets.add(adjacent); break;
        }
        int added = 0;
        for (Block doorBlock : targets) {
            BlockPosition position = new BlockPosition(doorBlock.getWorld().getName(), doorBlock.getX(), doorBlock.getY(), doorBlock.getZ());
            if (!venue.vipDoors().contains(position)) { venue.vipDoors().add(position); added++; }
        }
        venues.save(); messages.send(sender, "admin.vip-door-added", Map.of("venue", venue.id(), "count", added)); return true;
    }

    private boolean clearVipDoors(CommandSender sender, Venue venue) {
        vipAccess.closeDoors(venue); venue.vipDoors().clear(); venues.save(); messages.send(sender, "admin.vip-doors-cleared", Map.of("venue", venue.id())); return true;
    }

    private boolean vipSelectionInsideVenue(Venue venue, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        org.bukkit.World bukkitWorld = Bukkit.getWorld(world); if (bukkitWorld == null || venue.regions().isEmpty()) return false;
        for (int x : new int[]{minX, maxX}) for (int y : new int[]{minY, maxY}) for (int z : new int[]{minZ, maxZ})
            if (!venue.contains(new Location(bukkitWorld, x, y, z))) return false;
        return true;
    }

    private boolean createGate(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 3) { help(sender); return true; }
        if (!validId(args[2])) { messages.send(sender, "errors.invalid-id"); return true; }
        String id = args[2].toLowerCase(Locale.ROOT);
        if (venue.gates().containsKey(id)) { messages.send(sender, "admin.already-exists"); return true; }
        venue.gates().put(id, new Gate(args[2])); venues.save();
        messages.send(sender, "admin.gate-created", Map.of("gate", args[2])); return true;
    }

    private boolean setGateBlock(CommandSender sender, Venue venue, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) { messages.send(sender, "errors.player-only"); return true; }
        Gate gate = gate(sender, venue, args); if (gate == null) return true;
        Side side = side(args); if (side == null) { help(sender); return true; }
        String world = player.getWorld().getName();
        if (!sameVenueWorld(venue, world)) { messages.send(sender, "errors.different-world"); return true; }
        BlockPosition block = BlockPosition.standingOn(player.getLocation());
        SavedLocation destination = SavedLocation.from(player.getLocation());
        if (side == Side.ENTRANCE) {
            gate.entranceBlock(block);
            gate.exitDestination(destination);
        } else {
            gate.exitBlock(block);
            gate.entranceDestination(destination);
        }
        venues.save(); messages.send(sender, "admin.gate-block-set", Map.of("side", side.label)); return true;
    }

    private boolean setDestination(CommandSender sender, Venue venue, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) { messages.send(sender, "errors.player-only"); return true; }
        Gate gate = gate(sender, venue, args); if (gate == null) return true;
        Side side = side(args); if (side == null) { help(sender); return true; }
        if (!sameVenueWorld(venue, player.getWorld().getName())) { messages.send(sender, "errors.different-world"); return true; }
        SavedLocation location = SavedLocation.from(player.getLocation());
        if (side == Side.ENTRANCE) gate.entranceDestination(location); else gate.exitDestination(location);
        venues.save(); messages.send(sender, "admin.destination-set", Map.of("side", side.label)); return true;
    }

    private boolean setLogoutDestination(CommandSender sender, Venue venue, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) { messages.send(sender, "errors.player-only"); return true; }
        if (args.length != 2) { help(sender); return true; }
        if (!sameVenueWorld(venue, player.getWorld().getName())) { messages.send(sender, "errors.different-world"); return true; }
        if (venues.all().stream().anyMatch(candidate -> candidate.contains(player.getLocation()))) { messages.send(sender, "errors.logout-destination-inside"); return true; }
        venue.logoutDestination(SavedLocation.from(player.getLocation()));
        venues.save();
        messages.send(sender, "admin.logout-destination-set", Map.of("venue", venue.id()));
        return true;
    }

    private boolean info(CommandSender sender, Venue venue) {
        messages.send(sender, "admin.info", Map.of("venue", venue.id(), "fee", venue.fee(), "regions", venue.regions().size(), "vipRegions", venue.vipRegions().size(), "vipDoors", venue.vipDoors().size(), "gates", venue.gates().size())); return true;
    }

    private boolean addPassMachine(CommandSender sender, Venue venue) {
        if (!(sender instanceof org.bukkit.entity.Player player)) { messages.send(sender, "errors.player-only"); return true; }
        Block target = player.getTargetBlockExact(6);
        if (target == null || !target.getType().name().endsWith("_BUTTON")) { messages.send(sender, "errors.look-at-button"); return true; }
        if (!sameVenueWorld(venue, target.getWorld().getName())) { messages.send(sender, "errors.different-world"); return true; }
        BlockPosition position = new BlockPosition(target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
        if (venue.bgmButtons().contains(position) || venue.rouletteMachines().values().stream().anyMatch(machine -> position.equals(machine.playButton()))) { messages.send(sender, "errors.button-already-used"); return true; }
        if (!venue.passMachines().contains(position)) venue.passMachines().add(position);
        venues.save();
        messages.send(sender, "admin.pass-machine-added", Map.of("venue", venue.id()));
        return true;
    }

    private boolean clearPassMachines(CommandSender sender, Venue venue) {
        venue.passMachines().clear(); venues.save();
        messages.send(sender, "admin.pass-machines-cleared", Map.of("venue", venue.id())); return true;
    }

    private boolean addBgmButton(CommandSender sender, Venue venue) {
        if (!(sender instanceof org.bukkit.entity.Player player)) { messages.send(sender, "errors.player-only"); return true; }
        Block target = player.getTargetBlockExact(6);
        if (target == null || !target.getType().name().endsWith("_BUTTON")) { messages.send(sender, "errors.look-at-button"); return true; }
        if (!sameVenueWorld(venue, target.getWorld().getName())) { messages.send(sender, "errors.different-world"); return true; }
        BlockPosition position = new BlockPosition(target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
        if (venue.passMachines().contains(position) || venue.rouletteMachines().values().stream().anyMatch(machine -> position.equals(machine.playButton()))) { messages.send(sender, "errors.button-already-used"); return true; }
        if (!venue.bgmButtons().contains(position)) venue.bgmButtons().add(position);
        venues.save();
        messages.send(sender, "admin.bgm-button-added", Map.of("venue", venue.id()));
        return true;
    }

    private boolean clearBgmButtons(CommandSender sender, Venue venue) {
        venue.bgmButtons().clear();
        venues.save();
        messages.send(sender, "admin.bgm-buttons-cleared", Map.of("venue", venue.id()));
        return true;
    }

    private boolean addFireworkPoint(CommandSender sender, Venue venue) {
        if (!(sender instanceof org.bukkit.entity.Player player)) { messages.send(sender, "errors.player-only"); return true; }
        if (!sameVenueWorld(venue, player.getWorld().getName())) { messages.send(sender, "errors.different-world"); return true; }
        var location = player.getLocation();
        double y = Math.round(location.getY() * 100.0) / 100.0;
        SavedLocation point = new SavedLocation(location.getWorld().getName(), location.getBlockX() + 0.5, y, location.getBlockZ() + 0.5, 0, 0);
        if (!venue.fireworkLaunchPoints().contains(point)) venue.fireworkLaunchPoints().add(point);
        venues.save();
        messages.send(sender, "admin.firework-point-added", Map.of("venue", venue.id(), "count", venue.fireworkLaunchPoints().size()));
        return true;
    }

    private boolean clearFireworkPoints(CommandSender sender, Venue venue) {
        venue.fireworkLaunchPoints().clear();
        venues.save();
        messages.send(sender, "admin.firework-points-cleared", Map.of("venue", venue.id()));
        return true;
    }

    private boolean setPassPrice(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 4) { help(sender); return true; }
        Long value = amount(args[3]);
        if (value == null) { messages.send(sender, "errors.invalid-number"); return true; }
        PassType type;
        try { type = PassType.valueOf(args[2].replace('-', '_').toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { help(sender); return true; }
        switch (type) {
            case ONE_TIME -> venue.oneTimePassPrice(value);
            case SUBSCRIPTION -> venue.subscriptionPassPrice(value);
            case TRIAL -> venue.trialPassPrice(value);
        }
        venues.save();
        messages.send(sender, "admin.pass-price-set", Map.of("venue", venue.id(), "pass", messages.text(type.messagePath()), "price", value));
        return true;
    }

    private boolean managePlayerPass(CommandSender sender, String[] args) {
        if (!sender.isOp() || !sender.hasPermission("gamblegate.passadmin")) {
            messages.send(sender, "errors.no-permission"); return true;
        }
        if (args.length != 4 || (!args[1].equalsIgnoreCase("revoke") && !args[1].equalsIgnoreCase("expire"))) {
            help(sender); return true;
        }
        Venue venue = venues.get(args[3]);
        if (venue == null) { messages.send(sender, "errors.venue-not-found", Map.of("venue", args[3])); return true; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            messages.send(sender, "errors.player-not-found", Map.of("player", args[2])); return true;
        }
        boolean changed = args[1].equalsIgnoreCase("revoke")
                ? passManager.revokePass(target.getUniqueId(), venue.id())
                : passManager.expirePass(target.getUniqueId(), venue.id());
        if (!changed) {
            messages.send(sender, "errors.pass-not-found", Map.of("player", target.getName() == null ? args[2] : target.getName(), "venue", venue.id()));
            return true;
        }
        String path = args[1].equalsIgnoreCase("revoke") ? "admin.pass-revoked" : "admin.pass-expired";
        messages.send(sender, path, Map.of("player", target.getName() == null ? args[2] : target.getName(), "venue", venue.id()));
        return true;
    }

    private Gate gate(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 4) return null;
        Gate gate = venue.gates().get(args[2].toLowerCase(Locale.ROOT));
        if (gate == null) messages.send(sender, "errors.gate-not-found", Map.of("gate", args[2]));
        return gate;
    }

    private Side side(String[] args) {
        if (args.length != 4) return null;
        return switch (args[3].toLowerCase(Locale.ROOT)) { case "entrance" -> Side.ENTRANCE; case "exit" -> Side.EXIT; default -> null; };
    }

    private boolean sameVenueWorld(Venue venue, String world) { return venue.regions().isEmpty() || venue.regions().getFirst().world().equals(world); }
    private boolean validId(String id) { return id.matches("[A-Za-z0-9_-]+"); }
    private Long amount(String raw) { try { long n = Long.parseLong(raw); return n >= 0 ? n : null; } catch (NumberFormatException e) { return null; } }
    private long configAmount(String path, long fallback) {
        double value = plugin.getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value) || value < 0) return fallback;
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(value);
    }
    private void help(CommandSender sender) { messages.lines("admin.usage", Map.of()).forEach(sender::sendMessage); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) choices.addAll(List.of("help", "create", "delete", "setfee", "addregion", "clearregions", "addvipregion", "clearvipregions", "addvipdoor", "clearvipdoors", "creategate", "setgateblock", "setdestination", "setlogoutdestination", "addpassmachine", "clearpassmachines", "addbgmbutton", "clearbgmbuttons", "addfireworkpoint", "clearfireworkpoints", "setpassprice", "pass", "info", "reload"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("pass")) choices.addAll(List.of("revoke", "expire"));
        else if (args.length == 3 && args[0].equalsIgnoreCase("pass")) Bukkit.getOnlinePlayers().forEach(p -> choices.add(p.getName()));
        else if (args.length == 4 && args[0].equalsIgnoreCase("pass")) venues.all().forEach(v -> choices.add(v.id()));
        else if (args.length == 2 && !args[0].equalsIgnoreCase("create")) venues.all().forEach(v -> choices.add(v.id()));
        else if (args.length == 3 && List.of("setgateblock", "setdestination").contains(args[0].toLowerCase(Locale.ROOT))) {
            Venue venue = venues.get(args[1]); if (venue != null) venue.gates().values().forEach(g -> choices.add(g.id()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setpassprice")) choices.addAll(List.of("one-time", "subscription", "trial"));
        else if (args.length == 4 && List.of("setgateblock", "setdestination").contains(args[0].toLowerCase(Locale.ROOT))) choices.addAll(List.of("entrance", "exit"));
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private enum Side { ENTRANCE("入口"), EXIT("出口"); private final String label; Side(String label) { this.label = label; } }
}
