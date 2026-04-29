package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.engine.AdaptiveRegimeEngine;
import com.riskpilot.engine.KillSwitchEngine;
import com.riskpilot.engine.RealTimeEdgeTracker;
import com.riskpilot.engine.RegimeConfidenceEngine;
import com.riskpilot.engine.RegimeFilter;
import com.riskpilot.engine.RiskGateEngine;
import com.riskpilot.engine.VolatilityNormalizer;
import com.riskpilot.event.CandleClosedEvent;
import com.riskpilot.exception.StaleFeedException;
import com.riskpilot.model.ActiveTradeExecution;
import com.riskpilot.model.Candle;
import com.riskpilot.model.GateDecision;
import com.riskpilot.model.MarketTick;
import com.riskpilot.model.Regime;
import com.riskpilot.model.Signal;
import com.riskpilot.model.TimePhase;
import com.riskpilot.model.Trade;
import com.riskpilot.model.TradeExit;
import com.riskpilot.model.TradeLog;
import com.riskpilot.model.TradeView;
import com.riskpilot.model.TradingSessionSnapshot;
import com.riskpilot.repository.TradeRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowExecutionEngine {
    private static final String DEFAULT_SYMBOL = "NIFTY";

    private final RiskPilotProperties config;
    private final RiskGateEngine riskGateEngine;
    private final KillSwitchEngine killSwitchEngine;
    private final SessionStateManager stateManager;
    private final MarketDataStateService marketDataStateService;
    private final CandleAggregator candleAggregator;
    private final TrapEngine trapEngine;
    private final LiveMetricsLogger liveMetricsLogger;
    private final WebSocketService webSocketService;
    private final VolatilityNormalizer volatilityNormalizer;
    private final RegimeFilter regimeFilter;
    private final RegimeConfidenceEngine regimeConfidenceEngine;
    private final RealTimeEdgeTracker edgeTracker;
    private final AdaptiveRegimeEngine adaptiveRegimeEngine;
    private final StrictValidationService strictValidationService;
    private final NtfyNotificationService ntfyNotificationService;
    private final TradeRepository tradeRepository;
    private final MarketSessionService marketSessionService;
    private final ReentrantLock tradeStateLock = new ReentrantLock();
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, AtomicInteger> rejectReasonCounts = new ConcurrentHashMap<>();
    private final AtomicLong signalOpportunityCount = new AtomicLong();
    private final AtomicLong rejectedSignalCount = new AtomicLong();

    private String lastTriggeredCandleTime = "";
    private LocalDateTime activeSignalTime;
    private LocalDateTime activeExecutionTime;
    private double activeExpectedEntry;
    private long activeEntryLatencyMs;
    private Long activeTradeId;
    private volatile RegimeConfidenceEngine.RegimeScore lastRegimeConfidenceScore;

    public void evaluateTick(StrictValidationService.ValidationResult validationResult) {
        tradeStateLock.lock();
        try {
        MarketTick tick = validationResult.tick();
        if (killSwitchEngine.isKillSwitchTriggered()) {
            log.warn("Kill switch active, ignoring tick");
            return;
        }

        log.debug(
            "ExecutionEngine trigger seq={} price={} exchangeTs={} receiveTs={} ageMs={} allowExecution={}",
            tick.sequenceId(),
            tick.price(),
            tick.exchangeTimestamp(),
            tick.receivedAt(),
            tick.sourceAgeMs(),
            validationResult.allowExecution()
        );

        if (!validationResult.allowExecution()) {
            log.info(
                "EXECUTION_SKIPPED_MARKET_CLOSED seq={} price={} exchangeTs={} receiveTs={} ageMs={}",
                tick.sequenceId(),
                tick.price(),
                tick.exchangeTimestamp(),
                tick.receivedAt(),
                tick.sourceAgeMs()
            );
            return;
        }

        TradingSessionSnapshot state = stateManager.getSnapshot();
        if (!state.tradeActive() || state.activeTradeReference() == null) {
            return;
        }

        ActiveTradeExecution trade = ActiveTradeExecution.updateExcursions(state.activeTradeReference(), tick.price());
        trade = ActiveTradeExecution.fromTickTP1(trade, tick.price());

        TradeExit exit = ActiveTradeExecution.checkStopLoss(trade, tick.price());
        if (exit.triggered()) {
            closeTrade(trade, exit, tick);
            return;
        }

        updateActiveTradeState(trade, state.lastRejectReason(), tick.price());
        } finally {
            tradeStateLock.unlock();
        }
    }

    @EventListener
    public void onCandleClosed(CandleClosedEvent event) {
        evaluateCandle(event.candle());
    }

    public void evaluateCandle(Candle candle) {
        tradeStateLock.lock();
        try {
        if (!marketSessionService.isMarketOpen(candle.timestamp())) {
            log.info("ShadowExecutionEngine skipping candle evaluation outside market session timestamp={}", candle.timestamp());
            return;
        }
        volatilityNormalizer.updateOpeningRange(candle.high, candle.low, candle.timestamp());

        List<Candle> history = candleAggregator.getValidHistory();
        double atr = computeSimpleAtr(history, 5);
        regimeFilter.processCandle(
            candle.open,
            candle.high,
            candle.low,
            candle.close,
            candle.tickCount(),
            candle.timestamp(),
            atr
        );

        updateSessionStateFromTime(candle.timestamp().toLocalTime());
        TradingSessionSnapshot state = stateManager.getSnapshot();

        if (state.tradeActive() && state.activeTradeReference() != null) {
            ActiveTradeExecution trade = ActiveTradeExecution.fromCandleClose(state.activeTradeReference(), candle);
            if (riskGateEngine.shouldForceLateSessionExit(state)) {
                try {
                    MarketTick exitTick = requireLiveTick("TIME_CUTOFF_EXIT");
                    closeTrade(trade, exitAtPrice(trade, exitTick.price(), "TIME_CUTOFF_EXIT", "REAL"), exitTick);
                } catch (StaleFeedException staleFeedException) {
                    closeTrade(trade, exitAtPrice(trade, candle.close, "FEED_STALE_EXIT", "ESTIMATED"), null);
                }
                return;
            }
            updateActiveTradeState(trade, state.lastRejectReason(), candle.close);
            return;
        }

        maybeOpenTrade(state, history);
        } finally {
            tradeStateLock.unlock();
        }
    }

    public void evaluateCandleClose() {
        tradeStateLock.lock();
        try {
        List<Candle> history = candleAggregator.getValidHistory();
        if (history.isEmpty()) {
            return;
        }
        evaluateCandle(history.get(history.size() - 1));
        } finally {
            tradeStateLock.unlock();
        }
    }

    public void restart() {
        tradeStateLock.lock();
        try {
        cancelPersistedActiveTrade("ENGINE_RESTART");
        stateManager.resetDaily();
        candleAggregator.clearHistory();
        volatilityNormalizer.reset();
        regimeFilter.reset();
        edgeTracker.reset();
        rejectReasonCounts.clear();
        signalOpportunityCount.set(0L);
        rejectedSignalCount.set(0L);
        lastTriggeredCandleTime = "";
        activeSignalTime = null;
        activeExecutionTime = null;
        activeExpectedEntry = 0.0;
        activeEntryLatencyMs = 0L;
        activeTradeId = null;
        lastRegimeConfidenceScore = null;
        broadcastCurrentSessionState();
        } finally {
            tradeStateLock.unlock();
        }
    }

    @PreDestroy
    public void shutdown() {
        broadcastExecutor.shutdownNow();
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void executeDailyHardReset() {
        restart();
    }

    private void maybeOpenTrade(TradingSessionSnapshot state, List<Candle> history) {
        if (history.size() < 7) {
            return;
        }

        Candle latest = history.get(history.size() - 1);
        String candleId = latest.date + "T" + latest.time;
        if (candleId.equals(lastTriggeredCandleTime)) {
            return;
        }
        signalOpportunityCount.incrementAndGet();

        if (!strictValidationService.canExecuteNewTrade()) {
            logReject(state, "STRICT_LIMIT_BLOCK");
            return;
        }

        int size = history.size();
        List<Candle> priorCandles = history.subList(Math.max(0, size - 8), size - 2);
        double localResistance = priorCandles.stream().mapToDouble(c -> c.high).max().orElse(latest.high);
        double localSupport = priorCandles.stream().mapToDouble(c -> c.low).min().orElse(latest.low);

        Signal signal = trapEngine.detectTrap(history, localSupport, localResistance);
        if (signal == null) {
            return;
        }

        MarketTick entryTick;
        try {
            entryTick = requireLiveTick("ENTRY_TICK_REQUIRED");
            StrictValidationService.ValidationResult validationResult = strictValidationService.validateFreshTick(entryTick);
            if (!validationResult.allowExecution()) {
                logReject(state, "MARKET_CLOSED_EXECUTION_BLOCK");
                return;
            }
            entryTick = validationResult.tick();
        } catch (Exception e) {
            logReject(state, e.getMessage());
            return;
        }

        double entrySlippage = Math.abs(entryTick.price() - signal.getEntry());
        long entryLatencyMs = entryTick.sourceAgeMs();

        try {
            strictValidationService.validateRegime(state.regime().name());
            strictValidationService.validateTimePhase(latest.timestamp().toLocalTime());
            strictValidationService.validateEntryExecution(signal.getEntry(), entryTick.price(), entryLatencyMs);
        } catch (Exception e) {
            logReject(state, e.getMessage());
            return;
        }

        double orRange = currentOrRange(state);
        GateDecision decision = riskGateEngine.evaluateEntry(state, orRange, entrySlippage, entryLatencyMs);
        riskGateEngine.logDecision(state, orRange, entryLatencyMs, entrySlippage, decision);
        if (!decision.allowed()) {
            logReject(state, decision.reason());
            return;
        }

        openTrade(signal, state, latest.timestamp(), entryTick);
        lastTriggeredCandleTime = candleId;
    }

    private void updateSessionStateFromTime(LocalTime now) {
        LocalTime sessionStart = LocalTime.parse(config.getSession().getStart());
        LocalTime sessionEnd = LocalTime.parse(config.getSession().getEnd());
        LocalTime openingRangeEnd = LocalTime.parse(config.getSession().getOpeningRangeEnd());
        List<Candle> history = candleAggregator.getValidHistory();
        RegimeFilter.RegimeMetrics regimeMetrics = regimeFilter.getCurrentRegime();

        stateManager.update(current -> {
            double orHigh = current.orHigh();
            double orLow = current.orLow();

            if (!history.isEmpty() && !now.isAfter(openingRangeEnd)) {
                Candle last = history.get(history.size() - 1);
                orHigh = isValidNumber(orHigh) ? Math.max(orHigh, last.high) : last.high;
                orLow = isValidNumber(orLow) ? Math.min(orLow, last.low) : last.low;
            }

            double orRange = isValidOr(orHigh, orLow) ? (orHigh - orLow) : 0.0;
            TradingSessionSnapshot candidateSnapshot = new TradingSessionSnapshot(
                !now.isBefore(sessionStart) && now.isBefore(sessionEnd),
                current.regime(),
                false,
                resolvePhase(now),
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
            RegimeConfidenceEngine.RegimeScore confidenceScore = evaluateRegimeConfidence(candidateSnapshot, history);
            lastRegimeConfidenceScore = confidenceScore;

            boolean tradingAllowed = confidenceScore != null
                ? confidenceScore.isTradingAllowed()
                : regimeMetrics != null
                    ? regimeMetrics.isTradingAllowed()
                    : orRange >= config.getFilters().getMinOrRange();
            Regime regime = tradingAllowed ? Regime.TREND : Regime.BLOCKED;

            return new TradingSessionSnapshot(
                !now.isBefore(sessionStart) && now.isBefore(sessionEnd),
                regime,
                tradingAllowed,
                resolvePhase(now),
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

        broadcastCurrentSessionState();
    }

    private TimePhase resolvePhase(LocalTime now) {
        if (now.isBefore(LocalTime.parse(config.getTimePhase().getMid().getStart()))) {
            return TimePhase.EARLY;
        }
        if (now.isBefore(LocalTime.parse(config.getTimePhase().getLate().getStart()))) {
            return TimePhase.MID;
        }
        return TimePhase.LATE;
    }

    private void openTrade(Signal signal, TradingSessionSnapshot state, LocalDateTime signalTime, MarketTick entryTick) {
        LocalDateTime executionTime = toLocalDateTime(entryTick.receivedAt());
        double actualEntryPrice = entryTick.price();
        double tp1Distance = Math.max(1.0, volatilityNormalizer.getCurrentTP1());
        double tp1Level = "SHORT".equalsIgnoreCase(signal.getDirection())
            ? actualEntryPrice - tp1Distance
            : actualEntryPrice + tp1Distance;
        double positionScale = switch (state.timePhase()) {
            case MID -> config.getTimePhase().getMid().getPositionScale();
            case LATE -> 0.0;
            default -> config.getTimePhase().getEarly().getPositionScale();
        };
        double initialRisk = Math.max(1.0, Math.abs(signal.getStopLoss() - actualEntryPrice));

        ActiveTradeExecution trade = new ActiveTradeExecution(
            normalizeDirection(signal.getDirection()),
            actualEntryPrice,
            signal.getStopLoss(),
            tp1Level,
            initialRisk,
            false,
            false,
            false,
            false,
            positionScale,
            positionScale,
            Math.max(0, signal.getQuantity()),
            Math.max(0, signal.getQuantity()),
            0.0,
            0.0,
            0.0,
            0.0,
            signal.getStopLoss()
        );

        activeSignalTime = signalTime;
        activeExecutionTime = executionTime;
        activeExpectedEntry = signal.getEntry();
        activeEntryLatencyMs = entryTick.sourceAgeMs();
        persistOpenedTrade(signal, trade, executionTime, entryTick);

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

        ntfyNotificationService.notifyTradeEntry(signal, trade);
        broadcastCurrentSessionState();
    }

    private void closeTrade(ActiveTradeExecution trade, TradeExit exit, MarketTick exitTick) {
        TradingSessionSnapshot state = stateManager.getSnapshot();
        double riskPoints = Math.max(1.0, trade.initialRisk());
        double realizedR = exit.pnlPoints() / riskPoints;
        double finalRealizedPnL = trade.realizedPnL() + exit.pnlPoints();
        double expectedExit = trade.tp1Hit() ? trade.trailingSL() : trade.stopLoss();
        boolean recovery = activeSignalTime == null || activeExecutionTime == null;
        String effectiveExitType = recovery ? "RECOVERED" : exit.exitType();
        double entrySlip = calculateEntrySlippage(trade.direction(), activeExpectedEntry, trade.entryPrice());
        double exitSlip = calculateExitSlippage(trade.direction(), expectedExit, exit.exitPrice());
        long exitLatencyMs = exitTick != null ? exitTick.sourceAgeMs() : config.getInfra().getHeartbeat().getMaxSilenceMs();

        try {
            strictValidationService.validateExitExecution(
                trade.tp1Hit() ? "RUNNER" : "PANIC_EXIT",
                expectedExit,
                exit.exitPrice(),
                exitLatencyMs
            );
        } catch (Exception e) {
            log.warn("Exit slippage validation triggered: {}", e.getMessage());
        }

        LocalDateTime effectiveSignalTime = activeSignalTime != null ? activeSignalTime : LocalDateTime.now();
        LocalDateTime effectiveExecutionTime = activeExecutionTime != null ? activeExecutionTime : effectiveSignalTime;
        if (recovery) {
            log.warn(
                "STATE_RECOVERY_MODE activeSignalTime={} activeExecutionTime={} exitReason={}",
                activeSignalTime,
                activeExecutionTime,
                exit.reason()
            );
        }

        TradeLog executionLog = liveMetricsLogger.logShadowExecution(
            effectiveSignalTime,
            effectiveExecutionTime,
            trade.direction(),
            activeEntryLatencyMs,
            exitLatencyMs,
            activeExpectedEntry,
            trade.entryPrice(),
            expectedExit,
            exit.exitPrice(),
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.mfe(),
            trade.mae(),
            realizedR,
            trade.quantity(),
            trade.remainingQuantity(),
            "ALLOW",
            "",
            state.regime(),
            state.timePhase(),
            state.feedStable(),
            exit.reason(),
            effectiveExitType,
            recovery,
            LocalDateTime.now()
        );

        broadcastTradeData(TradeView.fromTradeLog(executionLog));

        edgeTracker.addTradeResult(realizedR, trade.tp1Hit(), trade.runnerActive(), entrySlip, exitSlip);
        RegimeFilter.RegimeMetrics regimeMetrics = regimeFilter.getCurrentRegime();
        AdaptiveRegimeEngine.SessionFeatures features = new AdaptiveRegimeEngine.SessionFeatures(
            currentOrRange(state),
            regimeMetrics != null ? regimeMetrics.getAtrRatio() : 1.0,
            regimeMetrics != null ? regimeMetrics.getTrendEfficiency() : 0.5,
            regimeMetrics != null ? regimeMetrics.getBreakoutHoldRate() : 0.5,
            regimeMetrics != null ? regimeMetrics.getRegimeScore() : 3
        );
        adaptiveRegimeEngine.addTradeResult(
            realizedR,
            trade.tp1Hit(),
            trade.runnerActive(),
            entrySlip,
            exitSlip,
            features
        );
        strictValidationService.recordTradeExecution(realizedR);
        ntfyNotificationService.notifyTradeExit(trade, exit, realizedR);
        finalizePersistedTrade(trade, exit, finalRealizedPnL, expectedExit, exitLatencyMs, exitSlip, effectiveExitType);

        stateManager.update(current -> new TradingSessionSnapshot(
            current.sessionActive(),
            current.regime(),
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
            exit.reason()
        ));

        activeSignalTime = null;
        activeExecutionTime = null;
        activeExpectedEntry = 0.0;
        activeEntryLatencyMs = 0L;
        activeTradeId = null;
        broadcastCurrentSessionState();
    }

    private void updateActiveTradeState(ActiveTradeExecution trade, String lastRejectReason, double currentPrice) {
        syncPersistedActiveTrade(trade, currentPrice);
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
            lastRejectReason
        ));

        broadcastCurrentSessionState();
    }

    private void logReject(TradingSessionSnapshot state, String reason) {
        rejectedSignalCount.incrementAndGet();
        rejectReasonCounts.computeIfAbsent(reason == null ? "UNKNOWN" : reason, ignored -> new AtomicInteger()).incrementAndGet();
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

        liveMetricsLogger.logReject(
            LocalDateTime.now(),
            reason,
            state.regime(),
            state.timePhase(),
            state.feedStable()
        );

        broadcastCurrentSessionState();
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
        payload.put("lastRejectReason", state.lastRejectReason());
        payload.put("orHigh", isValidNumber(state.orHigh()) ? state.orHigh() : null);
        payload.put("orLow", isValidNumber(state.orLow()) ? state.orLow() : null);
        MarketDataStateService.MarketDataSnapshot marketDataSnapshot = marketDataStateService.snapshot();
        boolean marketOpen = marketSessionService.isMarketOpen();
        String priceSource = marketDataStateService.resolvePriceSource(marketOpen, config.getInfra().getHeartbeat().getMaxSilenceMs());
        payload.put("transport", marketDataSnapshot.transport() != null ? marketDataSnapshot.transport().name() : null);
        payload.put("marketStatus", marketOpen ? "OPEN" : "CLOSED");
        payload.put("priceSource", priceSource);
        payload.put("sessionActive", marketOpen && state.sessionActive());
        payload.put("lastPrice", marketDataSnapshot.lastTick() != null ? marketDataSnapshot.lastTick().price() : null);
        payload.put("sourceAgeMs", marketDataSnapshot.lastTick() != null ? marketDataSnapshot.lastTick().sourceAgeMs() : null);
        payload.put("feedBlocked", marketDataSnapshot.feedBlocked());
        payload.put("feedBlockReason", marketDataSnapshot.blockReason());
        payload.put("rejectReasonCounts", getTopRejectReasons());
        payload.put("operationalStatus", isOperationallyBlocked() ? "OPERATIONALLY_BLOCKED" : "ACTIVE");
        RegimeFilter.RegimeMetrics regimeMetrics = regimeFilter.getCurrentRegime();
        payload.put("regimeFilterScore", regimeMetrics != null ? regimeMetrics.getRegimeScore() : null);
        payload.put("regimeConfidenceScore", lastRegimeConfidenceScore != null ? lastRegimeConfidenceScore.getTotalScore() : null);
        payload.put("regimeConfidenceReason", lastRegimeConfidenceScore != null ? lastRegimeConfidenceScore.getReason() : null);
        payload.put("reducedMode", lastRegimeConfidenceScore != null && lastRegimeConfidenceScore.isReducedMode());
        broadcastExecutor.execute(() -> webSocketService.sendSessionState(payload));
    }

    private void broadcastTradeData(TradeView tradeView) {
        broadcastExecutor.execute(() -> webSocketService.sendTradeExecution(tradeView.toMap()));
    }

    private TradeExit exitAtPrice(ActiveTradeExecution trade, double price, String reason, String exitType) {
        double points = trade.tp1Level() < trade.entryPrice()
            ? trade.entryPrice() - price
            : price - trade.entryPrice();
        double size = trade.tp1Hit() ? trade.remainingSize() : trade.positionSize();
        return new TradeExit(true, points * size, reason, price, exitType);
    }

    private double currentOrRange(TradingSessionSnapshot state) {
        return isValidOr(state.orHigh(), state.orLow()) ? state.orHigh() - state.orLow() : 0.0;
    }

    private double computeSimpleAtr(List<Candle> history, int lookback) {
        if (history.isEmpty()) {
            return 0.0;
        }

        int start = Math.max(0, history.size() - lookback);
        return history.subList(start, history.size()).stream()
            .mapToDouble(c -> c.high - c.low)
            .average()
            .orElse(0.0);
    }

    private RegimeConfidenceEngine.RegimeScore evaluateRegimeConfidence(TradingSessionSnapshot snapshot, List<Candle> history) {
        if (history.size() < 6) {
            return null;
        }

        List<RegimeConfidenceEngine.CandleData> confidenceCandles = history.stream()
            .map(c -> new RegimeConfidenceEngine.CandleData(c.open, c.high, c.low, c.close, c.timestamp()))
            .toList();
        return regimeConfidenceEngine.evaluate(snapshot, confidenceCandles);
    }

    private boolean isValidOr(double orHigh, double orLow) {
        return isValidNumber(orHigh) && isValidNumber(orLow) && orHigh >= orLow;
    }

    private boolean isValidNumber(double value) {
        return !Double.isInfinite(value) && !Double.isNaN(value);
    }

    private void persistOpenedTrade(Signal signal, ActiveTradeExecution trade, LocalDateTime entryTime, MarketTick entryTick) {
        try {
            cancelPersistedActiveTrade("STALE_RECOVERY");
            Trade persistedTrade = Trade.builder()
                .symbol(resolveSignalSymbol(signal))
                .direction(trade.direction())
                .entryPrice(decimal(trade.entryPrice()))
                .expectedEntryPrice(decimal(signal.getEntry()))
                .stopLoss(decimal(trade.stopLoss()))
                .targetPrice(decimal(trade.tp1Level()))
                .expectedExitPrice(decimal(trade.tp1Level()))
                .positionSize(BigDecimal.valueOf(Math.max(0, trade.quantity())))
                .remainingSize(BigDecimal.valueOf(Math.max(0, trade.remainingQuantity())))
                .realizedPnL(decimal(trade.realizedPnL()))
                .unrealizedPnL(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .maxFavorableExcursion(decimal(trade.mfe()))
                .maxAdverseExcursion(decimal(trade.mae()))
                .tp1Hit(trade.tp1Hit())
                .runnerActive(trade.runnerActive())
                .tailHalfLocked(trade.tailHalfLocked())
                .trailingStopLoss(decimal(trade.trailingSL()))
                .entryLatencyMs(entryTick.sourceAgeMs())
                .entrySlippage(decimal(calculateEntrySlippage(trade.direction(), signal.getEntry(), trade.entryPrice())))
                .status("ACTIVE")
                .exitReason("OPEN")
                .exitType("REAL")
                .entryTime(entryTime)
                .build();
            persistedTrade = tradeRepository.save(persistedTrade);
            activeTradeId = persistedTrade.getId();
        } catch (Exception e) {
            log.warn("Unable to persist opened shadow trade: {}", e.getMessage());
        }
    }

    private void syncPersistedActiveTrade(ActiveTradeExecution trade, double currentPrice) {
        try {
            Optional<Trade> persistedTrade = findPersistedActiveTrade();
            if (persistedTrade.isEmpty()) {
                return;
            }

            Trade entity = persistedTrade.get();
            entity.setStopLoss(decimal(trade.stopLoss()));
            entity.setTargetPrice(decimal(trade.tp1Level()));
            entity.setExpectedExitPrice(decimal(trade.tp1Level()));
            entity.setRemainingSize(BigDecimal.valueOf(Math.max(0, trade.remainingQuantity())));
            entity.setRealizedPnL(decimal(trade.realizedPnL()));
            entity.setUnrealizedPnL(decimal(calculateUnrealizedPnL(trade, currentPrice)));
            entity.setMaxFavorableExcursion(decimal(trade.mfe()));
            entity.setMaxAdverseExcursion(decimal(trade.mae()));
            entity.setTp1Hit(trade.tp1Hit());
            entity.setRunnerActive(trade.runnerActive());
            entity.setTailHalfLocked(trade.tailHalfLocked());
            entity.setTrailingStopLoss(decimal(trade.trailingSL()));
            entity.setStatus("ACTIVE");
            entity.setExitReason("OPEN");
            entity.setExitType("REAL");
            tradeRepository.save(entity);
            activeTradeId = entity.getId();
        } catch (Exception e) {
            log.warn("Unable to sync active shadow trade: {}", e.getMessage());
        }
    }

    private void finalizePersistedTrade(
        ActiveTradeExecution trade,
        TradeExit exit,
        double finalRealizedPnL,
        double expectedExit,
        long exitLatencyMs,
        double exitSlippage,
        String effectiveExitType
    ) {
        try {
            Optional<Trade> persistedTrade = findPersistedActiveTrade();
            if (persistedTrade.isEmpty()) {
                return;
            }

            Trade entity = persistedTrade.get();
            entity.setStopLoss(decimal(trade.stopLoss()));
            entity.setTargetPrice(decimal(trade.tp1Level()));
            entity.setExpectedExitPrice(decimal(expectedExit));
            entity.setActualExitPrice(decimal(exit.exitPrice()));
            entity.setExitLatencyMs(exitLatencyMs);
            entity.setExitSlippage(decimal(exitSlippage));
            entity.setRemainingSize(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            entity.setRealizedPnL(decimal(finalRealizedPnL));
            entity.setUnrealizedPnL(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            entity.setMaxFavorableExcursion(decimal(trade.mfe()));
            entity.setMaxAdverseExcursion(decimal(trade.mae()));
            entity.setTp1Hit(trade.tp1Hit());
            entity.setRunnerActive(trade.runnerActive());
            entity.setTailHalfLocked(trade.tailHalfLocked());
            entity.setTrailingStopLoss(decimal(trade.trailingSL()));
            entity.setStatus("CLOSED");
            entity.setExitReason(exit.reason());
            entity.setExitType(effectiveExitType);
            entity.setExitTime(LocalDateTime.now());
            tradeRepository.save(entity);
        } catch (Exception e) {
            log.warn("Unable to finalize shadow trade record: {}", e.getMessage());
        }
    }

    private void cancelPersistedActiveTrade(String reason) {
        try {
            Optional<Trade> persistedTrade = findPersistedActiveTrade();
            if (persistedTrade.isEmpty()) {
                return;
            }

            Trade entity = persistedTrade.get();
            entity.setStatus("CANCELLED");
            entity.setExitReason(reason);
            entity.setExitType("ESTIMATED");
            entity.setExitTime(LocalDateTime.now());
            entity.setUnrealizedPnL(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            entity.setRemainingSize(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            tradeRepository.save(entity);
        } catch (Exception e) {
            log.warn("Unable to cancel stale active shadow trade: {}", e.getMessage());
        }
    }

    private Optional<Trade> findPersistedActiveTrade() {
        if (activeTradeId != null) {
            Optional<Trade> byId = tradeRepository.findById(activeTradeId);
            if (byId.isPresent()) {
                return byId;
            }
        }
        return tradeRepository.findFirstBySymbolAndStatusOrderByEntryTimeDesc(DEFAULT_SYMBOL, "ACTIVE");
    }

    private String resolveSignalSymbol(Signal signal) {
        if (signal.getSymbol() == null || signal.getSymbol().isBlank()) {
            return DEFAULT_SYMBOL;
        }
        return signal.getSymbol().trim();
    }

    private String resolveTradeDirection(ActiveTradeExecution trade) {
        return trade.direction();
    }

    private double calculateUnrealizedPnL(ActiveTradeExecution trade, double currentPrice) {
        double pnlPoints = trade.tp1Level() < trade.entryPrice()
            ? trade.entryPrice() - currentPrice
            : currentPrice - trade.entryPrice();
        return pnlPoints * trade.remainingSize();
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private MarketTick requireLiveTick(String reason) {
        MarketTick tick = marketDataStateService.lastAcceptedTick()
            .orElseThrow(() -> new IllegalStateException(reason));
        if (tick.afterHours()) {
            throw new StaleFeedException("FEED_STALE_EXIT");
        }
        long silenceMs = marketDataStateService.silenceMs(Instant.now());
        if (silenceMs > config.getInfra().getHeartbeat().getMaxSilenceMs()) {
            throw new StaleFeedException("FEED_STALE_EXIT");
        }
        return tick;
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return marketSessionService.toMarketTime(instant).toLocalDateTime();
    }

    public Map<String, Integer> getTopRejectReasons() {
        return rejectReasonCounts.entrySet().stream()
            .sorted((left, right) -> Integer.compare(right.getValue().get(), left.getValue().get()))
            .limit(5)
            .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue().get()), LinkedHashMap::putAll);
    }

    public boolean isOperationallyBlocked() {
        long opportunities = signalOpportunityCount.get();
        if (opportunities == 0L) {
            return false;
        }
        return rejectedSignalCount.get() * 100L > opportunities * 90L;
    }

    private String normalizeDirection(String direction) {
        return "SHORT".equalsIgnoreCase(direction) ? "SHORT" : "LONG";
    }

    private double calculateEntrySlippage(String direction, double expectedEntry, double actualEntry) {
        if ("SHORT".equalsIgnoreCase(direction)) {
            return expectedEntry - actualEntry;
        }
        return actualEntry - expectedEntry;
    }

    private double calculateExitSlippage(String direction, double expectedExit, double actualExit) {
        if ("SHORT".equalsIgnoreCase(direction)) {
            return actualExit - expectedExit;
        }
        return expectedExit - actualExit;
    }
}
