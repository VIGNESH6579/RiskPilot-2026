package com.riskpilot.service;

import com.riskpilot.model.Regime;
import com.riskpilot.model.TimePhase;
import com.riskpilot.model.TradingSessionSnapshot;
import org.springframework.stereotype.Service;

@Service
public class RiskGateEngine {

    private static final int MAX_TRADES_PER_DAY = 2;

    public GateDecision evaluateEntry(TradingSessionSnapshot state) {
        if (!state.sessionActive()) {
            return GateDecision.reject("SESSION_INACTIVE");
        }
        if (!state.feedStable()) {
            return GateDecision.reject("FEED_UNSTABLE");
        }
        if (!state.heartbeatAlive()) {
            return GateDecision.reject("HEARTBEAT_DEAD");
        }
        if (state.regime() != Regime.TREND) {
            return GateDecision.reject("REGIME_BLOCKED");
        }
        if (!state.volatilityQualified()) {
            return GateDecision.reject("VOLATILITY_FAIL");
        }
        if (state.tradeActive()) {
            return GateDecision.reject("TRADE_ALREADY_ACTIVE");
        }
        if (state.tradesTaken() >= MAX_TRADES_PER_DAY) {
            return GateDecision.reject("MAX_TRADES_REACHED");
        }
        if (state.cumulativeDailyLossR() <= -1.5) {
            return GateDecision.reject("DAILY_LOSS_CAP_REACHED");
        }
        if (state.timePhase() == TimePhase.LATE) {
            return GateDecision.reject("LATE_SESSION_BLOCK");
        }
        return GateDecision.allow();
    }
}
