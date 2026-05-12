package com.riskpilot.service;

import com.riskpilot.config.PlainWebSocketConfig;
import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.engine.AdaptiveRegimeEngine;
import com.riskpilot.engine.KillSwitchEngine;
import com.riskpilot.engine.RealTimeEdgeTracker;
import com.riskpilot.engine.RegimeConfidenceEngine;
import com.riskpilot.engine.RegimeFilter;
import com.riskpilot.engine.RiskGateEngine;
import com.riskpilot.engine.VolatilityNormalizer;
import com.riskpilot.model.ActiveTradeExecution;
import com.riskpilot.model.Candle;
import com.riskpilot.model.GateDecision;
import com.riskpilot.model.Regime;
import com.riskpilot.model.Signal;
import com.riskpilot.model.TimePhase;
import com.riskpilot.model.TradeExit;
import com.riskpilot.model.TradingSessionSnapshot;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final TransactionTemplate transactionTemplate;
    private final MarketSessionService marketSessionService;

    private final List<RegimeConfidenceEngine.CandleData> candleHistory = new ArrayList<>();
    private final ConcurrentHashMap<String, AtomicLong> rejectReasonCounts = new ConcurrentHashMap<>();

    private static final int MAX_REJECT_REASONS = 256;

    private String lastTriggeredCandleTime = "";
    private String lastEvaluatedCandleTime = "";
    private String lastStoredCandleTime = "";
    private LocalDateTime activeSignalTime;
    private double activeExpectedEntry;
    private boolean dayBlockedByFirstTradeFailure;

    private static record ClosedTradeBroadcast(
        LocalDateTime signalTime,
        double expectedEntry,
        double expectedExit,
        ActiveTradeExecution trade,
        TradeExit exit,
        double realizedR,
        double entrySlippage,
        double runnerSlippage,
        Regime stateRegime,
        TimePhase stateTimePhase,
        boolean stateFeedStable,
        AdaptiveRegimeEngine.SessionFeatures sessionFeatures
    ) {}

    public synchronized void evaluateTick(double currentPrice) {
        log.debug("Processing tick: {}", currentPrice);

        if (killSwitchEngine.isKillSwitchTriggered()) {
            log.error("KILL_SWITCH_ACTIVE - ignoring tick processing");
            return;
        }

        TradingSessionSnapshot state = stateManager.getSnapshot();
        if (!state.tradeActive() || state.activeTradeReference() == null) {
            evaluateLatestClosedCandleSignal();
            broadcastCurrentSessionState(currentPrice);
            return;
        }

        ActiveTradeExecution trade = ActiveTradeExecution.updateExcursions(state.activeTradeReference(), currentPrice);
        trade = ActiveTradeExecution.fromTickTP1(trade, currentPrice);

        TradeExit exit = ActiveTradeExecution.checkStopLoss(trade, currentPrice);
        if (exit.triggered()) {
            closeTrade(trade, exit);
            return;
        }

        updateActiveTradeState(trade, state.lastRejectReason());
        broadcastCurrentSessionState(currentPrice);
    }

    private void evaluateLatestClosedCandleSignal() {
        List<Candle> history = candleAggregator.getValidHistory();
        if (history.isEmpty()) {
            return;
        }
        Candle newestCandle = history.get(history.size() - 1);
        ingestClosedCandleForIndicators(newestCandle);
        updateSessionStateFromTime(newestCandle.timestamp().toLocalTime());
        evaluateSignalIfEligible(newestCandle, stateManager.getSnapshot());
    }

    public synchronized void evaluateCandle(Candle candle) {
        candleAggregator.addCandle(candle);
        ingestClosedCandleForIndicators(candle);

        TradingSessionSnapshot state = stateManager.getSnapshot();
        updateSessionStateFromTime(candle.timestamp().toLocalTime());

        if (state.tradeActive() && state.activeTradeReference() != null) {
            ActiveTradeExecution updated = ActiveTradeExecution.fromCandleClose(
                state.activeTradeReference(), candle, calculateSimpleAtr(candleAggregator.getValidHistory(), 14));

            TradeExit forcedExit = checkForcedSessionExit(updated, candle);
            if (forcedExit.triggered()) {
                closeTrade(updated, forcedExit);
                return;
            }

            updateActiveTradeState(updated, state.lastRejectReason());
            broadcastCurrentSessionState();
            return;
        }

        evaluateSignalIfEligible(candle, stateManager.getSnapshot());
    }

    public synchronized void evaluateCandleClose() {
        List<Candle> history = candleAggregator.getValidHistory();
        if (history.isEmpty()) {
            return;
        }

        Candle newestCandle = history.get(history.size() - 1);
        ingestClosedCandleForIndicators(newestCandle);
        updateSessionStateFromTime(newestCandle.timestamp().toLocalTime());
        if (history.size() < 10) {
            return;
        }

        if (dayBlockedByFirstTradeFailure) {
            logReject(stateManager.getSnapshot(), "FIRST_TRADE_FAILURE_DAY_BLOCK");
            return;
        }

        TradingSessionSnapshot state = stateManager.getSnapshot();

        if (riskGateEngine.shouldForceLateSessionExit(state)) {
            log.warn("LATE_SESSION_FORCE_EXIT: time cutoff reached");
            if (state.tradeActive() && state.activeTradeReference() != null) {
                ActiveTradeExecution updated = ActiveTradeExecution.fromCandleClose(
                    state.activeTradeReference(), newestCandle, calculateSimpleAtr(history, 14));
                closeTrade(updated, exitAtPrice(updated, newestCandle.close, "TIME_CUTOFF_EXIT"));
            }
            return;
        }

        if (state.tradeActive() && state.activeTradeReference() != null) {
            double currentRange = newestCandle.high - newestCandle.low;
            double orRange = Math.max(0.0, state.orHigh() - state.orLow());
            ActiveTradeExecution updated = ActiveTradeExecution.fromCandleClose(
                state.activeTradeReference(), newestCandle, calculateSimpleAtr(history, 14));

            if (state.timePhase() != TimePhase.EARLY && orRange > 0.0 && currentRange < (orRange * 0.2)) {
                closeTrade(updated, exitAtPrice(updated, newestCandle.close, "VOLATILITY_COLLAPSE_EXIT"));
                return;
            }

            updateActiveTradeState(updated, state.lastRejectReason());
            broadcastCurrentSessionState();
            return;
        }

        evaluateSignalIfEligible(newestCandle, state);
    }

    @Scheduled(cron = "0 14 9 * * *", zone = "Asia/Kolkata")
    public void executeDailyHardReset() {
        restart();
    }

    public synchronized void restart() {
        candleAggregator.clearHistory();
        stateManager.resetDaily();
        candleHistory.clear();
        lastTriggeredCandleTime = "";
        lastEvaluatedCandleTime = "";
        lastStoredCandleTime = "";
        activeSignalTime = null;
        activeExpectedEntry = 0.0;
        dayBlockedByFirstTradeFailure = false;
        broadcastCurrentSessionState();
    }

    private void storeCandleData(Candle candle) {
        candleHistory.add(new RegimeConfidenceEngine.CandleData(
            candle.open, candle.high, candle.low, candle.close, candle.timestamp()
        ));

        if (candleHistory.size() > 50) {
            candleHistory.remove(0);
        }
    }

    private void ingestClosedCandleForIndicators(Candle candle) {
        if (candle == null || candle.time.equals(lastStoredCandleTime)) {
            return;
        }

        storeCandleData(candle);
        updateRegimeFilter(candle);
        volatilityNormalizer.updateOpeningRange(candle.high, candle.low, candle.timestamp());
        lastStoredCandleTime = candle.time;
    }

    private void updateRegimeFilter(Candle candle) {
        double atr = Math.abs(candle.high - candle.low);
        regimeFilter.processCandle(
            candle.open, candle.high, candle.low, candle.close,
            candle.volume(), candle.timestamp(), atr
        );
    }

    private void updateSessionStateFromTime(LocalTime now) {
        TimePhase phase = now.isBefore(LocalTime.NOON)
            ? TimePhase.EARLY
            : (now.isBefore(LocalTime.of(13, 30)) ? TimePhase.MID : TimePhase.LATE);

        stateManager.update(current -> {
            // FIX: Use marketSessionService to determine session activity
            boolean sessionActive = marketSessionService.isMarketOpen();
            double orHigh = current.orHigh();
            double orLow = current.orLow();

            List<Candle> history = candleAggregator.getValidHistory();
            if (!history.isEmpty() && now.isBefore(LocalTime.of(9, 45))) {
                Candle last = history.get(history.size() - 1);
                orHigh = Double.isFinite(orHigh) ? Math.max(orHigh, last.high) : last.high;
                orLow = Double.isFinite(orLow) ? Math.min(orLow, last.low) : last.low;
            }

            double orRange = (Double.isFinite(orHigh) && Double.isFinite(orLow))
                ? orHigh - orLow
                : 0.0;

            Regime regime = Regime.TREND;  // Default to TREND regime

            return new TradingSessionSnapshot(
                sessionActive,
                regime,
                true,
                phase,
                current.tradesTaken(),
                current.tradeActive(),
                current.feedStable(),
                current.heartbeatAlive(),
                orHigh,
                orLow,
                current.cumulativeDailyLossR(),
                current.consecutiveLosses(),
                current.activeTradeReference(),
                current.lastRejectReason(),
                current.paperBalance()
            );
        });
    }

    private void evaluateSignalIfEligible(Candle candle, TradingSessionSnapshot state) {
        if (!state.sessionActive() || state.tradeActive()) {
            return;
        }

        if (state.tradesTaken() >= config.getRisk().getMaxTradesPerDay()) {
            logReject(state, "MAX_TRADES_EXCEEDED");
            return;
        }

        // FIX: Use TrapEngine to detect signals instead of edgeTracker.detectEdge
        List<Candle> history = candleAggregator.getValidHistory();
        double currentVix = 15.0; // Fallback
        try {
            currentVix = vixService.getIndiaVix();
        } catch (Exception e) {
            log.warn("VixService.getIndiaVix() failed, using fallback: {}", e.getMessage());
        }

        Signal signal = trapEngine.detectTrap(
            history,
            0.0,  // localSupport - would need to be calculated from structure
            0.0,  // localResistance - would need to be calculated from structure
            currentVix,
            calculateSimpleAtr(history, 14)
        );

        if (signal == null || candle.time.equals(lastTriggeredCandleTime)) {
            return;
        }

        // FIX: Use evaluateEntry with proper parameters instead of evaluate(snapshot, signal)
        double orRange = Math.max(0.0, state.orHigh() - state.orLow());
        double entrySlippage = 0.0;  // Would be calculated from market conditions
        long latencyMs = 0L;  // Would be calculated from signal timing
        
        GateDecision decision = riskGateEngine.evaluateEntry(
            state,
            orRange,
            entrySlippage,
            latencyMs,
            candleHistory
        );

        if (!decision.allowed()) {
            logReject(state, decision.reason());
            return;
        }

        executeTrade(signal, candle);
        lastTriggeredCandleTime = candle.time;
    }

    private void executeTrade(Signal signal, Candle candle) {
        // FIX: Create ActiveTradeExecution using proper constructor instead of initiate()
        double riskPoints = Math.abs(signal.getStopLoss() - signal.getEntry());
        ActiveTradeExecution trade = new ActiveTradeExecution(
            "SHORT",  // direction
            signal.getEntry(),  // entryPrice
            signal.getStopLoss(),  // stopLoss
            signal.getTarget(),  // tp1Level
            riskPoints,  // initialRiskPoints
            false,  // tp1Hit
            false,  // runnerActive
            1.0,  // positionSize
            1.0,  // remainingSize
            0.0,  // realizedPnL
            0.0,  // mfe
            0.0,  // mae
            0.0,  // peakFavorableR
            signal.getStopLoss()  // trailingSL
        );

        activeSignalTime = candle.timestamp();
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
            current.consecutiveLosses(),
            trade,
            "ALLOW",
            current.paperBalance()
        ));
        broadcastCurrentSessionState();
    }

    private void closeTrade(ActiveTradeExecution trade, TradeExit exit) {
        ClosedTradeBroadcast broadcast = executeCloseTradeInternal(trade, exit);
        if (broadcast != null) {
            broadcastTradeData(
                broadcast.signalTime(),
                broadcast.expectedEntry(),
                broadcast.trade(),
                broadcast.exit(),
                broadcast.realizedR()
            );
        }
        broadcastCurrentSessionState();
    }

    private ClosedTradeBroadcast executeCloseTradeInternal(ActiveTradeExecution trade, TradeExit exit) {
        TradingSessionSnapshot state = stateManager.getSnapshot();
        if (state.activeTradeReference() == null || activeSignalTime == null) {
            return null;
        }

        LocalDateTime signalTime = activeSignalTime;
        double expectedEntry = activeExpectedEntry;
        double expectedExit = trade.tp1Hit() ? trade.trailingSL() : trade.stopLoss();
        double riskPts = Math.max(0.0001, trade.initialRiskPoints());
        double realizedR = exit.pnlPoints() / riskPts;
        double entrySlippage = Math.abs(trade.entryPrice() - expectedEntry);
        double runnerSlippage = trade.runnerActive() ? Math.abs(exit.exitPrice() - expectedExit) : 0.0;

        RegimeFilter.RegimeMetrics currentRegime = regimeFilter.getCurrentRegime();
        AdaptiveRegimeEngine.SessionFeatures sessionFeatures = currentRegime != null
            ? new AdaptiveRegimeEngine.SessionFeatures(
                currentRegime.getOrRange(),
                currentRegime.getAtrRatio(),
                currentRegime.getTrendEfficiency(),
                currentRegime.getBreakoutHoldRate(),
                currentRegime.getRegimeScore())
            : new AdaptiveRegimeEngine.SessionFeatures(0.0, 0.0, 0.0, 0.0, 0);

        boolean firstTradeFailure = state.tradesTaken() == 1 && !trade.tp1Hit() && trade.mae() < -80.0 && realizedR <= -1.0;
        if (firstTradeFailure) {
            dayBlockedByFirstTradeFailure = true;
        }

        int newConsecutiveLosses = realizedR < 0.0 ? state.consecutiveLosses() + 1 : 0;

        // Calculate paper balance change
        double balanceChange = exit.pnlPoints() * 50; // Assuming lot size of 50 for NIFTY
        
        stateManager.update(current -> new TradingSessionSnapshot(
            current.sessionActive(),
            firstTradeFailure ? Regime.BLOCKED : current.regime(),
            current.volatilityQualified(),
            current.timePhase(),
            current.tradesTaken(),
            false,
            current.feedStable(),
            current.heartbeatAlive(),
            current.orHigh(),
            current.orLow(),
            current.cumulativeDailyLossR() + realizedR,
            newConsecutiveLosses,
            null,
            firstTradeFailure ? "FIRST_TRADE_FAILURE_DAY_BLOCK" : "ALLOW",
            current.paperBalance() + balanceChange
        ));
        return new ClosedTradeBroadcast(
            signalTime,
            expectedEntry,
            expectedExit,
            trade,
            exit,
            realizedR,
            entrySlippage,
            runnerSlippage,
            state.regime(),
            state.timePhase(),
            state.feedStable(),
            sessionFeatures
        );
    }

    private TradeExit checkForcedSessionExit(ActiveTradeExecution trade, Candle candle) {
        if (riskGateEngine.shouldForceLateSessionExit(stateManager.getSnapshot())) {
            return exitAtPrice(trade, candle.close, "TIME_CUTOFF_EXIT");
        }
        return TradeExit.noExit();
    }

    private TradeExit exitAtPrice(ActiveTradeExecution trade, double price, String reason) {
        double pnl = trade.direction().equalsIgnoreCase("BUY") ? (price - trade.entryPrice()) : (trade.entryPrice() - price);
        return new TradeExit(true, pnl, reason, price);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreSessionCandles() {
        log.info("Session candle restore ready; existing in-memory history size={}",
            candleAggregator.getValidHistory().size());
    }

    private void logReject(TradingSessionSnapshot state, String reason) {
        rejectReasonCounts.computeIfAbsent(canonicalRejectReason(reason), ignored -> new AtomicLong())
            .incrementAndGet();

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
            current.consecutiveLosses(),
            current.activeTradeReference(),
            reason,
            current.paperBalance()
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

    private double calculateSimpleAtr(List<Candle> history, int periods) {
        if (history.size() < 2) {
            return 25.0;
        }

        int start = Math.max(0, history.size() - periods);
        double totalRange = 0.0;
        int count = 0;

        for (int i = start + 1; i < history.size(); i++) {
            Candle current = history.get(i);
            Candle previous = history.get(i - 1);
            double highLow = current.high - current.low;
            double highClose = Math.abs(current.high - previous.close);
            double lowClose = Math.abs(current.low - previous.close);
            totalRange += Math.max(highLow, Math.max(highClose, lowClose));
            count++;
        }

        return count > 0 ? totalRange / count : 25.0;
    }

    private String canonicalRejectReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }

        String canonical = reason.trim()
            .toUpperCase()
            .replaceAll("[^A-Z_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");

        if (canonical.length() > 64) {
            canonical = canonical.substring(0, 64);
        }

        if (rejectReasonCounts.size() > MAX_REJECT_REASONS) {
            rejectReasonCounts.entrySet().removeIf(e -> e.getValue().get() <= 1);
        }

        return canonical.isEmpty() ? "UNKNOWN" : canonical;
    }

    private void broadcastCurrentSessionState() {
        broadcastCurrentSessionState(0.0);
    }

    private void broadcastCurrentSessionState(double currentPrice) {
        TradingSessionSnapshot state = stateManager.getSnapshot();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionActive", state.sessionActive());
        payload.put("regime", state.regime().name());
        payload.put("volatilityQualified", state.volatilityQualified());
        payload.put("timePhase", state.timePhase().name());
        payload.put("tradeActive", state.tradeActive());
        payload.put("tradesTaken", state.tradesTaken());
        payload.put("maxTradesPerDay", config.getRisk().getMaxTradesPerDay());
        payload.put("feedStable", state.feedStable());
        payload.put("heartbeatAlive", state.heartbeatAlive());
        payload.put("dailyLossR", state.cumulativeDailyLossR());
        payload.put("consecutiveLosses", state.consecutiveLosses());
        payload.put("lastRejectReason", state.lastRejectReason());
        payload.put("orHigh", state.orHigh());
        payload.put("orLow", state.orLow());
        payload.put("paperBalance", state.paperBalance());

        // Add real-time market data to broadcast
        payload.put("spot", currentPrice > 0 ? currentPrice : 0.0);
        payload.put("vix", vixService.getIndiaVix());

        webSocketService.sendSessionState(payload);
    }

    private void broadcastTradeData(
        LocalDateTime signalTime,
        double expectedEntry,
        ActiveTradeExecution trade,
        TradeExit exit,
        double realizedR
    ) {
        try {
            Map<String, Object> tradeData = new LinkedHashMap<>();
            tradeData.put("id", signalTime + "_" + trade.entryPrice());
            tradeData.put("signalTime", signalTime.toString());
            tradeData.put("executeTime", LocalDateTime.now().toString());
            tradeData.put("latencySec", java.time.Duration.between(signalTime, LocalDateTime.now()).toMillis() / 1000.0);
            tradeData.put("expectedEntry", expectedEntry);
            tradeData.put("actualEntry", trade.entryPrice());
            tradeData.put("slippage", trade.entryPrice() - expectedEntry);
            tradeData.put("mfe", trade.mfe());
            tradeData.put("mae", trade.mae());
            tradeData.put("realizedR", realizedR);
            tradeData.put("direction", trade.direction());
            tradeData.put("tp1Hit", trade.tp1Hit());
            tradeData.put("runnerCaptured", trade.runnerActive());
            tradeData.put("exitReason", exit.reason());
            tradeData.put("exitTime", LocalDateTime.now().toString());

            PlainWebSocketConfig.TradeDataWebSocketHandler.broadcastTradeData(tradeData);
        } catch (Exception e) {
            log.error("Failed to broadcast trade data: {}", e.getMessage(), e);
        }
    }

    private void updateActiveTradeState(ActiveTradeExecution trade, String lastRejectReason) {
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
            current.consecutiveLosses(),
            trade,
            lastRejectReason,
            current.paperBalance()
        ));
    }
}
