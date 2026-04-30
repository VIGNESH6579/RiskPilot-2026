package com.riskpilot.service.slippage;

/**
 * Inputs to a {@link SlippageModel}. All values are derived from the
 * live Angel feed or the configured envelope — never randomised.
 *
 * @param volatilityPoints rolling volatility estimate (typically
 *        {@code VolatilityNormalizer.getCurrentATR()} or the recent
 *        candle range), in index points
 * @param atrPoints        true ATR over the lookback window in points;
 *        equals {@code volatilityPoints} when no separate ATR is
 *        available
 * @param tickGapMs        observed inter-tick gap in milliseconds —
 *        proxy for current liquidity / book speed
 * @param slippageMinPoints lower bound from configuration
 * @param slippageMaxPoints upper bound from configuration
 */
public record SlippageContext(
    double volatilityPoints,
    double atrPoints,
    long tickGapMs,
    double slippageMinPoints,
    double slippageMaxPoints
) {
    public static SlippageContext of(double volatilityPoints, double atrPoints, long tickGapMs,
                                     double minPoints, double maxPoints) {
        return new SlippageContext(volatilityPoints, atrPoints, tickGapMs, minPoints, maxPoints);
    }
}
