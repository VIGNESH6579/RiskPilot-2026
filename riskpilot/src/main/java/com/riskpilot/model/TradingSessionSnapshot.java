package com.riskpilot.model;

import java.time.LocalTime;

/**
 * Immutable Snapshot representing the strictly controlled execution state of the Live Shadow Engine.
 */
public record TradingSessionSnapshot(
    boolean isTradeActive,
    int tradesToday,
    double orHigh,
    double orLow,
    String regimeFlag,
    double cumulativeDailyLossR,
    boolean preMarketStable,
    SimulationTrade activeTradeReference, // Must treat this reference carefully (should be practically immutable or handled carefully)
    LocalTime currentPhase
) {
    public static TradingSessionSnapshot initial() {
        return new TradingSessionSnapshot(
            false, 
            0, 
            Double.MIN_VALUE, 
            Double.MAX_VALUE, 
            "DEAD", 
            0.0, 
            false, 
            null, 
            LocalTime.of(9, 15)
        );
    }
}
