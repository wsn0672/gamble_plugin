package jp.wsn0672.gamblegate.model;

import java.util.ArrayList;
import java.util.List;

public final class RouletteMachine {
    private final String id;
    private BlockPosition playButton;
    private SavedLocation viewingLocation;
    private SavedLocation returnLocation;
    private SavedLocation wagerDisplayLocation;
    private final List<BlockPosition> pockets = new ArrayList<>();

    public RouletteMachine(String id) { this.id = id; }
    public String id() { return id; }
    public BlockPosition playButton() { return playButton; }
    public void playButton(BlockPosition value) { playButton = value; }
    public SavedLocation viewingLocation() { return viewingLocation; }
    public void viewingLocation(SavedLocation value) { viewingLocation = value; }
    public SavedLocation returnLocation() { return returnLocation; }
    public void returnLocation(SavedLocation value) { returnLocation = value; }
    public SavedLocation wagerDisplayLocation() { return wagerDisplayLocation; }
    public void wagerDisplayLocation(SavedLocation value) { wagerDisplayLocation = value; }
    public List<BlockPosition> pockets() { return pockets; }
    public boolean complete() {
        if (playButton == null || viewingLocation == null || returnLocation == null || pockets.size() != 16) return false;
        String world = pockets.getFirst().world();
        return pockets.stream().allMatch(pocket -> pocket.world().equals(world));
    }
}
