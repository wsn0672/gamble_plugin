package jp.wsn0672.gamblegate.model;

public final class CrashMachine {
    private final String id;
    private BlockPosition chair;

    public CrashMachine(String id) { this.id = id; }
    public String id() { return id; }
    public BlockPosition chair() { return chair; }
    public void chair(BlockPosition value) { chair = value; }
    public boolean complete() { return chair != null; }
}
