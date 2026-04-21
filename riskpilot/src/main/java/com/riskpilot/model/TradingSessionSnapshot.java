package com.riskpilot.model;

/**
 * Immutable source of truth for session risk controls.
 */
public record TradingSessionSnapshot(
    boolean sessionActive,
    Regime regime,
    boolean volatilityQualified,
    TimePhase timePhase,
    int tradesTaken,
    boolean tradeActive,
    boolean feedStable,
    boolean heartbeatAlive,
    double orHigh,
    double orLow,
    double cumulativeDailyLossR,
    ActiveTrade activeTradeReference,
    String lastRejectReason
) {
    public static TradingSessionSnapshot initial() {
        return new TradingSessionSnapshot(
            false,
            Regime.UNKNOWN,
            false,
            TimePhase.EARLY,
            0,
            false,
            true,
            true,
            Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            0.0,
            null,
            "INITIALIZED"
        );
    }
}
