package jp.wsn0672.gamblegate.model;

public final class SlotMachine {
    private final String id;
    private BlockPosition chair;
    private long bet;

    public SlotMachine(String id) { this.id = id; }
    public String id() { return id; }
    public BlockPosition chair() { return chair; }
    public void chair(BlockPosition value) { chair = value; }
    public long bet() { return bet; }
    public void bet(long value) { bet = value; }
    public boolean complete() { return chair != null && bet > 0; }
}
