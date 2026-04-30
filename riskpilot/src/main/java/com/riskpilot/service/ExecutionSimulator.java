package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.MarketTick;
import com.riskpilot.service.slippage.SlippageContext;
import com.riskpilot.service.slippage.SlippageModel;
import com.riskpilot.service.slippage.SlippageModelResolver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic execution model.
 *
 * <p>Latency, spread and slippage are derived from REAL measured inputs
 * (the observed inter-tick gap from the live Angel One feed and the
 * volatility of the most recent real candles) and the configured
 * {@link SlippageModel}. No random number generation is performed
 * anywhere in this class — every value is a pure function of the live
 * market data and the configured bounds.
 *
 * <p>Bucket C additions (audit fixes):
 * <ul>
 *   <li>Slippage is now produced by a swappable strategy
 *       ({@code FIXED_TICK} or {@code ATR_FRACTION}) selected via
 *       configuration. The previous hard-coded
 *       {@code volatilityComponent + tickSpeedFactor} formula is the
 *       fallback when no model is wired (kept for safety; in normal
 *       boot the resolver is always present).</li>
 *   <li>{@link #executeEntry} / {@link #executeExit} return the full
 *       per-leg breakdown so larger orders can be modelled as two
 *       fills against deeper book liquidity (book-walking) and the
 *       audit ledger captures every leg.</li>
 * </ul>
 */
@Service
public class ExecutionSimulator {

    private final RiskPilotProperties properties;
    private final SlippageModelResolver slippageModelResolver;

    public ExecutionSimulator(RiskPilotProperties properties, SlippageModelResolver slippageModelResolver) {
        this.properties = properties;
        this.slippageModelResolver = slippageModelResolver;
    }

    public ExecutionPlan planEntry(Instant signalTime, double volatilityPoints, long tickGapMs) {
        return plan(signalTime, volatilityPoints, tickGapMs, true);
    }

    public ExecutionPlan planExit(Instant signalTime, double volatilityPoints, long tickGapMs) {
        return plan(signalTime, volatilityPoints, tickGapMs, false);
    }

    /**
     * Backwards-compatible single-fill entry. Equivalent to calling
     * {@link #executeEntry} with {@code totalLots=1} and taking the
     * aggregated fill — kept so existing call sites do not need to be
     * audited for the totalLots argument.
     */
    public SimulatedFill fillEntry(String direction, double expectedPrice, MarketTick tick, ExecutionPlan plan) {
        return executeEntry(direction, expectedPrice, tick, plan, 1).aggregated();
    }

    public SimulatedFill fillExit(String direction, double expectedPrice, MarketTick tick, ExecutionPlan plan) {
        return executeExit(direction, expectedPrice, tick, plan, 1).aggregated();
    }

    /**
     * Multi-leg entry execution. Splits orders that exceed the
     * configured {@code partial-fill-threshold-lots} into two legs, the
     * second of which pays an additional {@code drift} to model book
     * walking. Returns both the per-leg detail (for the audit ledger)
     * and a volume-weighted aggregated {@link SimulatedFill} (for the
     * existing trade-row write path that expects a single price).
     */
    public Execution executeEntry(String direction, double expectedPrice, MarketTick tick, ExecutionPlan plan, int totalLots) {
        return execute(direction, expectedPrice, tick, plan, totalLots, true);
    }

    public Execution executeExit(String direction, double expectedPrice, MarketTick tick, ExecutionPlan plan, int totalLots) {
        return execute(direction, expectedPrice, tick, plan, totalLots, false);
    }

    private Execution execute(
        String direction,
        double expectedPrice,
        MarketTick tick,
        ExecutionPlan plan,
        int totalLots,
        boolean entry
    ) {
        boolean shortTrade = "SHORT".equalsIgnoreCase(direction);
        // Entry SHORT and exit LONG both sell — the adverse direction
        // for the trader is below the touch in both cases. Symmetric
        // for buys.
        boolean adverseUp = entry ? !shortTrade : shortTrade;

        int lots = Math.max(0, totalLots);
        RiskPilotProperties.Execution.Simulation simulation = properties.getExecution().getSimulation();
        int threshold = simulation.getPartialFillThresholdLots();
        boolean partial = threshold > 0 && lots >= threshold && lots >= 2;

        if (!partial) {
            SimulatedFill single = buildLeg(adverseUp, tick.price(), expectedPrice, plan, 0L, 0.0);
            FillLeg leg = new FillLeg(single, 1, 1, Math.max(1, lots));
            return new Execution(single, List.of(leg));
        }

        int leg1Lots = lots / 2;
        int leg2Lots = lots - leg1Lots;
        SimulatedFill leg1Fill = buildLeg(adverseUp, tick.price(), expectedPrice, plan, 0L, 0.0);
        SimulatedFill leg2Fill = buildLeg(
            adverseUp,
            tick.price(),
            expectedPrice,
            plan,
            simulation.getPartialFillSecondLegLatencyMs(),
            simulation.getPartialFillSecondLegDriftPoints()
        );

        List<FillLeg> legs = new ArrayList<>(2);
        legs.add(new FillLeg(leg1Fill, 1, 2, leg1Lots));
        legs.add(new FillLeg(leg2Fill, 2, 2, leg2Lots));

        SimulatedFill aggregated = aggregate(expectedPrice, legs);
        return new Execution(aggregated, Collections.unmodifiableList(legs));
    }

    private SimulatedFill buildLeg(
        boolean adverseUp,
        double touchPrice,
        double expectedPrice,
        ExecutionPlan plan,
        long extraLatencyMs,
        double extraSlippagePoints
    ) {
        double halfSpread = plan.spreadPoints() / 2.0;
        double impact = plan.slippagePoints() + Math.max(0.0, extraSlippagePoints);
        double actualPrice = adverseUp
            ? touchPrice + halfSpread + impact
            : touchPrice - halfSpread - impact;
        long latency = plan.latencyMs() + Math.max(0L, extraLatencyMs);
        Instant executionTime = plan.signalTime().plusMillis(latency);
        return new SimulatedFill(expectedPrice, actualPrice, executionTime, latency, plan.spreadPoints(), impact);
    }

    private SimulatedFill aggregate(double expectedPrice, List<FillLeg> legs) {
        double totalNotional = 0.0;
        long totalLots = 0L;
        long maxLatency = 0L;
        double weightedSlippage = 0.0;
        Instant lastExec = legs.get(0).fill().executionTime();
        for (FillLeg leg : legs) {
            int lots = Math.max(0, leg.lots());
            if (lots == 0) continue;
            totalNotional += leg.fill().actualPrice() * lots;
            totalLots += lots;
            weightedSlippage += leg.fill().slippagePoints() * lots;
            if (leg.fill().latencyMs() > maxLatency) {
                maxLatency = leg.fill().latencyMs();
                lastExec = leg.fill().executionTime();
            }
        }
        double vwap = totalLots == 0L ? legs.get(0).fill().actualPrice() : totalNotional / totalLots;
        double avgSlippage = totalLots == 0L ? legs.get(0).fill().slippagePoints() : weightedSlippage / totalLots;
        return new SimulatedFill(expectedPrice, vwap, lastExec, maxLatency, legs.get(0).fill().spreadPoints(), avgSlippage);
    }

    private ExecutionPlan plan(Instant signalTime, double volatilityPoints, long tickGapMs, boolean entry) {
        RiskPilotProperties.Execution.Simulation simulation = properties.getExecution().getSimulation();
        long minLatency = entry ? simulation.getEntryLatencyMinMs() : simulation.getExitLatencyMinMs();
        long maxLatency = entry ? simulation.getEntryLatencyMaxMs() : simulation.getExitLatencyMaxMs();

        long observedGap = Math.max(0L, tickGapMs);
        long latencyMs = clampLong(observedGap, Math.min(minLatency, maxLatency), Math.max(minLatency, maxLatency));

        double spreadPoints = midpoint(simulation.getSpreadMinPoints(), simulation.getSpreadMaxPoints());
        double slippagePoints = computeSlippage(volatilityPoints, tickGapMs, simulation);

        return new ExecutionPlan(signalTime, latencyMs, signalTime.plusMillis(latencyMs), spreadPoints, slippagePoints);
    }

    private double computeSlippage(
        double volatilityPoints,
        long tickGapMs,
        RiskPilotProperties.Execution.Simulation simulation
    ) {
        SlippageModel model = slippageModelResolver != null ? slippageModelResolver.active() : null;
        if (model != null) {
            SlippageContext ctx = SlippageContext.of(
                volatilityPoints,
                volatilityPoints,
                tickGapMs,
                simulation.getSlippageMinPoints(),
                simulation.getSlippageMaxPoints()
            );
            return model.slippagePoints(ctx);
        }

        // Fallback: legacy formula. Should never be reached in normal
        // boot because SlippageModelResolver is a Spring component.
        double volatilityComponent = Math.max(0.0, volatilityPoints) * simulation.getVolatilityWeight();
        double tickSpeedFactor = tickGapMs <= 0L
            ? 1.0
            : Math.min(2.0, 1000.0 / Math.max(1.0, tickGapMs));
        double rawSlippage = simulation.getSlippageMinPoints()
            + volatilityComponent
            + (tickSpeedFactor * simulation.getTickSpeedWeight());
        return clampDouble(rawSlippage, simulation.getSlippageMinPoints(), simulation.getSlippageMaxPoints());
    }

    /**
     * Identifier of the active slippage model. Exposed so the audit
     * ledger can stamp every fill row with the model that produced it.
     */
    public String activeSlippageModelName() {
        SlippageModel model = slippageModelResolver != null ? slippageModelResolver.active() : null;
        return model != null ? model.name() : "LEGACY_INLINE";
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

    /**
     * One leg of a fill. {@code legNumber} is 1-based; {@code totalLegs}
     * is 1 for single-shot fills and 2 for partials.
     */
    public record FillLeg(
        SimulatedFill fill,
        int legNumber,
        int totalLegs,
        int lots
    ) {}

    /**
     * Result of executing an order. {@link #aggregated()} is the
     * volume-weighted single fill (compatible with existing trade-row
     * writes); {@link #legs()} is the per-leg detail for the audit
     * ledger.
     */
    public record Execution(
        SimulatedFill aggregated,
        List<FillLeg> legs
    ) {}
}
