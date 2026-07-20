package jp.wsn0672.gamblegate.highlow;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

public final class NoteBlockMidiPlayer {
    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final List<File> midiFolders;
    private final Map<String, BukkitTask> playbacks = new HashMap<>();
    private final ToDoubleFunction<UUID> listenerVolumeMultiplier;

    public NoteBlockMidiPlayer(JavaPlugin plugin) {
        this(plugin, listenerId -> 1.0);
    }

    public NoteBlockMidiPlayer(JavaPlugin plugin, ToDoubleFunction<UUID> listenerVolumeMultiplier) {
        this.plugin = plugin;
        this.listenerVolumeMultiplier = listenerVolumeMultiplier;
        File casinoMidi = new File(plugin.getDataFolder(), "casino-midi");
        File legacyHighLowMidi = new File(plugin.getDataFolder(), "highlow-midi");
        midiFolders = List.of(casinoMidi, legacyHighLowMidi);
        if (!casinoMidi.exists() && !casinoMidi.mkdirs()) plugin.getLogger().warning("casino-midi フォルダを作成できませんでした。");
    }

    public void start(String key, Location source, UUID listenerId) {
        stop(key);
        if (!plugin.getConfig().getBoolean("casino-bgm.enabled", true) || source.getWorld() == null) return;
        Song song = randomMidiSong();
        if (song == null) song = builtInSong();
        Song selected = song;
        int[] position = {0};
        int[] songTick = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!playbacks.containsKey(key)) return;
            int maxNotes = Math.max(1, plugin.getConfig().getInt("casino-bgm.max-notes-per-tick", 12));
            int played = 0;
            while (position[0] < selected.notes().size() && selected.notes().get(position[0]).tick() <= songTick[0] && played < maxNotes) {
                play(source, selected.notes().get(position[0]), listenerId);
                position[0]++; played++;
            }
            songTick[0]++;
            if (songTick[0] > selected.lengthTicks()) { songTick[0] = 0; position[0] = 0; }
        }, 0L, 1L);
        playbacks.put(key, task);
    }

    public void stop(String key) {
        BukkitTask task = playbacks.remove(key);
        if (task != null) task.cancel();
    }

    public boolean isPlaying(String key) { return playbacks.containsKey(key); }

    public void stopAll() {
        for (BukkitTask task : playbacks.values()) task.cancel();
        playbacks.clear();
    }

    private void play(Location source, Note note, UUID listenerId) {
        Player player = Bukkit.getPlayer(listenerId);
        if (player == null || !player.isOnline() || !player.getWorld().equals(source.getWorld())) return;
        double personalMultiplier = Math.max(0, listenerVolumeMultiplier.applyAsDouble(listenerId));
        if (personalMultiplier <= 0) return;
        float configuredVolume = (float) Math.max(0, plugin.getConfig().getDouble("casino-bgm.volume", 0.65));
        float volume = (float) (configuredVolume * personalMultiplier * Math.max(0.15f, note.velocity() / 127.0f));
        float pitch = note.channel() == 9 ? 1.0f : (float) Math.max(0.5, Math.min(2.0, Math.pow(2.0, (note.note() - 66) / 12.0)));
        String sound = note.channel() == 9 ? percussion(note.note()) : "minecraft:block.note_block.harp";
        player.playSound(player.getLocation(), sound, SoundCategory.RECORDS, volume, pitch);
    }

    private String percussion(int note) {
        if (note == 35 || note == 36) return "minecraft:block.note_block.basedrum";
        if (note == 38 || note == 40) return "minecraft:block.note_block.snare";
        return "minecraft:block.note_block.hat";
    }

    private Song randomMidiSong() {
        List<File> candidates = new ArrayList<>();
        for (File folder : midiFolders) {
            File[] files = folder.listFiles(file -> {
                String name = file.getName().toLowerCase(Locale.ROOT);
                return file.isFile() && (name.endsWith(".mid") || name.endsWith(".midi"));
            });
            if (files != null) candidates.addAll(List.of(files));
        }
        if (candidates.isEmpty()) return null;
        while (!candidates.isEmpty()) {
            File file = candidates.remove(random.nextInt(candidates.size()));
            try { return read(file); }
            catch (Exception exception) { plugin.getLogger().warning("MIDIを読み込めませんでした: " + file.getName() + " - " + exception.getMessage()); }
        }
        return null;
    }

    private Song read(File file) throws Exception {
        Sequence sequence = MidiSystem.getSequence(file);
        long tickLength = Math.max(1, sequence.getTickLength());
        long microsecondLength = Math.max(50_000, sequence.getMicrosecondLength());
        double serverTicksPerMidiTick = (microsecondLength / 50_000.0) / tickLength;
        List<Note> notes = new ArrayList<>();
        for (Track track : sequence.getTracks()) for (int index = 0; index < track.size(); index++) {
            MidiEvent event = track.get(index);
            MidiMessage message = event.getMessage();
            if (!(message instanceof ShortMessage shortMessage) || shortMessage.getCommand() != ShortMessage.NOTE_ON || shortMessage.getData2() <= 0) continue;
            int tick = (int) Math.min(Integer.MAX_VALUE - 1, Math.round(event.getTick() * serverTicksPerMidiTick));
            notes.add(new Note(tick, shortMessage.getData1(), shortMessage.getData2(), shortMessage.getChannel()));
        }
        notes.sort(Comparator.comparingInt(Note::tick));
        if (notes.isEmpty()) throw new IllegalArgumentException("ノートがありません");
        int length = Math.max(notes.getLast().tick() + 20, (int) Math.min(Integer.MAX_VALUE - 1, Math.round(microsecondLength / 50_000.0)));
        return new Song(notes, length);
    }

    private Song builtInSong() {
        int[] melody = {66, 69, 73, 71, 69, 66, 64, 66, 69, 73, 76, 73, 71, 69, 66, 64};
        List<Note> notes = new ArrayList<>();
        for (int i = 0; i < melody.length; i++) notes.add(new Note(i * 4, melody[i], 95, 0));
        return new Song(notes, melody.length * 4 + 8);
    }

    private record Note(int tick, int note, int velocity, int channel) {}
    private record Song(List<Note> notes, int lengthTicks) {}
}
