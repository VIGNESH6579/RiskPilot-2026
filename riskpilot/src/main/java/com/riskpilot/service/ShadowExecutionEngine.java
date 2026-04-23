package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.engine.KillSwitchEngine;
import com.riskpilot.engine.RiskGateEngine;
import com.riskpilot.engine.RegimeFilter;
import com.riskpilot.engine.RealTimeEdgeTracker;
import com.riskpilot.engine.VolatilityNormalizer;
import com.riskpilot.model.ActiveTradeExecution;
import com.riskpilot.model.Candle;
import com.riskpilot.model.CandleEntity;
import com.riskpilot.model.GateDecision;
import com.riskpilot.model.Signal;
import com.riskpilot.model.TimePhase;
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
    private final RegimeFilter regimeFilter;
    private final RealTimeEdgeTracker edgeTracker;
    private final VolatilityNormalizer volatilityNormalizer;
    private final AdaptiveRegimeEngine adaptiveRegimeEngine;
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

    // Constructor removed - using @RequiredArgsConstructor for dependency injection

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

    public synchronized void evaluateCandle(Candle candle) {
        TradingSessionSnapshot state = stateManager.getSnapshot();
        
        // 🔒 STEP 1: Update candle aggregator
        candleAggregator.addCandle(candle);
        
        // 🔒 STEP 2: Update regime filter with new candle data
        updateRegimeFilter(candle);
        
        // 🔒 STEP 3: Update volatility normalizer with opening range
        volatilityNormalizer.updateOpeningRange(candle.high(), candle.low(), candle.timestamp());
        
        // 🔒 STEP 4: Process exit logic if trade active
        if (state.tradeActive() && state.activeTradeReference() != null) {
            ActiveTradeExecution trade = state.activeTradeReference();
            
            // Candle close logic
            trade = ActiveTradeExecution.fromCandleClose(trade, candle);
            
            // Check for late session forced exit
            TradeExit forcedExit = checkForcedSessionExit(trade, candle);
            if (forcedExit.triggered()) {
                closeTrade(trade, forcedExit);
                return;
            }
            
            updateActiveTradeState(trade, state.lastRejectReason());
            broadcastCurrentSessionState();
            return;
        }
        
        // 🔒 STEP 5: Check for new signal
        if (shouldEvaluateSignal(candle)) {
            evaluateSignal(candle, state);
        }
    }

    private void updateRegimeFilter(Candle candle) {
        // Calculate ATR (simplified - you may want to use proper ATR calculation)
        double atr = Math.abs(candle.high() - candle.low());
        
        // Update regime filter
        regimeFilter.processCandle(
            candle.open(), candle.high(), candle.low(), candle.close(),
            candle.volume(), candle.timestamp(), atr
        );
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

            List<CandleEntity> history = candleAggregator.getValidHistory();
            if (!history.isEmpty()) {
                CandleEntity last = history.get(history.size() - 1);
                if (now.isBefore(LocalTime.of(10, 15))) {
                    orHigh = Math.max(orHigh, last.getHighPrice());
                    orLow = Math.min(orLow, last.getLowPrice());
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
        double tp1Distance = volatilityNormalizer.getCurrentTP1(); // Use normalized TP1
        double dynamicTp1 = signal.getEntry() - tp1Distance;
        double initialRisk = Math.abs(signal.getStopLoss() - signal.getEntry());
        
        ActiveTradeExecution trade = new ActiveTradeExecution(
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

    private void closeTrade(ActiveTradeExecution trade, TradeExit exit) {
        TradingSessionSnapshot state = stateManager.getSnapshot();
        if (state.activeTradeReference() == null || activeSignalTime == null) {
            return;
        }
        double expectedExit = trade.tp1Hit() ? trade.trailingSL() : trade.stopLoss();
        double riskPts = Math.abs(trade.stopLoss() - trade.entryPrice());
        double realizedR = riskPts == 0.0 ? 0.0 : exit.pnlPoints() / riskPts;

        // Update real-time edge tracker with trade result
        double entrySlippage = Math.abs(trade.entryPrice() - activeExpectedEntry);
        double runnerSlippage = trade.runnerActive() ? Math.abs(exit.exitPrice() - activeExpectedEntry) : 0.0;
        edgeTracker.addTradeResult(
            realizedR, 
            trade.tp1Hit(), 
            trade.runnerActive(),
            entrySlippage,
            runnerSlippage
        );

        // Feed adaptive regime engine with trade results
        RegimeFilter.RegimeMetrics currentRegime = regimeFilter.getCurrentRegime();
        AdaptiveRegimeEngine.SessionFeatures sessionFeatures = new AdaptiveRegimeEngine.SessionFeatures(
            currentRegime.getOrRange(),
            currentRegime.getAtrRatio(),
            currentRegime.getTrendEfficiency(),
            currentRegime.getBreakoutHoldRate(),
            currentRegime.getRegimeScore()
        );
        
        adaptiveRegimeEngine.addTradeResult(
            realizedR,
            trade.tp1Hit(),
            trade.runnerActive(),
            entrySlippage,
            runnerSlippage,
            sessionFeatures
        );

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

    // Trade execution methods moved to ActiveTradeExecution model

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
