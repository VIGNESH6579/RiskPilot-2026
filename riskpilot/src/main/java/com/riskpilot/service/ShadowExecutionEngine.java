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

    private final List<RegimeConfidenceEngine.CandleData> candleHistory = new ArrayList<>();
    private final ConcurrentHashMap<String, AtomicLong> rejectReasonCounts = new ConcurrentHashMap<>();

    private static final int MAX_REJECT_REASONS = 256;

    private String lastTriggeredCandleTime = "";
    private String lastStoredCandleTime = "";
    private LocalDateTime activeSignalTime;
    private double activeExpectedEntry;
    private boolean dayBlockedByFirstTradeFailure;

    private record ClosedTradeBroadcast(
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
        broadcastCurrentSessionState();
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

        if (shouldEvaluateSignal(candle)) {
            evaluateSignal(candle, stateManager.getSnapshot());
        }
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

        if (shouldEvaluateSignal(newestCandle)) {
            evaluateSignal(newestCandle, state);
        }
    }

    @Scheduled(cron = "0 15 9 * * *", zone = "Asia/Kolkata")
    public void executeDailyHardReset() {
        restart();
    }

    public synchronized void restart() {
        candleAggregator.clearHistory();
        stateManager.resetDaily();
        candleHistory.clear();
        lastTriggeredCandleTime = "";
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
            boolean sessionActive = !now.isBefore(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(15, 30));
            double orHigh = current.orHigh();
            double orLow = current.orLow();

            List<Candle> history = candleAggregator.getValidHistory();
            if (!history.isEmpty() && now.isBefore(LocalTime.of(10, 15))) {
                Candle last = history.get(history.size() - 1);
                orHigh = Double.isFinite(orHigh) ? Math.max(orHigh, last.high) : last.high;
                orLow = Double.isFinite(orLow) ? Math.min(orLow, last.low) : last.low;
            }

            double orRange = (Double.isFinite(orHigh) && Double.isFinite(orLow)) ? (orHigh - orLow) : 0.0;
            Regime regime = orRange > config.getFilters().getMinOrRange() ? Regime.TREND : Regime.BLOCKED;

            return new TradingSessionSnapshot(
                sessionActive,
                regime,
                orRange > config.getFilters().getMinOrRange(),
                phase,
                current.tradesTaken(),
                current.tradeActive(),
                !candleAggregator.isFeedUnstable(),
                current.heartbeatAlive(),
                orHigh,
                orLow,
                current.cumulativeDailyLossR(),
                current.consecutiveLosses(),
                current.activeTradeReference(),
                current.lastRejectReason()
            );
        });
    }

    private boolean shouldEvaluateSignal(Candle candle) {
        return !candle.time.equals(lastTriggeredCandleTime)
            && candleAggregator.getValidHistory().size() >= 7
            && !dayBlockedByFirstTradeFailure;
    }

    private void evaluateSignal(Candle candle, TradingSessionSnapshot state) {
        List<Candle> history = candleAggregator.getValidHistory();
        if (history.size() < 7) {
            return;
        }

        double localSupport = history.stream()
            .skip(Math.max(0, history.size() - 6))
            .mapToDouble(c -> c.low)
            .min()
            .orElse(candle.low);
        double localResistance = history.stream()
            .skip(Math.max(0, history.size() - 6))
            .mapToDouble(c -> c.high)
            .max()
            .orElse(candle.high);

        double liveVix = vixService.getIndiaVix();
        Signal signal = trapEngine.detectTrap(history, localSupport, localResistance, liveVix, calculateSimpleAtr(history, 14));
        if (signal == null) {
            return;
        }

        double orRange = Math.max(0.0, state.orHigh() - state.orLow());
        double entrySlippageEstimate = Math.abs(candle.close - signal.getEntry());
        GateDecision decision = riskGateEngine.evaluateEntry(
            state, orRange, entrySlippageEstimate, 0L, new ArrayList<>(candleHistory));
        riskGateEngine.logDecision(state, orRange, 0L, entrySlippageEstimate, decision);

        if (!decision.allowed()) {
            logReject(state, decision.reason());
            return;
        }

        openTrade(signal, state);
        lastTriggeredCandleTime = candle.time;
        broadcastCurrentSessionState();
    }

    private void openTrade(Signal signal, TradingSessionSnapshot state) {
        LocalDateTime now = LocalDateTime.now();
        double tp1Distance = volatilityNormalizer.getCurrentTP1();
        double dynamicTp1 = "SHORT".equalsIgnoreCase(signal.getDirection())
            ? signal.getEntry() - tp1Distance
            : signal.getEntry() + tp1Distance;
        double initialRisk = Math.abs(signal.getStopLoss() - signal.getEntry());
        double size = signal.getQuantity() > 0 ? signal.getQuantity() : 1.0;

        ActiveTradeExecution trade = new ActiveTradeExecution(
            signal.getDirection(),
            signal.getEntry(),
            signal.getStopLoss(),
            dynamicTp1,
            initialRisk,
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
            current.consecutiveLosses(),
            trade,
            "ALLOW"
        ));
    }

    private TradeExit checkForcedSessionExit(ActiveTradeExecution trade, Candle candle) {
        return riskGateEngine.shouldForceLateSessionExit(stateManager.getSnapshot())
            ? exitAtPrice(trade, candle.close, "TIME_CUTOFF_EXIT")
            : TradeExit.noExit();
    }

    private TradeExit exitAtPrice(ActiveTradeExecution trade, double price, String reason) {
        double exitSize = trade.tp1Hit() ? trade.remainingSize() : trade.positionSize();
        double pnl = "SHORT".equalsIgnoreCase(trade.direction())
            ? trade.realizedPnL() + ((trade.entryPrice() - price) * exitSize)
            : trade.realizedPnL() + ((price - trade.entryPrice()) * exitSize);
        return new TradeExit(true, pnl, reason, price);
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
            lastRejectReason
        ));
    }

    private void closeTrade(ActiveTradeExecution trade, TradeExit exit) {
        MDC.put("correlationId", UUID.randomUUID().toString().substring(0, 8));
        MDC.put("entry", String.valueOf(trade.entryPrice()));

        ClosedTradeBroadcast postCommitBroadcast = null;
        try {
            postCommitBroadcast = transactionTemplate.execute(status -> executeCloseTradeInternal(trade, exit));
        } finally {
            MDC.clear();
        }

        if (postCommitBroadcast != null) {
            try {
                edgeTracker.addTradeResult(
                    postCommitBroadcast.realizedR(),
                    postCommitBroadcast.trade().tp1Hit(),
                    postCommitBroadcast.trade().runnerActive(),
                    postCommitBroadcast.entrySlippage(),
                    postCommitBroadcast.runnerSlippage()
                );
                adaptiveRegimeEngine.addTradeResult(
                    postCommitBroadcast.realizedR(),
                    postCommitBroadcast.trade().tp1Hit(),
                    postCommitBroadcast.trade().runnerActive(),
                    postCommitBroadcast.entrySlippage(),
                    postCommitBroadcast.runnerSlippage(),
                    postCommitBroadcast.sessionFeatures()
                );
            } catch (Exception e) {
                log.error("POST_COMMIT_EDGE_UPDATE_FAILED: {}", e.getMessage(), e);
            }
            try {
                liveMetricsLogger.logShadowExecution(
                    postCommitBroadcast.signalTime(),
                    LocalDateTime.now(),
                    postCommitBroadcast.expectedEntry(),
                    postCommitBroadcast.trade().entryPrice(),
                    postCommitBroadcast.expectedExit(),
                    postCommitBroadcast.exit().exitPrice(),
                    postCommitBroadcast.trade().tp1Hit(),
                    postCommitBroadcast.trade().runnerActive(),
                    postCommitBroadcast.trade().mfe(),
                    postCommitBroadcast.trade().mae(),
                    postCommitBroadcast.realizedR(),
                    "ALLOW",
                    "",
                    postCommitBroadcast.stateRegime(),
                    postCommitBroadcast.stateTimePhase(),
                    postCommitBroadcast.stateFeedStable(),
                    postCommitBroadcast.exit().reason(),
                    LocalDateTime.now()
                );
            } catch (Exception e) {
                log.error("POST_COMMIT_METRICS_LOG_FAILED: {}", e.getMessage(), e);
            }
            activeSignalTime = null;
            activeExpectedEntry = 0.0;
            try {
                broadcastTradeData(
                    postCommitBroadcast.signalTime(),
                    postCommitBroadcast.expectedEntry(),
                    postCommitBroadcast.trade(),
                    postCommitBroadcast.exit(),
                    postCommitBroadcast.realizedR()
                );
                broadcastCurrentSessionState();
            } catch (Exception e) {
                log.error("POST_COMMIT_BROADCAST_FAILED: {}", e.getMessage(), e);
            }
        }
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
            firstTradeFailure ? "FIRST_TRADE_FAILURE_DAY_BLOCK" : "ALLOW"
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
            tradeData.put("isRunner", trade.runnerActive());
            tradeData.put("exitReason", exit.reason());
            tradeData.put("exitTime", LocalDateTime.now().toString());

            PlainWebSocketConfig.TradeDataWebSocketHandler.broadcastTradeData(tradeData);
        } catch (Exception e) {
            log.error("Failed to broadcast trade data: {}", e.getMessage(), e);
        }
    }
}
