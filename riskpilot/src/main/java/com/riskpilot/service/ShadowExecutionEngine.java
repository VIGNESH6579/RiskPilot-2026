package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.engine.RiskGateEngine;
import com.riskpilot.model.ActiveTradeExecution;
import com.riskpilot.model.CandleEntity;
import com.riskpilot.model.GateDecision;
import com.riskpilot.model.TradeExit;
import com.riskpilot.model.TradingSessionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowExecutionEngine {

    private final RiskPilotProperties config;
    private final RiskGateEngine riskGateEngine;
    private final KillSwitchEngine killSwitchEngine;
    private final SessionStateManager stateManager;
    private final CandleAggregator candleAggregator;
    private final TrapEngine trapEngine;
    private final VixService vixService;
    private final LiveMetricsLogger liveMetricsLogger;
    private final WebSocketService webSocketService;

    private String lastTriggeredCandleTime = "";
    private LocalDateTime activeSignalTime;
    private double activeExpectedEntry;
    private boolean dayBlockedByFirstTradeFailure;

    public ShadowExecutionEngine(
        SessionStateManager stateManager,
        CandleAggregator candleAggregator,
        TrapEngine trapEngine,
        RiskGateEngine riskGateEngine,
        VixService vixService,
        LiveMetricsLogger liveMetricsLogger,
        WebSocketService webSocketService
    ) {
        this.stateManager = stateManager;
        this.candleAggregator = candleAggregator;
        this.trapEngine = trapEngine;
        this.riskGateEngine = riskGateEngine;
        this.vixService = vixService;
        this.liveMetricsLogger = liveMetricsLogger;
        this.webSocketService = webSocketService;
    }

    public synchronized void evaluateTick(double currentPrice) {
        // 🔴 KILL-SWITCH CHECK (EVERY TICK)
        if (killSwitchEngine.isKillSwitchTriggered()) {
            log.error("🚫 KILL SWITCH ACTIVE - Ignoring tick processing");
            return;
        }

        TradingSessionSnapshot state = stateManager.getSnapshot();

        if (state.tradeActive() && state.activeTradeReference() != null) {
            ActiveTradeExecution trade = state.activeTradeReference();
            
            // 🔒 STEP 1: Update MFE/MAE (mandatory, every tick)
            trade = ActiveTradeExecution.updateExcursions(trade, currentPrice);

            // 🔒 STEP 2: Handle TP1 (tick-level, immediate)
            trade = ActiveTradeExecution.fromTickTP1(trade, currentPrice);

            // 🔒 STEP 3: Check Stop Loss (tick-level, immediate)
            TradeExit exit = ActiveTradeExecution.checkStopLoss(trade, currentPrice);
            
            if (exit.triggered()) {
                closeTrade(trade, exit);
                return;
            }
            
            updateActiveTradeState(trade, state.lastRejectReason());
            broadcastCurrentSessionState();
        }
    }

    public synchronized void evaluateCandleClose() {
        List<CandleEntity> strictHistory = candleAggregator.getValidHistory();
        if (strictHistory.size() < 10) {
            return;
        }

        CandleEntity newestCandle = strictHistory.get(strictHistory.size() - 1);
        updateSessionStateFromTime(LocalTime.parse(newestCandle.getTimestamp().toLocalTime().toString()));

        if (dayBlockedByFirstTradeFailure) {
            logReject(stateManager.getSnapshot(), "FIRST_TRADE_FAILURE_DAY_BLOCK");
            return;
        }

        TradingSessionSnapshot state = stateManager.getSnapshot();
        
        // 🔒 STEP 4: FORCE LATE SESSION EXIT (CONFIG-DRIVEN)
        if (riskGateEngine.shouldForceLateSessionExit(state)) {
            log.warn("🚫 LATE SESSION FORCE EXIT: Time cutoff reached");
            if (state.tradeActive() && state.activeTradeReference() != null) {
                ActiveTradeExecution trade = state.activeTradeReference();
                ActiveTradeExecution updated = ActiveTradeExecution.fromCandleClose(trade, newestCandle);
                TradeExit exit = new TradeExit(
                    true,
                    updated.realizedPnL() + ((updated.entryPrice() - newestCandle.getClosePrice()) * updated.remainingSize()),
                    "TIME_CUTOFF_EXIT",
                    newestCandle.getClosePrice()
                );
                closeTrade(updated, exit);
            }
            return;
        }

        if (state.tradeActive() && state.activeTradeReference() != null) {
            CandleEntity previousCandle = strictHistory.get(strictHistory.size() - 2);
            double currentRange = newestCandle.getHighPrice() - newestCandle.getLowPrice();
            double previousRange = previousCandle.getHighPrice() - previousCandle.getLowPrice();
            double orRange = Math.max(0.0, state.orHigh() - state.orLow());
            ActiveTradeExecution updated = ActiveTradeExecution.fromCandleClose(state.activeTradeReference(), newestCandle);
            
            if (state.timePhase() != TimePhase.EARLY && orRange > 0.0 && currentRange < (orRange * 0.2)) {
                TradeExit exit = new TradeExit(
                    true,
                    updated.realizedPnL() + ((updated.entryPrice() - newestCandle.getClosePrice()) * updated.remainingSize()),
                    "VOLATILITY_COLLAPSE_EXIT",
                    newestCandle.getClosePrice()
                );
                closeTrade(updated, exit);
                return;
            }
            updateActiveTradeState(updated, state.lastRejectReason());
            broadcastCurrentSessionState();
            return;
        }

        if (newestCandle.time.equals(lastTriggeredCandleTime)) {
            return;
        }

        // 🔒 STEP 1: Calculate metrics for gate evaluation
        double localSupport = strictHistory.stream().skip(Math.max(0, strictHistory.size() - 6)).mapToDouble(c -> c.low).min().orElse(newestCandle.low);
        double localResistance = strictHistory.stream().skip(Math.max(0, strictHistory.size() - 6)).mapToDouble(c -> c.high).max().orElse(newestCandle.high);
        double liveVix = vixService.getIndiaVix();
        double orRange = Math.max(0.0, state.orHigh() - state.orLow());
        double entrySlippageEstimate = Math.abs(newestCandle.close - trapEngine.detectTrap(strictHistory, localSupport, localResistance, liveVix).getEntry());

        // 🔒 STEP 2: HARD GATE EVALUATION (NO BYPASS)
        GateDecision decision = riskGateEngine.evaluateEntry(state, orRange, entrySlippageEstimate, 0L);
        
        // 🔒 STEP 3: MANDATORY DECISION LOGGING
        riskGateEngine.logDecision(state, orRange, 0L, entrySlippageEstimate, decision);
        
        if (!decision.allowed()) {
            log.warn("🚫 GATE REJECTION: {}", decision.reason());
            return;
        }

        // 🔒 STEP 4: Only proceed if ALL gates passed
        Signal signal = trapEngine.detectTrap(strictHistory, localSupport, localResistance, liveVix);
        if (signal == null) {
            return;
        }

        openTrade(signal, state);
        lastTriggeredCandleTime = newestCandle.time;
        broadcastCurrentSessionState();
    }

    @Scheduled(cron = "0 14 9 * * ?")
    public void executeDailyHardReset() {
        stateManager.resetDaily();
        candleAggregator.clearHistory();
        lastTriggeredCandleTime = "";
        activeSignalTime = null;
        dayBlockedByFirstTradeFailure = false;
        broadcastCurrentSessionState();
    }

    private void updateSessionStateFromTime(LocalTime now) {
        TimePhase phase = now.isBefore(LocalTime.NOON) ? TimePhase.EARLY :
            (now.isBefore(LocalTime.of(13, 30)) ? TimePhase.MID : TimePhase.LATE);

        stateManager.update(current -> {
            boolean sessionActive = !now.isBefore(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(15, 30));
            double orHigh = current.orHigh();
            double orLow = current.orLow();

            List<Candle> history = candleAggregator.getValidHistory();
            if (!history.isEmpty()) {
                Candle last = history.get(history.size() - 1);
                if (now.isBefore(LocalTime.of(10, 15))) {
                    orHigh = Math.max(orHigh, last.high);
                    orLow = Math.min(orLow, last.low);
                }
            }

            double orRange = (Double.isInfinite(orHigh) || Double.isInfinite(orLow)) ? 0.0 : (orHigh - orLow);
            boolean volatilityQualified = orRange > 120.0;
            Regime regime = volatilityQualified ? Regime.TREND : Regime.BLOCKED;

            return new TradingSessionSnapshot(
                sessionActive,
                regime,
                volatilityQualified,
                phase,
                current.tradesTaken(),
                current.tradeActive(),
                !candleAggregator.isFeedUnstable(),
                current.heartbeatAlive(),
                orHigh,
                orLow,
                current.cumulativeDailyLossR(),
                current.activeTradeReference(),
                current.lastRejectReason()
            );
        });
    }

    private void openTrade(Signal signal, TradingSessionSnapshot state) {
        LocalDateTime now = LocalDateTime.now();
        double size = state.timePhase() == TimePhase.EARLY ? 1.0 : 0.35;
        double orRange = Math.max(0.0, state.orHigh() - state.orLow());
        double tp1Distance = Math.max(15.0, orRange * 0.15);
        double dynamicTp1 = signal.getEntry() - tp1Distance;
        double initialRisk = Math.abs(signal.getStopLoss() - signal.getEntry());
        ActiveTrade trade = new ActiveTrade(
            signal.getEntry(),
            signal.getStopLoss(),
            dynamicTp1,
            initialRisk,
            false,
            false,
            false,
            false,
            size,
            size,
            0.0,
            0.0,
            0.0,
            0.0,
            signal.getStopLoss()
        );
        activeSignalTime = now;
        activeExpectedEntry = signal.getEntry();

        stateManager.update(current -> new TradingSessionSnapshot(
            current.sessionActive(),
            current.regime(),
            current.volatilityQualified(),
            current.timePhase(),
            current.tradesTaken() + 1,
            true,
            current.feedStable(),
            current.heartbeatAlive(),
            current.orHigh(),
            current.orLow(),
            current.cumulativeDailyLossR(),
            trade,
            "ALLOW"
        ));
        broadcastCurrentSessionState();
    }

    private void closeTrade(ActiveTrade trade, TradeExit exit) {
        TradingSessionSnapshot state = stateManager.getSnapshot();
        if (state.activeTradeReference() == null || activeSignalTime == null) {
            return;
        }
        double expectedExit = trade.tp1Hit() ? trade.trailingSL() : trade.stopLoss();
        double riskPts = Math.abs(trade.stopLoss() - trade.entryPrice());
        double realizedR = riskPts == 0.0 ? 0.0 : exit.pnlPoints() / riskPts;

        liveMetricsLogger.logShadowExecution(
            activeSignalTime,
            LocalDateTime.now(),
            activeExpectedEntry,
            trade.entryPrice(),
            expectedExit,
            exit.exitPrice(),
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.mfe(),
            trade.mae(),
            realizedR,
            "ALLOW",
            "",
            state.regime(),
            state.timePhase(),
            state.feedStable(),
            exit.reason(),
            LocalDateTime.now()
        );

        boolean isFirstTradeFailure = state.tradesTaken() == 1 && !trade.tp1Hit() && trade.mae() > 80.0 && realizedR <= -1.0;
        if (isFirstTradeFailure) {
            dayBlockedByFirstTradeFailure = true;
        }

        stateManager.update(current -> new TradingSessionSnapshot(
            current.sessionActive(),
            isFirstTradeFailure ? Regime.BLOCKED : current.regime(),
            current.volatilityQualified(),
            current.timePhase(),
            current.tradesTaken(),
            false,
            current.feedStable(),
            current.heartbeatAlive(),
            current.orHigh(),
            current.orLow(),
            current.cumulativeDailyLossR() + Math.min(0.0, realizedR),
            null,
            isFirstTradeFailure ? "FIRST_TRADE_FAILURE_DAY_BLOCK" : "ALLOW"
        ));
        broadcastCurrentSessionState();
    }

    private void logReject(TradingSessionSnapshot state, String reason) {
        stateManager.update(current -> new TradingSessionSnapshot(
            current.sessionActive(),
            current.regime(),
            current.volatilityQualified(),
            current.timePhase(),
            current.tradesTaken(),
            current.tradeActive(),
            current.feedStable(),
            current.heartbeatAlive(),
            current.orHigh(),
            current.orLow(),
            current.cumulativeDailyLossR(),
            current.activeTradeReference(),
            reason
        ));
        broadcastCurrentSessionState();

        liveMetricsLogger.logReject(
            LocalDateTime.now(),
            reason,
            state.regime(),
            state.timePhase(),
            state.feedStable()
        );
    }

    private ActiveTrade updateExcursions(ActiveTrade trade, double currentPrice) {
        double favorable = trade.entryPrice() - currentPrice;
        double adverse = currentPrice - trade.entryPrice();
        double mfe = Math.max(trade.mfe(), favorable);
        double mae = Math.max(trade.mae(), adverse);
        return new ActiveTrade(
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
            trade.initialRisk(),
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.stage2Active(),
            trade.tailHalfLocked(),
            trade.positionSize(),
            trade.remainingSize(),
            trade.realizedPnL(),
            mfe,
            mae,
            trade.peakFavorableR(),
            trade.trailingSL()
        );
    }

    private ActiveTrade handleTickTP1(ActiveTrade trade, double currentPrice) {
        if (trade.tp1Hit()) {
            return trade;
        }

        if (currentPrice <= trade.tp1Level()) {
            double tp1Size = trade.positionSize() * 0.15;
            double remaining = trade.positionSize() - tp1Size;
            double pnl = (trade.entryPrice() - currentPrice) * tp1Size;
            double softStop = trade.entryPrice() + (0.4 * trade.initialRisk());
            return new ActiveTrade(
                trade.entryPrice(),
                softStop,
                trade.tp1Level(),
                trade.initialRisk(),
                true,
                true,
                trade.stage2Active(),
                trade.tailHalfLocked(),
                trade.positionSize(),
                remaining,
                trade.realizedPnL() + pnl,
                trade.mfe(),
                trade.mae(),
                trade.peakFavorableR(),
                softStop
            );
        }

        return trade;
    }

    private ActiveTrade handleCandleClose(ActiveTrade trade, Candle candle, double currentRange, double previousRange) {
        if (!trade.runnerActive()) {
            return trade;
        }

        boolean stage2Active = trade.stage2Active() || (trade.initialRisk() > 0.0 && trade.mfe() >= (0.8 * trade.initialRisk()));
        double updatedSL = trade.trailingSL();
        if (stage2Active) {
            double swingBasedSL = candle.high + 10.0;
            updatedSL = Math.min(updatedSL, swingBasedSL);
        }
        boolean momentumWeakening = previousRange > 0.0 && currentRange < (previousRange * 0.6);
        if (momentumWeakening && trade.mfe() > (0.8 * trade.initialRisk())) {
            updatedSL = Math.min(updatedSL, candle.high + 5.0);
        }

        return new ActiveTrade(
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
            trade.initialRisk(),
            trade.tp1Hit(),
            trade.runnerActive(),
            stage2Active,
            trade.tailHalfLocked(),
            trade.positionSize(),
            trade.remainingSize(),
            trade.realizedPnL(),
            trade.mfe(),
            trade.mae(),
            trade.peakFavorableR(),
            updatedSL
        );
    }

    private TradeExit checkStopLoss(ActiveTrade trade, double currentPrice) {
        double effectiveSL = trade.tp1Hit() ? trade.trailingSL() : trade.stopLoss();
        double pullback = trade.mfe() - (trade.entryPrice() - currentPrice);
        if (trade.initialRisk() > 0.0 && trade.mfe() >= (2.0 * trade.initialRisk()) && pullback >= (0.5 * trade.initialRisk())) {
            double pnl = trade.realizedPnL() + ((trade.entryPrice() - currentPrice) * trade.remainingSize());
            return new TradeExit(true, pnl, "TAIL_PROTECTION_EXIT", currentPrice);
        }
        if (currentPrice >= effectiveSL) {
            double exitSize = trade.tp1Hit() ? trade.remainingSize() : trade.positionSize();
            double pnl = trade.realizedPnL() + ((trade.entryPrice() - currentPrice) * exitSize);
            String reason = trade.tp1Hit() ? "RUNNER_TRAIL_EXIT" : "STRUCTURAL_SL";
            return new TradeExit(true, pnl, reason, currentPrice);
        }
        return TradeExit.noExit();
    }

    private ActiveTrade applyTailProtection(ActiveTrade trade, double currentPrice) {
        if (!trade.runnerActive() || trade.initialRisk() <= 0.0) {
            return trade;
        }
        double pullback = trade.mfe() - (trade.entryPrice() - currentPrice);
        if (!trade.tailHalfLocked() && trade.mfe() >= (1.5 * trade.initialRisk()) && pullback >= (0.4 * trade.initialRisk())) {
            double lockSize = trade.remainingSize() * 0.5;
            double pnl = (trade.entryPrice() - currentPrice) * lockSize;
            return new ActiveTrade(
                trade.entryPrice(),
                trade.stopLoss(),
                trade.tp1Level(),
                trade.initialRisk(),
                trade.tp1Hit(),
                trade.runnerActive(),
                trade.stage2Active(),
                true,
                trade.positionSize(),
                trade.remainingSize() - lockSize,
                trade.realizedPnL() + pnl,
                trade.mfe(),
                trade.mae(),
                trade.peakFavorableR(),
                trade.trailingSL()
            );
        }
        return trade;
    }

    private void updateActiveTradeState(ActiveTrade trade, String reason) {
        stateManager.update(current -> new TradingSessionSnapshot(
            current.sessionActive(),
            current.regime(),
            current.volatilityQualified(),
            current.timePhase(),
            current.tradesTaken(),
            true,
            current.feedStable(),
            current.heartbeatAlive(),
            current.orHigh(),
            current.orLow(),
            current.cumulativeDailyLossR(),
            trade,
            reason
        ));
    }

    private void broadcastCurrentSessionState() {
        TradingSessionSnapshot state = stateManager.getSnapshot();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionActive", state.sessionActive());
        payload.put("regime", state.regime().name());
        payload.put("volatilityQualified", state.volatilityQualified());
        payload.put("timePhase", state.timePhase().name());
        payload.put("tradeActive", state.tradeActive());
        payload.put("tradesTaken", state.tradesTaken());
        payload.put("maxTradesPerDay", 2);
        payload.put("feedStable", state.feedStable());
        payload.put("heartbeatAlive", state.heartbeatAlive());
        payload.put("dailyLossR", state.cumulativeDailyLossR());
        payload.put("lastRejectReason", state.lastRejectReason());
        payload.put("orHigh", state.orHigh());
        payload.put("orLow", state.orLow());
        webSocketService.sendSessionState(payload);
    }
}
