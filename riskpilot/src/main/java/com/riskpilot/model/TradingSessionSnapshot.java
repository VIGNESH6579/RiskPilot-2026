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
    int consecutiveLosses,
    ActiveTradeExecution activeTradeReference,
    String lastRejectReason,
    double paperBalance
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
            0,
            null,
            "INITIALIZED",
            500000.0 // Initial paper balance: 5 Lakh
        );
    }

    public boolean isSessionActive() { return sessionActive; }
    public String getRegime() { return regime != null ? regime.name() : Regime.UNKNOWN.name(); }
    public boolean isVolatilityQualified() { return volatilityQualified; }
    public String getTimePhase() { return timePhase != null ? timePhase.name() : TimePhase.EARLY.name(); }
    public int getTradesTaken() { return tradesTaken; }
    public boolean isTradeActive() { return tradeActive; }
    public boolean isFeedStable() { return feedStable; }
    public boolean isHeartbeatAlive() { return heartbeatAlive; }
    public double getOrHigh() { return orHigh; }
    public double getOrLow() { return orLow; }
    public double getCumulativeDailyLossR() { return cumulativeDailyLossR; }
    public int getConsecutiveLosses() { return consecutiveLosses; }
    public ActiveTradeExecution getActiveTradeReference() { return activeTradeReference; }
    public String getLastRejectReason() { return lastRejectReason; }
    public double getPaperBalance() { return paperBalance; }
}
