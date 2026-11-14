/**
 * calculate_cashback — Alternative B from the Detailed Design Record.
 * Pure integer-paise arithmetic, floor truncation, no database lookups, no floating point.
 */
public final class Cashback {
    private Cashback() {}

    /** Tier multipliers as integer percentages (assumption: BASIC 1.00x, PRIME 1.25x, PREMIUM 1.50x). */
    public static int tierMultiplierPercent(String tier) {
        return switch (tier) {
            case "BASIC" -> 100;
            case "PRIME" -> 125;
            case "PREMIUM" -> 150;
            default -> throw new IllegalArgumentException("Unknown tier: " + tier);
        };
    }

    /**
     * @param amountInPaise  transaction amount, must be > 0
     * @param category       merchant category (TOPUP always earns zero)
     * @param tier           BASIC | PRIME | PREMIUM
     * @param rateBp         BaseRatePercent in basis points (5.00% = 500)
     * @return raw cashback in paise, before the monthly cap is applied
     */
    public static long calculate(long amountInPaise, String category, String tier, long rateBp) {
        if (amountInPaise <= 0) throw new IllegalArgumentException("Amount must be positive");
        if ("TOPUP".equals(category) || rateBp <= 0) return 0L;          // explicit zero-rate guard
        long mult = tierMultiplierPercent(tier);
        // amount * (rateBp/10000) * (mult/100)  — one integer division => floor truncation
        return Math.multiplyExact(Math.multiplyExact(amountInPaise, rateBp), mult) / 1_000_000L;
    }
}
