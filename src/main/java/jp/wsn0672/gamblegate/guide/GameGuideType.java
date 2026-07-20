package jp.wsn0672.gamblegate.guide;

public enum GameGuideType {
    SLOT("slot"), HIGHLOW("highlow"), CRASH("crash"), ROULETTE("roulette");

    private final String key;
    GameGuideType(String key) { this.key = key; }
    public String key() { return key; }
}
