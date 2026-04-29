package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.MarketTick;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

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
        long latencyMs = randomLong(minLatency, maxLatency);
        double spreadPoints = randomDouble(simulation.getSpreadMinPoints(), simulation.getSpreadMaxPoints());
        double volatilityComponent = Math.max(0.0, volatilityPoints) * simulation.getVolatilityWeight();
        double tickSpeedFactor = tickGapMs <= 0L
            ? 1.0
            : Math.min(2.0, 1000.0 / Math.max(1.0, tickGapMs));
        double slippageCeiling = Math.min(
            simulation.getSlippageMaxPoints(),
            simulation.getSlippageMinPoints() + volatilityComponent + (tickSpeedFactor * simulation.getTickSpeedWeight())
        );
        double slippagePoints = randomDouble(simulation.getSlippageMinPoints(), Math.max(simulation.getSlippageMinPoints(), slippageCeiling));
        return new ExecutionPlan(signalTime, latencyMs, signalTime.plusMillis(latencyMs), spreadPoints, slippagePoints);
    }

    private long randomLong(long minInclusive, long maxInclusive) {
        long min = Math.min(minInclusive, maxInclusive);
        long max = Math.max(minInclusive, maxInclusive);
        if (min == max) {
            return min;
        }
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private double randomDouble(double minInclusive, double maxInclusive) {
        double min = Math.min(minInclusive, maxInclusive);
        double max = Math.max(minInclusive, maxInclusive);
        if (Double.compare(min, max) == 0) {
            return min;
        }
        return ThreadLocalRandom.current().nextDouble(min, max);
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
