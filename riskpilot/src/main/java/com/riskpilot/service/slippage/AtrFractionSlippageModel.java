package com.riskpilot.service.slippage;

import com.riskpilot.config.RiskPilotProperties;
import org.springframework.stereotype.Component;

/**
 * Slippage = ATR × {@code riskpilot.execution.slippage.atr-fraction},
 * with a small tick-speed bonus when the feed is firing faster than
 * once per second (proxy for high-conviction order flow that eats the
 * book).
 *
 * <p>This is the recommended default for index futures because the cost
 * of crossing the spread scales with realised volatility — a fixed-tick
 * model dramatically under-counts adverse fills during news spikes and
 * over-counts during the lunch-time grind.
 *
 * <p>The result is clamped into the configured
 * {@code [slippageMinPoints, slippageMaxPoints]} envelope.
 */
@Component
public class AtrFractionSlippageModel implements SlippageModel {

    private final RiskPilotProperties properties;

    public AtrFractionSlippageModel(RiskPilotProperties properties) {
        this.properties = properties;
    }

    @Override
    public double slippagePoints(SlippageContext ctx) {
        double fraction = properties.getExecution().getSlippage().getAtrFraction();
        double atr = Math.max(0.0, ctx.atrPoints() > 0.0 ? ctx.atrPoints() : ctx.volatilityPoints());
        double base = atr * fraction;

        // Tick-speed bonus: gaps below 1s indicate book-eating flow. We
        // add up to one half of the base as additional impact, scaling
        // linearly with how far below 1s we are. No randomness.
        double bonus = 0.0;
        if (ctx.tickGapMs() > 0L && ctx.tickGapMs() < 1000L) {
            double speedFactor = (1000.0 - ctx.tickGapMs()) / 1000.0; // 0..1
            bonus = base * 0.5 * speedFactor;
        }

        return clamp(base + bonus, ctx.slippageMinPoints(), ctx.slippageMaxPoints());
    }

    @Override
    public String name() {
        return "ATR_FRACTION";
    }

    private static double clamp(double value, double min, double max) {
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }
}
