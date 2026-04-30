package com.riskpilot.service.slippage;

import com.riskpilot.config.RiskPilotProperties;
import org.springframework.stereotype.Component;

/**
 * Fixed slippage of {@code riskpilot.execution.slippage.fixed-tick-points}
 * regardless of volatility or tick speed.
 *
 * <p>Useful for low-volatility regimes (early-morning consolidation, post
 * lunch chop) where ATR-fraction would underestimate the cost of crossing
 * the spread, and as a deterministic baseline for regression tests.
 *
 * <p>The result is still clamped into the configured
 * {@code [slippageMinPoints, slippageMaxPoints]} envelope so a misconfigured
 * fixed value cannot escape the operating band.
 */
@Component
public class FixedTickSlippageModel implements SlippageModel {

    private final RiskPilotProperties properties;

    public FixedTickSlippageModel(RiskPilotProperties properties) {
        this.properties = properties;
    }

    @Override
    public double slippagePoints(SlippageContext ctx) {
        double fixed = properties.getExecution().getSlippage().getFixedTickPoints();
        return clamp(fixed, ctx.slippageMinPoints(), ctx.slippageMaxPoints());
    }

    @Override
    public String name() {
        return "FIXED_TICK";
    }

    private static double clamp(double value, double min, double max) {
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }
}
