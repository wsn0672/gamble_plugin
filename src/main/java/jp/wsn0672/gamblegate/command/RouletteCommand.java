package jp.wsn0672.gamblegate.command;

import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.guide.GameGuideManager;
import jp.wsn0672.gamblegate.guide.GameGuideType;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.RouletteMachine;
import jp.wsn0672.gamblegate.model.SavedLocation;
import jp.wsn0672.gamblegate.model.Venue;
import jp.wsn0672.gamblegate.roulette.RouletteManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class RouletteCommand implements CommandExecutor, TabCompleter, Listener {
    private final VenueRepository venues;
    private final MessageService messages;
    private final RouletteManager manager;
    private final GameGuideManager guides;
    private final Map<UUID, CaptureSession> captures = new HashMap<>();

    public RouletteCommand(VenueRepository venues, MessageService messages, RouletteManager manager, GameGuideManager guides) {
        this.venues = venues; this.messages = messages; this.manager = manager; this.guides = guides;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gamblegate.rouletteadmin")) { messages.send(sender, "errors.no-permission"); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
        if (args[0].equalsIgnoreCase("cancel")) {
            if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return true; }
            captures.remove(player.getUniqueId()); messages.send(player, "roulette-admin.setup-cancelled"); return true;
        }
        if (args.length < 2) { help(sender); return true; }
        Venue venue = venues.get(args[1]);
        if (venue == null) { messages.send(sender, "errors.venue-not-found", Map.of("venue", args[1])); return true; }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setup" -> setup(sender, venue, args);
            case "setview" -> setView(sender, venue, args);
            case "setdisplay" -> setDisplay(sender, venue, args);
            case "delete" -> delete(sender, venue, args);
            case "info" -> info(sender, venue, args);
            case "guide" -> guides.register(sender, venue, GameGuideType.ROULETTE, args);
            case "unguide" -> guides.unregister(sender, venue, GameGuideType.ROULETTE, args);
            default -> { help(sender); yield true; }
        };
    }

    private boolean setup(CommandSender sender, Venue venue, String[] args) {
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return true; }
        if (args.length != 3) { help(sender); return true; }
        if (!args[2].equalsIgnoreCase("next") && !args[2].matches("[A-Za-z0-9_-]+")) { messages.send(player, "errors.invalid-id"); return true; }
        Block button = player.getTargetBlockExact(6);
        if (button == null || !isButton(button.getType())) { messages.send(player, "roulette-admin.look-at-button"); return true; }
        if (!venue.contains(button.getLocation()) || !venue.contains(player.getLocation())) { messages.send(player, "roulette-admin.outside-venue"); return true; }
        BlockPosition buttonPosition = position(button);
        if (venue.passMachines().contains(buttonPosition) || venue.bgmButtons().contains(buttonPosition)) {
            messages.send(player, "errors.button-already-used"); return true;
        }
        RouletteMachine machine = venue.rouletteMachines().get(args[2].toLowerCase(Locale.ROOT));
        RouletteMachine existing = machine;
        if (venue.rouletteMachines().values().stream().anyMatch(candidate -> candidate != existing && buttonPosition.equals(candidate.playButton()))) {
            messages.send(player, "errors.button-already-used"); return true;
        }
        boolean created = machine == null;
        if (machine == null) {
            machine = new RouletteMachine(args[2].equalsIgnoreCase("next") ? nextId(venue) : args[2]);
            venue.rouletteMachines().put(machine.id().toLowerCase(Locale.ROOT), machine);
        } else if (manager.isPlaying(venue, machine)) {
            messages.send(player, "roulette.busy"); return true;
        }
        machine.playButton(buttonPosition);
        machine.returnLocation(SavedLocation.from(player.getLocation()));
        machine.viewingLocation(null);
        machine.wagerDisplayLocation(null);
        machine.pockets().clear();
        captures.remove(player.getUniqueId());
        venues.save(); manager.refreshDisplays();
        messages.send(player, created ? "roulette-admin.setup-created" : "roulette-admin.setup-updated",
                Map.of("venue", venue.id(), "machine", machine.id()));
        messages.send(player, "roulette-admin.setup-view-next", Map.of("venue", venue.id(), "machine", machine.id()));
        return true;
    }

    private boolean setView(CommandSender sender, Venue venue, String[] args) {
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return true; }
        if (args.length != 3) { help(sender); return true; }
        RouletteMachine machine = machine(sender, venue, args[2]);
        if (machine == null) return true;
        if (manager.isPlaying(venue, machine)) { messages.send(player, "roulette.busy"); return true; }
        if (!venue.contains(player.getLocation())) { messages.send(player, "roulette-admin.outside-venue"); return true; }
        machine.viewingLocation(SavedLocation.from(player.getLocation()));
        machine.pockets().clear();
        captures.put(player.getUniqueId(), new CaptureSession(venue, machine));
        venues.save(); manager.refreshDisplays();
        messages.send(player, "roulette-admin.capture-start");
        return true;
    }

    private boolean setDisplay(CommandSender sender, Venue venue, String[] args) {
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return true; }
        if (args.length != 3) { help(sender); return true; }
        RouletteMachine machine = machine(sender, venue, args[2]);
        if (machine == null) return true;
        if (manager.isPlaying(venue, machine)) { messages.send(player, "roulette.busy"); return true; }
        if (!venue.contains(player.getLocation())) { messages.send(player, "roulette-admin.outside-venue"); return true; }
        machine.wagerDisplayLocation(SavedLocation.from(player.getLocation()));
        venues.save(); manager.refreshDisplays();
        messages.send(player, "roulette-admin.display-set", Map.of("venue", venue.id(), "machine", machine.id()));
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCapture(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        CaptureSession capture = captures.get(event.getPlayer().getUniqueId());
        if (capture == null) return;
        event.setCancelled(true);
        Block block = event.getClickedBlock();
        if (!capture.venue().contains(block.getLocation())) { messages.send(event.getPlayer(), "roulette-admin.outside-venue"); return; }
        if (block.getType() != Material.GOLD_BLOCK) { messages.send(event.getPlayer(), "roulette-admin.pocket-must-be-gold"); return; }
        BlockPosition position = position(block);
        if (capture.machine().pockets().contains(position)) { messages.send(event.getPlayer(), "roulette-admin.pocket-duplicate"); return; }
        boolean used = capture.venue().rouletteMachines().values().stream()
                .anyMatch(machine -> machine != capture.machine() && machine.pockets().contains(position));
        if (used) { messages.send(event.getPlayer(), "roulette-admin.pocket-used"); return; }
        capture.machine().pockets().add(position);
        int number = capture.machine().pockets().size();
        venues.save();
        messages.send(event.getPlayer(), "roulette-admin.pocket-added", Map.of("number", number, "remaining", 16 - number));
        if (number < 16) return;
        captures.remove(event.getPlayer().getUniqueId());
        manager.refreshDisplays();
        messages.send(event.getPlayer(), "roulette-admin.setup-complete", Map.of(
                "venue", capture.venue().id(), "machine", capture.machine().id()));
    }

    private boolean delete(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 3) { help(sender); return true; }
        RouletteMachine machine = machine(sender, venue, args[2]);
        if (machine == null) return true;
        if (manager.isPlaying(venue, machine)) { messages.send(sender, "roulette.busy"); return true; }
        venue.rouletteMachines().remove(machine.id().toLowerCase(Locale.ROOT));
        captures.entrySet().removeIf(entry -> entry.getValue().machine() == machine);
        venues.save(); manager.refreshDisplays(); messages.send(sender, "roulette-admin.deleted", Map.of("machine", machine.id()));
        return true;
    }

    private boolean info(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 3) { help(sender); return true; }
        RouletteMachine machine = machine(sender, venue, args[2]);
        if (machine == null) return true;
        messages.send(sender, "roulette-admin.info", Map.of("venue", venue.id(), "machine", machine.id(),
                "pockets", machine.pockets().size(), "display", machine.wagerDisplayLocation() != null, "complete", machine.complete()));
        return true;
    }

    private RouletteMachine machine(CommandSender sender, Venue venue, String id) {
        RouletteMachine machine = venue.rouletteMachines().get(id.toLowerCase(Locale.ROOT));
        if (machine == null) messages.send(sender, "errors.roulette-not-found", Map.of("machine", id));
        return machine;
    }

    private String nextId(Venue venue) {
        BigInteger highest = BigInteger.ZERO;
        for (String id : venue.rouletteMachines().keySet()) if (id.matches("\\d+")) highest = highest.max(new BigInteger(id));
        return highest.add(BigInteger.ONE).toString();
    }

    private boolean isButton(Material material) { return material.name().endsWith("_BUTTON"); }
    private BlockPosition position(Block block) { return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()); }
    private void help(CommandSender sender) { messages.lines("roulette-admin.usage", Map.of()).forEach(sender::sendMessage); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) choices.addAll(List.of("help", "setup", "setview", "setdisplay", "delete", "info", "guide", "unguide", "cancel"));
        else if (args.length == 2 && !args[0].equalsIgnoreCase("cancel")) venues.all().forEach(venue -> choices.add(venue.id()));
        else if (args.length == 3) {
            Venue venue = venues.get(args[1]);
            if (venue != null && !List.of("guide", "unguide").contains(args[0].toLowerCase(Locale.ROOT)))
                venue.rouletteMachines().values().forEach(machine -> choices.add(machine.id()));
            if (args[0].equalsIgnoreCase("setup")) choices.add("next");
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().distinct().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private record CaptureSession(Venue venue, RouletteMachine machine) {}
}
