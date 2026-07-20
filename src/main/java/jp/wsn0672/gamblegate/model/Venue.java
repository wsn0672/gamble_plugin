package jp.wsn0672.gamblegate.model;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Venue {
    private final String id;
    private long fee;
    private final List<RegionBox> regions = new ArrayList<>();
    private final List<RegionBox> vipRegions = new ArrayList<>();
    private final Map<String, Gate> gates = new LinkedHashMap<>();
    private final List<BlockPosition> passMachines = new ArrayList<>();
    private final List<BlockPosition> bgmButtons = new ArrayList<>();
    private final List<BlockPosition> vipDoors = new ArrayList<>();
    private final List<SavedLocation> fireworkLaunchPoints = new ArrayList<>();
    private final Map<String, SlotMachine> slotMachines = new LinkedHashMap<>();
    private final Map<String, HighLowMachine> highLowMachines = new LinkedHashMap<>();
    private final Map<String, CrashMachine> crashMachines = new LinkedHashMap<>();
    private final Map<String, RouletteMachine> rouletteMachines = new LinkedHashMap<>();
    private final Map<String, List<BlockPosition>> gameGuideSigns = new LinkedHashMap<>();
    private SavedLocation logoutDestination;
    private long oneTimePassPrice;
    private long subscriptionPassPrice;
    private long trialPassPrice;

    public Venue(String id, long fee) { this.id = id; this.fee = fee; }
    public String id() { return id; }
    public long fee() { return fee; }
    public void fee(long value) { fee = value; }
    public List<RegionBox> regions() { return regions; }
    public List<RegionBox> vipRegions() { return vipRegions; }
    public Map<String, Gate> gates() { return gates; }
    public List<BlockPosition> passMachines() { return passMachines; }
    public List<BlockPosition> bgmButtons() { return bgmButtons; }
    public List<BlockPosition> vipDoors() { return vipDoors; }
    public List<SavedLocation> fireworkLaunchPoints() { return fireworkLaunchPoints; }
    public Map<String, SlotMachine> slotMachines() { return slotMachines; }
    public Map<String, HighLowMachine> highLowMachines() { return highLowMachines; }
    public Map<String, CrashMachine> crashMachines() { return crashMachines; }
    public Map<String, RouletteMachine> rouletteMachines() { return rouletteMachines; }
    public Map<String, List<BlockPosition>> gameGuideSigns() { return gameGuideSigns; }
    public SavedLocation logoutDestination() { return logoutDestination; }
    public void logoutDestination(SavedLocation value) { logoutDestination = value; }
    public long oneTimePassPrice() { return oneTimePassPrice; }
    public void oneTimePassPrice(long value) { oneTimePassPrice = value; }
    public long subscriptionPassPrice() { return subscriptionPassPrice; }
    public void subscriptionPassPrice(long value) { subscriptionPassPrice = value; }
    public long trialPassPrice() { return trialPassPrice; }
    public void trialPassPrice(long value) { trialPassPrice = value; }
    public boolean contains(Location location) { return regions.stream().anyMatch(region -> region.contains(location)); }
    public boolean vipContains(Location location) { return vipRegions.stream().anyMatch(region -> region.contains(location)); }
    public boolean contains(Location location, int margin) { return regions.stream().anyMatch(region -> region.contains(location, margin)); }
    public boolean withinDistance(Location location, double radius) { return regions.stream().anyMatch(region -> region.withinDistance(location, radius)); }
}
