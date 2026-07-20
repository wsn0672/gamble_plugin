package jp.wsn0672.gamblegate.config;

import net.milkbowl.vault.economy.Economy;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CurrencyFormatter {
    private static final Pattern ZERO_FRACTION = Pattern.compile("([.,])(0+)(?=\\D*$)");
    private static final Map<Economy, Pattern> FRACTION_PATTERNS = new WeakHashMap<>();

    private CurrencyFormatter() {}

    public static String format(Economy economy, double amount) {
        if (!Double.isFinite(amount)) return economy.format(amount);
        double truncated = amount < 0 ? Math.ceil(amount) : Math.floor(amount);
        String formatted = economy.format(truncated);
        Pattern fractionPattern = fractionPattern(economy);
        return fractionPattern == null ? formatted : fractionPattern.matcher(formatted).replaceFirst("");
    }

    private static Pattern fractionPattern(Economy economy) {
        synchronized (FRACTION_PATTERNS) {
            if (FRACTION_PATTERNS.containsKey(economy)) return FRACTION_PATTERNS.get(economy);
            Matcher matcher = ZERO_FRACTION.matcher(economy.format(1));
            Pattern pattern = null;
            if (matcher.find()) {
                pattern = Pattern.compile(Pattern.quote(matcher.group(1)) + "0{" + matcher.group(2).length() + "}(?=\\D*$)");
            }
            FRACTION_PATTERNS.put(economy, pattern);
            return pattern;
        }
    }
}
