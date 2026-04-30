package com.riskpilot.service.slippage;

/**
 * Strategy for computing the per-trade slippage component (in points)
 * applied on top of the half-spread by {@code ExecutionSimulator}.
 *
 * <p>Implementations must be deterministic — given the same context they
 * must return the same value. No random number generation is permitted;
 * realism in the shadow engine depends on results being reproducible
 * across replays of the same tick stream.
 *
 * <p>The model is selected at runtime via
 * {@code riskpilot.execution.slippage.model} ({@code FIXED_TICK} or
 * {@code ATR_FRACTION}).
 */
public interface SlippageModel {

    /**
     * @return slippage to apply to the fill, in index points. Always
     *         non-negative. The caller is responsible for clamping into
     *         the configured operating envelope and for applying it on
     *         the correct side of the trade direction.
     */
    double slippagePoints(SlippageContext context);

    /**
     * Stable identifier written to the audit ledger so a row can be
     * traced back to the model that produced it.
     */
    String name();
}
