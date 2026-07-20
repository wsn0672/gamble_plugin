package jp.wsn0672.gamblegate.command;

import dev.geco.gsit.api.GSitAPI;
import dev.geco.gsit.model.Seat;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.highlow.HighLowManager;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.HighLowMachine;
import jp.wsn0672.gamblegate.model.Venue;
import jp.wsn0672.gamblegate.guide.GameGuideManager;
import jp.wsn0672.gamblegate.guide.GameGuideType;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HighLowCommand implements CommandExecutor, TabCompleter {
    private final VenueRepository venues;
    private final MessageService messages;
    private final HighLowManager manager;
    private final GameGuideManager guides;

    public HighLowCommand(VenueRepository venues, MessageService messages, HighLowManager manager, GameGuideManager guides) {
        this.venues = venues; this.messages = messages; this.manager = manager; this.guides = guides;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gamblegate.highlowadmin")) { messages.send(sender, "errors.no-permission"); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args.length < 2) { help(sender); return true; }
        Venue venue = venues.get(args[1]);
        if (venue == null) { messages.send(sender, "errors.venue-not-found", Map.of("venue", args[1])); return true; }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setup" -> setup(sender, venue, args);
            case "delete" -> delete(sender, venue, args);
            case "info" -> info(sender, venue, args);
            case "guide" -> guides.register(sender, venue, GameGuideType.HIGHLOW, args);
            case "unguide" -> guides.unregister(sender, venue, GameGuideType.HIGHLOW, args);
            default -> { help(sender); yield true; }
        };
    }

    private boolean setup(CommandSender sender, Venue venue, String[] args) {
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return true; }
        if (args.length != 2) { help(sender); return true; }
        Seat seat = GSitAPI.getSeatByEntity(player);
        if (seat == null) { messages.send(player, "highlow-admin.setup-must-sit"); return true; }
        Block chairBlock = seat.getBlock();
        if (!venue.contains(chairBlock.getLocation())) { messages.send(player, "errors.highlow-outside-venue"); return true; }
        BlockPosition chair = position(chairBlock);
        HighLowMachine machine = venue.highLowMachines().values().stream()
                .filter(candidate -> chair.equals(candidate.chair())).findFirst().orElse(null);
        boolean created = machine == null;
        if (created) {
            machine = new HighLowMachine(nextId(venue));
            machine.chair(chair);
            venue.highLowMachines().put(machine.id().toLowerCase(Locale.ROOT), machine);
        } else if (manager.isPlaying(venue, machine)) {
            messages.send(player, "highlow.busy");
            return true;
        }
        venues.save();
        messages.send(player, created ? "highlow-admin.setup-created-v2" : "highlow-admin.setup-updated-v2",
                Map.of("venue", venue.id(), "machine", machine.id()));
        manager.sendLinkStatus(player, venue, machine);
        return true;
    }

    private boolean delete(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 3) { help(sender); return true; }
        HighLowMachine machine = machine(sender, venue, args[2]);
        if (machine == null) return true;
        if (manager.isPlaying(venue, machine)) { messages.send(sender, "highlow.busy"); return true; }
        venue.highLowMachines().remove(machine.id().toLowerCase(Locale.ROOT));
        venues.save();
        messages.send(sender, "highlow-admin.deleted", Map.of("machine", machine.id()));
        return true;
    }

    private boolean info(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 3) { help(sender); return true; }
        HighLowMachine machine = machine(sender, venue, args[2]);
        if (machine == null) return true;
        messages.send(sender, "highlow-admin.info-v2", Map.of("venue", venue.id(), "machine", machine.id(),
                "complete", manager.isAutoLinked(venue, machine)));
        manager.sendLinkStatus(sender, venue, machine);
        return true;
    }

    private HighLowMachine machine(CommandSender sender, Venue venue, String id) {
        HighLowMachine machine = venue.highLowMachines().get(id.toLowerCase(Locale.ROOT));
        if (machine == null) messages.send(sender, "errors.highlow-not-found", Map.of("machine", id));
        return machine;
    }

    private String nextId(Venue venue) {
        BigInteger highest = BigInteger.ZERO;
        for (String id : venue.highLowMachines().keySet()) if (id.matches("\\d+")) highest = highest.max(new BigInteger(id));
        return highest.add(BigInteger.ONE).toString();
    }
    private BlockPosition position(Block block) { return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()); }
    private void help(CommandSender sender) { messages.lines("highlow-admin.usage-v2", Map.of()).forEach(sender::sendMessage); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) choices.addAll(List.of("help", "setup", "delete", "info", "guide", "unguide"));
        else if (args.length == 2) venues.all().forEach(venue -> choices.add(venue.id()));
        else if (args.length == 3 && !List.of("setup", "guide", "unguide").contains(args[0].toLowerCase(Locale.ROOT))) {
            Venue venue = venues.get(args[1]);
            if (venue != null) venue.highLowMachines().values().forEach(machine -> choices.add(machine.id()));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
