package jp.wsn0672.gamblegate.command;

import dev.geco.gsit.api.GSitAPI;
import dev.geco.gsit.model.Seat;
import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.SlotMachine;
import jp.wsn0672.gamblegate.model.Venue;
import jp.wsn0672.gamblegate.guide.GameGuideManager;
import jp.wsn0672.gamblegate.guide.GameGuideType;
import jp.wsn0672.gamblegate.slot.SlotManager;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SlotCommand implements CommandExecutor, TabCompleter, Listener {
    private final VenueRepository venues;
    private final MessageService messages;
    private final SlotManager slots;
    private final GameGuideManager guides;

    public SlotCommand(VenueRepository venues, MessageService messages, SlotManager slots, GameGuideManager guides) {
        this.venues = venues;
        this.messages = messages;
        this.slots = slots;
        this.guides = guides;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gamblegate.slotadmin")) { messages.send(sender, "errors.no-permission"); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
        if (args.length < 2) { help(sender); return true; }
        Venue venue = venues.get(args[1]);
        if (venue == null) { messages.send(sender, "errors.venue-not-found", Map.of("venue", args[1])); return true; }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setup" -> setup(sender, venue, args);
            case "setbet" -> setBet(sender, venue, args);
            case "delete" -> delete(sender, venue, args);
            case "info" -> info(sender, venue, args);
            case "guide" -> guides.register(sender, venue, GameGuideType.SLOT, args);
            case "unguide" -> guides.unregister(sender, venue, GameGuideType.SLOT, args);
            default -> { help(sender); yield true; }
        };
    }

    private boolean setup(CommandSender sender, Venue venue, String[] args) {
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return true; }
        if (args.length != 3) { help(sender); return true; }
        Long bet = positiveLong(args[2]);
        if (bet == null) { messages.send(sender, "slot-admin.invalid-bet"); return true; }
        Seat seat = GSitAPI.getSeatByEntity(player);
        if (seat == null) { messages.send(player, "slot-admin.setup-must-sit"); return true; }
        Block chairBlock = seat.getBlock();
        if (!validLocation(venue, chairBlock)) { messages.send(player, "errors.slot-outside-venue"); return true; }
        BlockPosition chair = position(chairBlock);
        SlotMachine machine = venue.slotMachines().values().stream()
                .filter(candidate -> chair.equals(candidate.chair()))
                .findFirst().orElse(null);
        boolean created = machine == null;
        if (created) {
            machine = new SlotMachine(nextId(venue));
            machine.chair(chair);
            venue.slotMachines().put(machine.id().toLowerCase(Locale.ROOT), machine);
        } else if (slots.isMachinePlaying(venue, machine)) {
            messages.send(player, "slots.busy");
            return true;
        }
        machine.bet(bet);
        venues.save();
        slots.invalidateLinkCache();
        messages.send(player, created ? "slot-admin.setup-created" : "slot-admin.setup-updated",
                Map.of("venue", venue.id(), "slot", machine.id(), "bet", bet));
        sendLinkStatus(player, venue, machine);
        return true;
    }

    private boolean setBet(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 4) { help(sender); return true; }
        SlotMachine machine = machine(sender, venue, args[2]);
        if (machine == null) return true;
        if (slots.isMachinePlaying(venue, machine)) { messages.send(sender, "slots.busy"); return true; }
        Long bet = positiveLong(args[3]);
        if (bet == null) { messages.send(sender, "slot-admin.invalid-bet"); return true; }
        machine.bet(bet);
        venues.save();
        messages.send(sender, "slot-admin.bet-set", Map.of("slot", machine.id(), "bet", bet));
        return true;
    }

    private boolean delete(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 3) { help(sender); return true; }
        SlotMachine machine = machine(sender, venue, args[2]);
        if (machine == null) return true;
        if (slots.isMachinePlaying(venue, machine)) { messages.send(sender, "slots.busy"); return true; }
        venue.slotMachines().remove(machine.id().toLowerCase(Locale.ROOT));
        venues.save();
        slots.invalidateLinkCache();
        messages.send(sender, "slot-admin.deleted", Map.of("slot", machine.id()));
        return true;
    }

    private boolean info(CommandSender sender, Venue venue, String[] args) {
        if (args.length != 3) { help(sender); return true; }
        SlotMachine machine = machine(sender, venue, args[2]);
        if (machine == null) return true;
        BlockPosition shelf = slots.linkedShelf(venue, machine);
        BlockPosition lever = slots.linkedLever(venue, machine);
        messages.send(sender, "slot-admin.info", Map.of(
                "venue", venue.id(), "slot", machine.id(), "bet", machine.bet(),
                "complete", slots.isAutoLinked(venue, machine)));
        messages.send(sender, "slot-admin.info-auto-locations", Map.of(
                "chair", locationText(machine.chair()), "shelf", locationText(shelf), "lever", locationText(lever)));
        return true;
    }

    private void sendLinkStatus(CommandSender sender, Venue venue, SlotMachine machine) {
        BlockPosition shelf = slots.linkedShelf(venue, machine);
        BlockPosition lever = slots.linkedLever(venue, machine);
        if (shelf == null || lever == null) {
            messages.send(sender, "slot-admin.setup-link-missing", Map.of(
                    "shelf", shelf == null ? "未検出" : locationText(shelf),
                    "lever", lever == null ? "未検出" : locationText(lever)));
            return;
        }
        messages.send(sender, "slot-admin.setup-link-success", Map.of(
                "shelf", locationText(shelf), "lever", locationText(lever)));
    }

    private SlotMachine machine(CommandSender sender, Venue venue, String id) {
        SlotMachine machine = venue.slotMachines().get(id.toLowerCase(Locale.ROOT));
        if (machine == null) messages.send(sender, "errors.slot-not-found", Map.of("slot", id));
        return machine;
    }

    private String nextId(Venue venue) {
        BigInteger highest = BigInteger.ZERO;
        for (String id : venue.slotMachines().keySet()) {
            if (!id.matches("\\d+")) continue;
            BigInteger value = new BigInteger(id);
            if (value.compareTo(highest) > 0) highest = value;
        }
        String candidate = highest.add(BigInteger.ONE).toString();
        while (venue.slotMachines().containsKey(candidate.toLowerCase(Locale.ROOT))) {
            candidate = new BigInteger(candidate).add(BigInteger.ONE).toString();
        }
        return candidate;
    }

    private boolean validLocation(Venue venue, Block block) {
        return (venue.regions().isEmpty() || venue.regions().getFirst().world().equals(block.getWorld().getName()))
                && (venue.regions().isEmpty() || venue.contains(block.getLocation()));
    }

    private BlockPosition position(Block block) {
        return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private String locationText(BlockPosition position) {
        return position == null ? "未検出" : position.world() + " " + position.x() + " " + position.y() + " " + position.z();
    }

    private Long positiveLong(String raw) {
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void help(CommandSender sender) {
        messages.lines("slot-admin.usage-v2", Map.of()).forEach(sender::sendMessage);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) choices.addAll(List.of("help", "setup", "setbet", "delete", "info", "guide", "unguide"));
        else if (args.length == 2) venues.all().forEach(venue -> choices.add(venue.id()));
        else if (args.length == 3 && !List.of("setup", "guide", "unguide").contains(args[0].toLowerCase(Locale.ROOT))) {
            Venue venue = venues.get(args[1]);
            if (venue != null) venue.slotMachines().values().forEach(machine -> choices.add(machine.id()));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
