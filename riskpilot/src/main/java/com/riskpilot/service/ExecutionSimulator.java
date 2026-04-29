package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.MarketTick;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Deterministic execution model.
 *
 * Latency, spread and slippage are derived from REAL measured inputs (the
 * observed inter-tick gap from the live Angel One feed and the volatility of
 * the most recent real candles). No random number generation is performed
 * anywhere in this class — every value is a pure function of the live market
 * data and the configured bounds.
 */
@Service
public class ExecutionSimulator {

    private final RiskPilotProperties properties;

    public ExecutionSimulator(RiskPilotProperties properties) {
        this.properties = properties;
    }

    public ExecutionPlan planEntry(Instant signalTime, double volatilityPoints, long tickGapMs) {
        return plan(signalTime, volatilityPoints, tickGapMs, true);
    }

    public ExecutionPlan planExit(Instant signalTime, double volatilityPoints, long tickGapMs) {
        return plan(signalTime, volatilityPoints, tickGapMs, false);
    }

    public SimulatedFill fillEntry(String direction, double expectedPrice, MarketTick tick, ExecutionPlan plan) {
        double halfSpread = plan.spreadPoints() / 2.0;
        double impact = plan.slippagePoints();
        double actualPrice = "SHORT".equalsIgnoreCase(direction)
            ? tick.price() - halfSpread - impact
            : tick.price() + halfSpread + impact;
        return new SimulatedFill(expectedPrice, actualPrice, plan.executionTime(), plan.latencyMs(), plan.spreadPoints(), plan.slippagePoints());
    }

    public SimulatedFill fillExit(String direction, double expectedPrice, MarketTick tick, ExecutionPlan plan) {
        double halfSpread = plan.spreadPoints() / 2.0;
        double impact = plan.slippagePoints();
        double actualPrice = "SHORT".equalsIgnoreCase(direction)
            ? tick.price() + halfSpread + impact
            : tick.price() - halfSpread - impact;
        return new SimulatedFill(expectedPrice, actualPrice, plan.executionTime(), plan.latencyMs(), plan.spreadPoints(), plan.slippagePoints());
    }

    private ExecutionPlan plan(Instant signalTime, double volatilityPoints, long tickGapMs, boolean entry) {
        RiskPilotProperties.Execution.Simulation simulation = properties.getExecution().getSimulation();
        long minLatency = entry ? simulation.getEntryLatencyMinMs() : simulation.getExitLatencyMinMs();
        long maxLatency = entry ? simulation.getEntryLatencyMaxMs() : simulation.getExitLatencyMaxMs();

        // Latency = the actual measured inter-tick gap from the live feed,
        // clamped to the configured operating envelope. No randomness.
        long observedGap = Math.max(0L, tickGapMs);
        long latencyMs = clampLong(observedGap, Math.min(minLatency, maxLatency), Math.max(minLatency, maxLatency));

        // Spread = the midpoint of the configured operating range. The Angel
        // SmartStream LTP feed does not publish bid/ask, so the configured
        // midpoint is the only honest deterministic estimate available.
        double spreadPoints = midpoint(simulation.getSpreadMinPoints(), simulation.getSpreadMaxPoints());

        // Slippage = a deterministic function of REAL volatility and the REAL
        // measured tick speed, clamped to the configured envelope.
        double volatilityComponent = Math.max(0.0, volatilityPoints) * simulation.getVolatilityWeight();
        double tickSpeedFactor = tickGapMs <= 0L
            ? 1.0
            : Math.min(2.0, 1000.0 / Math.max(1.0, tickGapMs));
        double rawSlippage = simulation.getSlippageMinPoints()
            + volatilityComponent
            + (tickSpeedFactor * simulation.getTickSpeedWeight());
        double slippagePoints = clampDouble(
            rawSlippage,
            simulation.getSlippageMinPoints(),
            simulation.getSlippageMaxPoints()
        );

        return new ExecutionPlan(signalTime, latencyMs, signalTime.plusMillis(latencyMs), spreadPoints, slippagePoints);
    }

    private static long clampLong(long value, long minInclusive, long maxInclusive) {
        long min = Math.min(minInclusive, maxInclusive);
        long max = Math.max(minInclusive, maxInclusive);
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static double clampDouble(double value, double minInclusive, double maxInclusive) {
        double min = Math.min(minInclusive, maxInclusive);
        double max = Math.max(minInclusive, maxInclusive);
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static double midpoint(double a, double b) {
        return (a + b) / 2.0;
    }

    public record ExecutionPlan(
        Instant signalTime,
        long latencyMs,
        Instant executionTime,
        double spreadPoints,
        double slippagePoints
    ) {}

    public record SimulatedFill(
        double expectedPrice,
        double actualPrice,
        Instant executionTime,
        long latencyMs,
        double spreadPoints,
        double slippagePoints
    ) {}
}
