package jp.wsn0672.gamblegate.config;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class MessageService {
    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration messages;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "message.yml");
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("message.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
        try (var stream = plugin.getResource("message.yml")) {
            if (stream == null) throw new IllegalStateException("Bundled message.yml is missing");
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            boolean changed = false;
            String oldCrashIdle = "&c&lCRASH\n&e0.00x → ???\n&fボタンで挑戦";
            if (oldCrashIdle.equals(messages.getString("crash.idle-display"))) {
                messages.set("crash.idle-display", "&c&lCRASH\n&e1.00x → ???\n&fボタンで挑戦");
                changed = true;
            }
            String oldCrashRunning = "&c&lCRASH\n&e&l{multiplier}x\n&f換金: &a{payout}";
            if (oldCrashRunning.equals(messages.getString("crash.running-display"))) {
                messages.set("crash.running-display", "{color}&lCRASH\n{color}&l{multiplier}x\n&f{stage} &7/ &f換金: &a{payout}");
                changed = true;
            }
            String stagedCrashRunning = "{color}&lCRASH\n{color}&l{multiplier}x\n&f{stage} &7/ &f換金: &a{payout}";
            if (stagedCrashRunning.equals(messages.getString("crash.running-display"))) {
                messages.set("crash.running-display", "{color}&lCRASH\n{color}&l{multiplier}x\n&f換金: &a{payout}");
                changed = true;
            }
            changed |= migrateDefault("game-guides.sign-prompt", "&e説明を見るにはクリック", "&3説明を見るにはクリック");
            changed |= migrateDefault("game-guides.slot.sign-name", "&6&lSLOT", "&1&lSLOT");
            changed |= migrateDefault("game-guides.highlow.sign-name", "&6&lHIGH & LOW", "&5&lHIGH & LOW");
            changed |= migrateDefault("game-guides.crash.sign-name", "&c&lCRASH", "&4&lCRASH");
            changed |= migrateDefault("admin.info", "&e{venue}: 入場料={fee}, リージョン={regions}, ゲート={gates}",
                    "&e{venue}: 入場料={fee}, リージョン={regions}, VIPリージョン={vipRegions}, VIPドア={vipDoors}, ゲート={gates}");
            changed |= migrateDefault("admin.vip-door-added", "&a会場 '{venue}' のVIP自動ドアを登録しました。",
                    "&a会場 '{venue}' のVIP自動ドアを{count}枚登録しました。隣接する両開きドアは自動検出されます。");
            changed |= migrateDefault("vip.denied-message", "&c会場 '{venue}' のVIP専用エリアには入れません。VIP会員から招待を受けることもできます。",
                    "&c会場 '{venue}' のVIP専用エリアへ入るにはVIP権限が必要です。");
            changed |= migrateDefault("vip.denied-subtitle", "&cVIP権限または招待が必要です", "&cVIP権限が必要です");
            changed |= migrateDefault("roulette.color-black", "&8黒", "&7黒");
            changed |= migrateDefault("roulette.won",
                    "&a{number}番（{color}&f）に停止！ &a{choice}が的中し、{payout}を受け取りました。",
                    "{result_color}&l{number}番 &a（{color}&a）に停止！ {choice}&aが的中し、{payout}を受け取りました。");
            changed |= migrateDefault("roulette.lost",
                    "&c{number}番（{color}&f）に停止しました。&c{choice}はハズレです。",
                    "{result_color}&l{number}番 &c（{color}&c）に停止しました。{choice}&cはハズレです。");
            changed |= migrateDefault("roulette.won",
                    "{result_color}&l{number}番 &f（{color}&f）に停止！ {choice}&fが的中し、&a{payout}&fを受け取りました。",
                    "{result_color}&l{number}番 &a（{color}&a）に停止！ {choice}&aが的中し、{payout}を受け取りました。");
            changed |= migrateDefault("roulette.lost",
                    "{result_color}&l{number}番 &f（{color}&f）に停止しました。{choice}&fは&cハズレ&fです。",
                    "{result_color}&l{number}番 &c（{color}&c）に停止しました。{choice}&cはハズレです。");
            // 旧message.ymlでは{color}内のリセット後に復帰色がなく、後続が全て白くなっていました。
            changed |= restoreRouletteContinuationColor("roulette.won", "&a");
            changed |= restoreRouletteContinuationColor("roulette.lost", "&c");
            changed |= migrateDefault("roulette.win-subtitle", "{color}&l{number}番 &f/ &a{payout}",
                    "{result_color}&l{number}番 &f/ &a{payout}");
            changed |= migrateDefault("roulette.lose-subtitle", "{color}&l{number}番",
                    "{result_color}&l{number}番");
            changed |= migrateDefault("scoreboard.result-roulette-win", "&aルーレット {number}番的中",
                    "&aルーレット {result_color}{number}番&a的中");
            changed |= migrateDefault("scoreboard.result-roulette-lose", "&cルーレット {number}番",
                    "&cルーレット {result_color}{number}番");
            List<String> oldHighLowRules = messages.getStringList("game-guides.highlow.rules");
            if (oldHighLowRules.contains("&a連勝するほど連勝ボーナスが増加します。")) {
                int index = oldHighLowRules.indexOf("&a連勝するほど連勝ボーナスが増加します。");
                oldHighLowRules.set(index, "&aカードごとの的中率に応じて、公平な倍率で獲得候補額が増加します。");
                messages.set("game-guides.highlow.rules", oldHighLowRules);
                changed = true;
            }
            for (String path : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(path) || messages.contains(path)) continue;
                messages.set(path, defaults.get(path));
                changed = true;
            }
            messages.setDefaults(defaults);
            if (changed) saveAtomically();
        } catch (IOException exception) {
            plugin.getLogger().severe("message.yml の保存に失敗しました: " + exception.getMessage());
        }
    }

    private void saveAtomically() throws IOException {
        File temporary = new File(plugin.getDataFolder(), "message.yml.tmp");
        messages.save(temporary);
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean migrateDefault(String path, String oldValue, String newValue) {
        if (!oldValue.equals(messages.getString(path))) return false;
        messages.set(path, newValue);
        return true;
    }

    private boolean restoreRouletteContinuationColor(String path, String continuationColor) {
        String value = messages.getString(path);
        if (value == null) return false;
        String fixed = value.replace("{color}）", "{color}" + continuationColor + "）")
                .replace("{color})", "{color}" + continuationColor + ")");
        if (fixed.equals(value)) return false;
        messages.set(path, fixed);
        return true;
    }

    public String text(String path) { return text(path, Collections.emptyMap()); }

    public String text(String path, Map<String, ?> placeholders) {
        String raw = messages.getString(path, path);
        for (var entry : placeholders.entrySet()) raw = raw.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public List<String> lines(String path, Map<String, ?> placeholders) {
        return messages.getStringList(path).stream().map(line -> {
            for (var entry : placeholders.entrySet()) line = line.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            return ChatColor.translateAlternateColorCodes('&', line);
        }).toList();
    }

    public void send(CommandSender sender, String path) { send(sender, path, Collections.emptyMap()); }

    public void send(CommandSender sender, String path, Map<String, ?> placeholders) {
        sender.sendMessage(text("prefix") + text(path, placeholders));
    }
}
