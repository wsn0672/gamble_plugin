package jp.wsn0672.gamblegate.pass;

public enum PassType {
    ONE_TIME("passes.one-time-name", 30),
    SUBSCRIPTION("passes.subscription-name", 30),
    TRIAL("passes.trial-name", 7);

    private final String messagePath;
    private final int days;

    PassType(String messagePath, int days) { this.messagePath = messagePath; this.days = days; }
    public String messagePath() { return messagePath; }
    public long durationMillis() { return days * 24L * 60L * 60L * 1000L; }
}
