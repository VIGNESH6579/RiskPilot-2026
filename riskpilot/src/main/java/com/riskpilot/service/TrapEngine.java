package com.riskpilot.service;

import com.riskpilot.model.Candle;
import com.riskpilot.model.Signal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Detects "trap" reversal setups (false breakout / false breakdown
 * followed by a reclaim) and emits a {@link Signal} with both:
 * <ul>
 *   <li>a fallback {@code quantity} computed from the legacy
 *       trap-specific risk capital, kept for backwards compatibility
 *       with anything that consumes the raw signal; and</li>
 *   <li>a {@code convictionScore} in [0.0, 1.0] for the downstream
 *       sizer to scale the risk-budgeted lot count by, so a shallow
 *       low-quality trap takes a smaller position than a deep,
 *       sharp-rejection trap of the same notional risk distance.</li>
 * </ul>
 *
 * <p>Conviction is composed deterministically from three independent
 * signals (no randomness):
 * <ol>
 *   <li>Breakout depth ratio — how far past S/R the failed leg poked,
 *       relative to the recent average candle range.</li>
 *   <li>Wick rejection ratio — fraction of the trap candle that is
 *       wick on the rejection side. A pure marubozu (no wick) scores
 *       0; a pin bar scores ~1.</li>
 *   <li>Reclaim distance — how far {@code t0.close} has retraced back
 *       inside the level, relative to the breakout depth. A barely
 *       reclaimed level scores low; a strong push back inside scores
 *       high.</li>
 * </ol>
 */
@Service
public class TrapEngine {

    private static final double MIN_BREAKOUT_DEPTH = 6.0;
    private static final double MAX_STOP_DISTANCE = 120.0;
    private static final double MIN_AVG_RANGE = 1.0;
    private static final int LOOKBACK_AVG_RANGE = 5;

    private final double riskCapital;
    private final int lotSize;

    public TrapEngine(
        @Value("${TRAP_RISK_CAPITAL:1000}") double riskCapital,
        @Value("${NIFTY_LOT_SIZE:75}") int lotSize
    ) {
        this.riskCapital = riskCapital;
        this.lotSize = Math.max(1, lotSize);
    }

    public Signal detectTrap(List<Candle> history, double localSupport, double localResistance) {
        if (history.size() < 7) {
            return null;
        }

        Candle t0 = history.get(history.size() - 1);
        Candle t1 = history.get(history.size() - 2);

        double sumRange = 0.0;
        for (int i = history.size() - 7; i <= history.size() - 3; i++) {
            Candle candle = history.get(i);
            sumRange += candle.high - candle.low;
        }
        double avgRange = Math.max(MIN_AVG_RANGE, sumRange / LOOKBACK_AVG_RANGE);
        double t1Range = Math.max(MIN_AVG_RANGE, t1.high - t1.low);
        if (t1Range <= avgRange) {
            return null;
        }

        // SHORT trap: failed upside breakout reclaimed back below
        // resistance.
        if (t1.high > localResistance) {
            double breakoutDepth = t1.high - localResistance;
            if (breakoutDepth >= MIN_BREAKOUT_DEPTH) {
                double t1Midpoint = (t1.high + t1.low) / 2.0;
                if (t0.close < localResistance && t0.close < t1Midpoint) {
                    double entry = t0.close;
                    double stopLoss = t1.high + 10.0;
                    double distanceToStop = Math.abs(stopLoss - entry);
                    if (distanceToStop <= MAX_STOP_DISTANCE) {
                        double upperWick = Math.max(0.0, t1.high - Math.max(t1.open, t1.close));
                        double reclaimPoints = Math.max(0.0, localResistance - t0.close);
                        double conviction = computeConviction(
                            breakoutDepth, avgRange, upperWick, t1Range, reclaimPoints
                        );
                        return buildSignal("SHORT", entry, stopLoss, distanceToStop, conviction);
                    }
                }
            }
        }

        // LONG trap: failed downside breakdown reclaimed back above
        // support.
        if (t1.low < localSupport) {
            double breakdownDepth = localSupport - t1.low;
            if (breakdownDepth >= MIN_BREAKOUT_DEPTH) {
                double t1Midpoint = (t1.high + t1.low) / 2.0;
                if (t0.close > localSupport && t0.close > t1Midpoint) {
                    double entry = t0.close;
                    double stopLoss = t1.low - 10.0;
                    double distanceToStop = Math.abs(entry - stopLoss);
                    if (distanceToStop <= MAX_STOP_DISTANCE) {
                        double lowerWick = Math.max(0.0, Math.min(t1.open, t1.close) - t1.low);
                        double reclaimPoints = Math.max(0.0, t0.close - localSupport);
                        double conviction = computeConviction(
                            breakdownDepth, avgRange, lowerWick, t1Range, reclaimPoints
                        );
                        return buildSignal("LONG", entry, stopLoss, distanceToStop, conviction);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Three-component conviction score in [0, 1]. Each component is
     * already in [0, 1] before averaging so the result is also in
     * [0, 1]. We use a 50/30/20 weighting because depth is the most
     * objective signal of trap quality, wick rejection is the next
     * strongest, and reclaim distance is the noisiest of the three.
     */
    private double computeConviction(
        double breakoutDepth,
        double avgRange,
        double rejectionWick,
        double t1Range,
        double reclaimPoints
    ) {
        // Component 1: depth in units of avgRange, capped at 3x for full
        // credit. A 1x breakout scores ~0.33; a 3x+ breakout scores 1.0.
        double depthRatio = clamp01(breakoutDepth / (avgRange * 3.0));

        // Component 2: rejection wick as a fraction of the trap candle
        // range. A pure pin bar (wick == range) scores 1.0; a marubozu
        // scores 0.0.
        double wickRatio = clamp01(rejectionWick / t1Range);

        // Component 3: reclaim distance relative to the breakout depth.
        // If t0 has fully retraced past the level by the same magnitude
        // as the failed poke, score 1.0. A token reclaim scores low.
        double reclaimRatio = breakoutDepth <= 0.0
            ? 0.0
            : clamp01(reclaimPoints / breakoutDepth);

        return clamp01(0.50 * depthRatio + 0.30 * wickRatio + 0.20 * reclaimRatio);
    }

    private Signal buildSignal(String direction, double entry, double stopLoss, double distanceToStop, double conviction) {
        Signal signal = new Signal();
        signal.setSymbol("NIFTY");
        signal.setDirection(direction);
        signal.setEntry(entry);
        signal.setStopLoss(stopLoss);
        signal.setTarget(0.0);
        signal.setConfidence((int) Math.round(conviction * 100.0));
        signal.setQuantity(calculateQuantity(distanceToStop));
        signal.setConvictionScore(conviction);
        return signal;
    }

    private int calculateQuantity(double distanceToStop) {
        if (distanceToStop <= 0.0) {
            return lotSize;
        }

        int rawUnits = Math.max(lotSize, (int) Math.floor(riskCapital / distanceToStop));
        int lots = (int) Math.ceil(rawUnits / (double) lotSize);
        return Math.max(lotSize, lots * lotSize);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
