package com.riskpilot.engine;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.GateDecision;
import com.riskpilot.model.Regime;
import com.riskpilot.model.TimePhase;
import com.riskpilot.model.TradingSessionSnapshot;
import com.riskpilot.service.MarketSessionService;
import com.riskpilot.service.RiskEngine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskGateEngine {

    private final RiskPilotProperties config;
    private final KillSwitchEngine killSwitchEngine;
    private final MarketSessionService marketSessionService;
    private final RiskEngine riskEngine;
    private final RegimeFilter regimeFilter;

    @PostConstruct
    public void validate() {
        // Daily trade count is no longer artificially capped at 2 — the
        // strategy + risk gates decide how many setups qualify each day.
        // The configured maxTradesPerDay still acts as a runtime safety
        // ceiling and is enforced inside evaluateEntry() below.
        if (config.getRisk().getMaxTradesPerDay() < 1) {
            throw new IllegalStateException("MAX_TRADES_VIOLATION: Max trades per day must be at least 1");
        }
        if (config.getExecution().getSlippage().getEntryMax() > 3.0) {
            throw new IllegalStateException("SLIPPAGE_VIOLATION: Entry slippage too high");
        }
    }

    public GateDecision evaluateEntry(
        TradingSessionSnapshot state,
        double orRange,
        double entrySlippage,
        long latencyMs
    ) {
        if (killSwitchEngine.isKillSwitchTriggered()) {
            return reject("KILL_SWITCH_HALTED");
        }
        if (!state.sessionActive()) {
            return reject("SESSION_INACTIVE");
        }
        if (config.getInfra().getFeed().isRequireStable() && !state.feedStable()) {
            return reject("FEED_UNSTABLE");
        }
        if (config.getInfra().getHeartbeat().isEnabled() && !state.heartbeatAlive()) {
            return reject("HEARTBEAT_DEAD");
        }
        if (state.regime() != Regime.TREND) {
            return reject("NON_TREND");
        }
        // Regime classification (TREND/CHOP/UNKNOWN) is necessary but not
        // sufficient: the volatility/trend-efficiency/breakout-hold composite
        // score on RegimeFilter can downgrade a snapshot that's nominally
        // TREND into a non-tradable state (e.g. fading momentum, low ATR
        // ratio). Wire it in here so a single source of truth governs entry.
        if (!regimeFilter.isTradingAllowed()) {
            return reject("REGIME_FILTER_BLOCKED");
        }
        if (orRange < config.getFilters().getMinOrRange()) {
            return reject("LOW_VOLATILITY");
        }
        if (config.getExecution().isRejectOnHighSlippage()
            && entrySlippage > config.getExecution().getSlippage().getEntryMax()) {
            return reject("SLIPPAGE_TOO_HIGH");
        }
        if (config.getExecution().isRejectOnLatencyBreach()
            && latencyMs > config.getExecution().getLatency().getHardBlockMs()) {
            return reject("LATENCY_TOO_HIGH");
        }
        if (state.tradeActive() && config.getRisk().isOneTradeAtATime()) {
            return reject("ACTIVE_TRADE_EXISTS");
        }
        if (state.tradesTaken() >= config.getRisk().getMaxTradesPerDay()) {
            return reject("MAX_TRADES_REACHED");
        }
        if (state.cumulativeDailyLossR() <= -config.getRisk().getMaxDailyLossR()) {
            return reject("DAILY_LOSS_LIMIT");
        }
        if (riskEngine.snapshot().dailyLossLimitBreached()) {
            return reject("ACCOUNT_DRAWDOWN_LIMIT");
        }
        if (!marketSessionService.canEnterNewTrade(marketSessionService.now())) {
            return reject("ENTRY_WINDOW_CLOSED");
        }
        if (state.timePhase() == TimePhase.LATE && !config.getTimePhase().getLate().getAllowNewTrades()) {
            return reject("LATE_SESSION_BLOCK");
        }
        return GateDecision.allow();
    }

    public boolean shouldForceLateSessionExit(TradingSessionSnapshot state) {
        return state.tradeActive()
            && state.timePhase() == TimePhase.LATE
            && config.getTimePhase().getLate().getForceExit();
    }

    public void logDecision(
        TradingSessionSnapshot state,
        double orRange,
        long latencyMs,
        double entrySlippage,
        GateDecision decision
    ) {
        log.info(
            "Gate decision={} regime={} orRange={} latencyMs={} slippage={}",
            decision.reason(),
            state.regime(),
            orRange,
            latencyMs,
            entrySlippage
        );
    }

    private GateDecision reject(String reason) {
        log.warn("Gate rejection: {}", reason);
        return GateDecision.reject(reason);
    }
}
