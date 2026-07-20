package jp.wsn0672.gamblegate.guide;

import jp.wsn0672.gamblegate.config.MessageService;
import jp.wsn0672.gamblegate.config.SoundService;
import jp.wsn0672.gamblegate.config.VenueRepository;
import jp.wsn0672.gamblegate.model.BlockPosition;
import jp.wsn0672.gamblegate.model.Venue;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class GameGuideManager implements Listener {
    private final JavaPlugin plugin;
    private final VenueRepository venues;
    private final MessageService messages;
    private final SoundService sounds;

    public GameGuideManager(JavaPlugin plugin, VenueRepository venues, MessageService messages, SoundService sounds) {
        this.plugin = plugin; this.venues = venues; this.messages = messages; this.sounds = sounds;
    }

    public boolean register(CommandSender sender, Venue venue, GameGuideType type, String[] args) {
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return true; }
        if (args.length != 2) return false;
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Sign sign)) { messages.send(player, "errors.look-at-sign"); return true; }
        if (!venue.contains(block.getLocation())) { messages.send(player, "game-guides.outside-venue"); return true; }
        BlockPosition position = position(block);
        RegisteredSign existing = find(position);
        if (existing != null && (existing.venue() != venue || existing.type() != type)) {
            messages.send(player, "game-guides.already-registered"); return true;
        }
        List<BlockPosition> signs = venue.gameGuideSigns().computeIfAbsent(type.key(), ignored -> new java.util.ArrayList<>());
        if (!signs.contains(position)) signs.add(position);
        updateSign(sign, type, true);
        venues.save();
        messages.send(player, "game-guides.registered", Map.of("game", messages.text("game-guides." + type.key() + ".name")));
        sounds.play(player, "entry-success");
        return true;
    }

    public boolean unregister(CommandSender sender, Venue venue, GameGuideType type, String[] args) {
        if (!(sender instanceof Player player)) { messages.send(sender, "errors.player-only"); return true; }
        if (args.length != 2) return false;
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Sign sign)) { messages.send(player, "errors.look-at-sign"); return true; }
        List<BlockPosition> signs = venue.gameGuideSigns().get(type.key());
        if (signs == null || !signs.remove(position(block))) { messages.send(player, "game-guides.not-registered"); return true; }
        if (signs.isEmpty()) venue.gameGuideSigns().remove(type.key());
        updateSign(sign, type, false);
        venues.save(); messages.send(player, "game-guides.removed");
        return true;
    }

    public void refreshSigns() {
        for (Venue venue : venues.all()) for (GameGuideType type : GameGuideType.values()) {
            List<BlockPosition> positions = venue.gameGuideSigns().get(type.key());
            if (positions == null) continue;
            for (BlockPosition position : positions) {
                World world = Bukkit.getWorld(position.world());
                if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) continue;
                if (world.getBlockAt(position.x(), position.y(), position.z()).getState() instanceof Sign sign) updateSign(sign, type, true);
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Venue venue : venues.all()) for (GameGuideType type : GameGuideType.values()) {
            List<BlockPosition> positions = venue.gameGuideSigns().get(type.key());
            if (positions == null) continue;
            for (BlockPosition position : positions) {
                if (!position.world().equals(event.getWorld().getName()) || (position.x() >> 4) != event.getChunk().getX() || (position.z() >> 4) != event.getChunk().getZ()) continue;
                if (event.getWorld().getBlockAt(position.x(), position.y(), position.z()).getState() instanceof Sign sign) updateSign(sign, type, true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock(); if (block == null || !(block.getState() instanceof Sign)) return;
        RegisteredSign registered = find(position(block)); if (registered == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        String base = "game-guides." + registered.type().key();
        player.sendMessage(messages.text("game-guides.header", Map.of("game", messages.text(base + ".name"))));
        messages.lines(base + ".rules", ruleValues(registered.type())).forEach(player::sendMessage);
        sounds.play(player, "gui-open");
    }

    private RegisteredSign find(BlockPosition position) {
        for (Venue venue : venues.all()) for (GameGuideType type : GameGuideType.values()) {
            List<BlockPosition> signs = venue.gameGuideSigns().get(type.key());
            if (signs != null && signs.contains(position)) return new RegisteredSign(venue, type);
        }
        return null;
    }

    private void updateSign(Sign sign, GameGuideType type, boolean registered) {
        String game = registered ? messages.text("game-guides." + type.key() + ".sign-name") : "";
        String prompt = registered ? messages.text("game-guides.sign-prompt") : "";
        for (Side side : Side.values()) {
            sign.getSide(side).setLine(1, game);
            sign.getSide(side).setLine(2, prompt);
            sign.getSide(side).setGlowingText(registered);
        }
        sign.setWaxed(registered);
        sign.update(true);
    }

    private Map<String, Object> ruleValues(GameGuideType type) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (type == GameGuideType.CRASH) {
            int minimum = Math.max(100, plugin.getConfig().getInt("crash.minimum-cashout-centi", 120));
            values.put("minimum", String.format(Locale.ROOT, "%.2f", minimum / 100.0));
            values.put("bets", plugin.getConfig().getLongList("crash.bets").stream().filter(value -> value > 0).limit(3)
                    .map(value -> String.format(Locale.ROOT, "%,d円", value)).collect(Collectors.joining(" / ")));
        } else if (type == GameGuideType.SLOT) {
            values.put("pair", decimal("slot-machine.payouts.two-match", 0.3));
            values.put("iron", decimal("slot-machine.payouts.IRON_INGOT", 2.0));
            values.put("emerald", decimal("slot-machine.payouts.EMERALD", 3.0));
            values.put("diamond", decimal("slot-machine.payouts.DIAMOND", 5.0));
        } else if (type == GameGuideType.ROULETTE) {
            values.put("half", decimal("roulette.payouts.half", 2.0));
            values.put("quarter", decimal("roulette.payouts.quarter", 4.0));
            values.put("exact", decimal("roulette.payouts.exact", 16.0));
            values.put("bets", plugin.getConfig().getLongList("roulette.bets").stream().filter(value -> value > 0).limit(3)
                    .map(value -> String.format(Locale.ROOT, "%,d円", value)).collect(Collectors.joining(" / ")));
        }
        return values;
    }

    private String decimal(String path, double fallback) {
        return String.format(Locale.ROOT, "%.2f", Math.max(0, plugin.getConfig().getDouble(path, fallback))).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private BlockPosition position(Block block) {
        return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private record RegisteredSign(Venue venue, GameGuideType type) {}
}
