package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.engine.AdaptiveRegimeEngine;
import com.riskpilot.engine.KillSwitchEngine;
import com.riskpilot.engine.RegimeConfidenceEngine;
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
import com.riskpilot.service.CandleAggregator;
import com.riskpilot.config.PlainWebSocketConfig;
import com.riskpilot.service.LiveMetricsLogger;
import com.riskpilot.service.SessionStateManager;
import com.riskpilot.service.TrapEngine;
import com.riskpilot.service.VixService;
import com.riskpilot.service.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
    private final TransactionTemplate transactionTemplate;  // BUG-008: For atomic trade operations

    // Store candle data for regime confidence evaluation
    private final List<RegimeConfidenceEngine.CandleData> candleHistory = new ArrayList<>();

    private String lastTriggeredCandleTime = "";
    private LocalDateTime activeSignalTime;
    private double activeExpectedEntry;
    private boolean dayBlockedByFirstTradeFailure;
    
    // BUG-017: Canonical reject reason tracking
    private final Map<String, AtomicLong> rejectReasonCounts = new LinkedHashMap<>();
    private static final int MAX_REJECT_REASONS = 256;

    // Constructor removed - using @RequiredArgsConstructor for dependency injection

    public synchronized void evaluateTick(double currentPrice) {
        // BUG-042: Per-tick logs at DEBUG level to reduce noise
        log.debug("Processing tick: {}", currentPrice);
        
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
        
        // 🔒 STEP 2: Store candle data for regime confidence evaluation
        storeCandleData(candle);
        
        // 🔒 STEP 3: Update regime filter with new candle data
        updateRegimeFilter(candle);
        
        // 🔒 STEP 4: Update volatility normalizer with opening range
        volatilityNormalizer.updateOpeningRange(candle.high(), candle.low(), candle.timestamp());
        
        // 🔒 STEP 5: Process exit logic if trade active
        if (state.tradeActive() && state.activeTradeReference() != null) {
            ActiveTradeExecution trade = state.activeTradeReference();
            
            // BUG-013: Calculate ATR and pass to fromCandleClose
            double atr = calculateSimpleAtr(candleAggregator.getValidHistory(), 14);
            trade = ActiveTradeExecution.fromCandleClose(trade, candle, atr);
            
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
        
        // 🔒 STEP 6: Check for new signal
        if (shouldEvaluateSignal(candle)) {
            evaluateSignal(candle, state);
        }
    }

    private void storeCandleData(Candle candle) {
        RegimeConfidenceEngine.CandleData candleData = new RegimeConfidenceEngine.CandleData(
            candle.open(), candle.high(), candle.low(), candle.close(), candle.timestamp()
        );
        
        candleHistory.add(candleData);
        
        // Keep only last 50 candles for regime confidence evaluation
        if (candleHistory.size() > 50) {
            candleHistory.remove(0);
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
                // BUG-013: Calculate ATR and pass to fromCandleClose
                double atr = calculateSimpleAtr(strictHistory, 14);
                ActiveTradeExecution updated = ActiveTradeExecution.fromCandleClose(trade, newestCandle, atr);
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
            double currentRange = newestCandle.getHighPrice().doubleValue() - newestCandle.getLowPrice().doubleValue();
            double previousRange = previousCandle.getHighPrice().doubleValue() - previousCandle.getLowPrice().doubleValue();
            double orRange = Math.max(0.0, state.orHigh() - state.orLow());
            // BUG-013: Calculate ATR and pass to fromCandleClose
            double atr = calculateSimpleAtr(strictHistory, 14);
            ActiveTradeExecution updated = ActiveTradeExecution.fromCandleClose(state.activeTradeReference(), newestCandle, atr);
            
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
        candleHistory.clear(); // Reset regime confidence candle data
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
        double orRange = Math.max(0.0, state.orHigh() - state.orLow());
        double tp1Distance = volatilityNormalizer.getCurrentTP1(); // Use normalized TP1
        double dynamicTp1 = signal.getEntry() - tp1Distance;
        double initialRisk = Math.abs(signal.getStopLoss() - signal.getEntry());
        
        // BUG-020: Use TrapEngine's position sizing from signal (not recalculate)
        // TrapEngine already computed the correct size based on risk parameters
        double size = signal.getConfidence() > 0 ? signal.getConfidence() : 
            (state.timePhase() == TimePhase.EARLY ? 1.0 : 0.35);
        
        // BUG-009: Set initialRiskPoints at entry and never overwrite
        ActiveTradeExecution trade = new ActiveTradeExecution(
            signal.getEntry(),
            signal.getStopLoss(),
            dynamicTp1,
            initialRisk,  // BUG-009: initialRiskPoints set at entry
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

    /**
     * BUG-008: Close trade with atomic transaction wrapper.
     * All side effects (edge tracker, adaptive regime, logging, state update) 
     * are wrapped in a single transaction to prevent partial writes on crash.
     */
    private void closeTrade(ActiveTradeExecution trade, TradeExit exit) {
        // BUG-041: Add MDC correlation ID for tracing
        String correlationId = java.util.UUID.randomUUID().toString().substring(0, 8);
        MDC.put("correlationId", correlationId);
        MDC.put("tradeId", trade.entryPrice() + "_" + System.currentTimeMillis());
        
        try {
            transactionTemplate.execute(status -> {
                executeCloseTradeInternal(trade, exit);
                return null;
            });
        } finally {
            MDC.clear();
        }
    }
    
    /**
     * Internal close trade logic - executed within transaction.
     */
    private void executeCloseTradeInternal(ActiveTradeExecution trade, TradeExit exit) {
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

        // Broadcast trade data to frontend WebSocket
        broadcastTradeData(activeSignalTime, trade, exit, realizedR);

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
            // BUG-015: Net R accumulation - wins offset losses (removed Math.min(0.0, realizedR))
            current.cumulativeDailyLossR() + realizedR,
            null,
            isFirstTradeFailure ? "FIRST_TRADE_FAILURE_DAY_BLOCK" : "ALLOW"
        ));
        broadcastCurrentSessionState();
    }
    
    /**
     * BUG-023: Restore session candles on application startup.
     * Moved from @PostConstruct to ApplicationReadyEvent to ensure
     * all infrastructure is initialized before loading persisted state.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreSessionCandles() {
        log.info("Restoring session candles from persistence...");
        // TODO: Load candles from database/cache if needed
        // This ensures candle state is available before first tick arrives
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

    /**
     * Calculate Simple ATR (Average True Range) over N periods.
     * BUG-013: Used for ATR-normalized trailing stops.
     */
    private double calculateSimpleAtr(List<CandleEntity> history, int periods) {
        if (history.size() < 2) {
            return 25.0; // Default ATR for NIFTY
        }
        
        int start = Math.max(0, history.size() - periods);
        double totalRange = 0.0;
        int count = 0;
        
        for (int i = start + 1; i < history.size(); i++) {
            CandleEntity current = history.get(i);
            CandleEntity previous = history.get(i - 1);
            
            double highLow = current.getHighPrice().doubleValue() - current.getLowPrice().doubleValue();
            double highClose = Math.abs(current.getHighPrice().doubleValue() - previous.getClosePrice().doubleValue());
            double lowClose = Math.abs(current.getLowPrice().doubleValue() - previous.getClosePrice().doubleValue());
            
            double trueRange = Math.max(highLow, Math.max(highClose, lowClose));
            totalRange += trueRange;
            count++;
        }
        
        return count > 0 ? totalRange / count : 25.0;
    }
    
    /**
     * BUG-017: Canonicalize reject reason to prevent unbounded map growth.
     */
    private String canonicalRejectReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        // Keep only uppercase alpha + underscore, strip everything else
        String canonical = reason.trim()
            .toUpperCase()
            .replaceAll("[^A-Z_]", "_")   // replace digits and punctuation
            .replaceAll("_+", "_")         // collapse consecutive underscores
            .replaceAll("^_+|_+$", "");    // trim leading/trailing underscores
        
        // Limit to 64 chars
        if (canonical.length() > 64) {
            canonical = canonical.substring(0, 64);
        }
        
        // BUG-017: Ceiling guard - prune if too many entries
        if (rejectReasonCounts.size() > MAX_REJECT_REASONS) {
            rejectReasonCounts.entrySet().removeIf(e -> e.getValue().get() <= 1);
        }
        
        return canonical.isEmpty() ? "UNKNOWN" : canonical;
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

    private void broadcastTradeData(LocalDateTime signalTime, ActiveTradeExecution trade, TradeExit exit, double realizedR) {
        try {
            // Create trade data payload matching frontend expectations
            Map<String, Object> tradeData = new LinkedHashMap<>();
            tradeData.put("id", signalTime.toString() + "_" + trade.entryPrice());
            tradeData.put("signalTime", signalTime.toString());
            tradeData.put("executeTime", LocalDateTime.now().toString());
            tradeData.put("latencySec", java.time.Duration.between(signalTime, LocalDateTime.now()).toMillis() / 1000.0);
            tradeData.put("expectedEntry", activeExpectedEntry);
            tradeData.put("actualEntry", trade.entryPrice());
            tradeData.put("slippage", trade.entryPrice() - activeExpectedEntry);
            tradeData.put("mfe", trade.mfe());
            tradeData.put("mae", trade.mae());
            tradeData.put("realizedR", realizedR);
            tradeData.put("isRunner", trade.runnerActive());
            tradeData.put("exitReason", exit.reason());
            tradeData.put("exitTime", LocalDateTime.now().toString());

            // Broadcast to plain WebSocket clients
            PlainWebSocketConfig.TradeDataWebSocketHandler.broadcastTradeData(tradeData);
            
            // Also broadcast via STOMP for compatibility
            webSocketService.sendTradeExecution(tradeData);
            
        } catch (Exception e) {
            log.error("Failed to broadcast trade data: {}", e.getMessage(), e);
        }
    }
}
