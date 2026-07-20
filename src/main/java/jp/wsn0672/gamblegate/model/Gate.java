package jp.wsn0672.gamblegate.model;

public final class Gate {
    private final String id;
    private BlockPosition entranceBlock;
    private BlockPosition exitBlock;
    private SavedLocation entranceDestination;
    private SavedLocation exitDestination;

    public Gate(String id) { this.id = id; }
    public String id() { return id; }
    public BlockPosition entranceBlock() { return entranceBlock; }
    public void entranceBlock(BlockPosition value) { entranceBlock = value; }
    public BlockPosition exitBlock() { return exitBlock; }
    public void exitBlock(BlockPosition value) { exitBlock = value; }
    public SavedLocation entranceDestination() { return entranceDestination; }
    public void entranceDestination(SavedLocation value) { entranceDestination = value; }
    public SavedLocation exitDestination() { return exitDestination; }
    public void exitDestination(SavedLocation value) { exitDestination = value; }
    public boolean complete() { return entranceBlock != null && exitBlock != null && entranceDestination != null && exitDestination != null; }
}
