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
import jakarta.annotation.PostConstruct;
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
import java.time.LocalDate;
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
    private final PositionSizer positionSizer;
    private final ExecutionSimulator executionSimulator;
    private final RiskEngine riskEngine;
    private final TradingSessionService tradingSessionService;
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
    private PendingEntry pendingEntry;
    private PendingExit pendingExit;

    @PostConstruct
    public void restoreRuntimeState() {
        restoreActiveTrade();
    }

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
                refreshRiskAndSession(null, tick.price());
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

            if (pendingEntry != null && isEntryPlanExpired(pendingEntry, tick.receivedAt())) {
                logReject(stateManager.getSnapshot(), "ENTRY_PLAN_EXPIRED");
                pendingEntry = null;
            }

            if (pendingEntry != null && !pendingEntry.executed() && !tick.receivedAt().isBefore(pendingEntry.plan().executionTime())) {
                executePlannedEntry(pendingEntry, tick);
            }

            TradingSessionSnapshot state = stateManager.getSnapshot();
            if (!state.tradeActive() || state.activeTradeReference() == null) {
                refreshRiskAndSession(null, tick.price());
                return;
            }

            ActiveTradeExecution trade = ActiveTradeExecution.updateExcursions(state.activeTradeReference(), tick.price());
            trade = ActiveTradeExecution.fromTickTP1(trade, tick.price());

            if (pendingExit != null && isExitPlanExpired(pendingExit, tick.receivedAt())) {
                PendingExit expiredExit = pendingExit;
                closeTrade(
                    expiredExit.trade(),
                    exitAtPrice(expiredExit.trade(), expiredExit.expectedExit(), "EXIT_PLAN_EXPIRED", "ESTIMATED"),
                    null,
                    null
                );
                return;
            }

            if (pendingExit != null && !tick.receivedAt().isBefore(pendingExit.plan().executionTime())) {
                executePlannedExit(pendingExit, tick);
                return;
            }

            TradeExit exit = ActiveTradeExecution.checkStopLoss(trade, tick.price());
            if (exit.triggered()) {
                scheduleExit(trade, exit.reason(), exit.exitType(), tick.receivedAt(), tick.price(), estimateVolatilityPoints(), estimateTickGapMs());
                updateActiveTradeState(trade, state.lastRejectReason(), tick.price());
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
                    scheduleExit(trade, "TIME_CUTOFF_EXIT", "REAL", exitTick.receivedAt(), exitTick.price(), candle.high - candle.low, estimateTickGapMs());
                } catch (StaleFeedException staleFeedException) {
                    closeTrade(trade, exitAtPrice(trade, candle.close, "FEED_STALE_EXIT", "ESTIMATED"), null, null);
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
        pendingEntry = null;
        pendingExit = null;
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

    @Scheduled(cron = "0 15 9 * * *", zone = "Asia/Kolkata")
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

        try {
            MarketTick entryTick = requireLiveTick("ENTRY_TICK_REQUIRED");
            StrictValidationService.ValidationResult validationResult = strictValidationService.validateFreshTick(entryTick);
            if (!validationResult.allowExecution()) {
                logReject(state, "MARKET_CLOSED_EXECUTION_BLOCK");
                return;
            }
        } catch (Exception e) {
            logReject(state, e.getMessage());
            return;
        }

        try {
            strictValidationService.validateRegime(state.regime().name());
            strictValidationService.validateTimePhase(latest.timestamp().toLocalTime());
        } catch (Exception e) {
            logReject(state, e.getMessage());
            return;
        }

        double orRange = currentOrRange(state);
        GateDecision decision = riskGateEngine.evaluateEntry(state, orRange, 0.0, 0L);
        riskGateEngine.logDecision(state, orRange, 0L, 0.0, decision);
        if (!decision.allowed()) {
            logReject(state, decision.reason());
            return;
        }

        scheduleEntry(signal, state, latest.timestamp(), estimateVolatilityPoints(), estimateTickGapMs());
        lastTriggeredCandleTime = candleId;
    }

    private void scheduleEntry(
        Signal signal,
        TradingSessionSnapshot state,
        LocalDateTime signalTime,
        double volatilityPoints,
        long tickGapMs
    ) {
        if (pendingEntry != null) {
            return;
        }
        Instant signalInstant = signalTime.atZone(marketSessionService.zoneId()).toInstant();
        ExecutionSimulator.ExecutionPlan plan = executionSimulator.planEntry(signalInstant, volatilityPoints, tickGapMs);
        pendingEntry = new PendingEntry(signal, state, signalTime, plan, false);
        log.info(
            "Planned entry signalTime={} direction={} expectedEntry={} eligibleAt={} latencyMs={}",
            signalTime,
            signal.getDirection(),
            signal.getEntry(),
            marketSessionService.toMarketTime(plan.executionTime()),
            plan.latencyMs()
        );
    }

    private void executePlannedEntry(PendingEntry plannedEntry, MarketTick tick) {
        ExecutionSimulator.SimulatedFill fill = executionSimulator.fillEntry(
            plannedEntry.signal().getDirection(),
            plannedEntry.signal().getEntry(),
            tick,
            plannedEntry.plan()
        );
        long actualEntryLatencyMs = Duration.between(plannedEntry.plan().signalTime(), fill.executionTime()).toMillis();
        double actualEntrySlippage = Math.abs(fill.actualPrice() - plannedEntry.signal().getEntry());
        try {
            strictValidationService.validateEntryExecution(plannedEntry.signal().getEntry(), fill.actualPrice(), actualEntryLatencyMs);
            TradingSessionSnapshot currentState = stateManager.getSnapshot();
            GateDecision decision = riskGateEngine.evaluateEntry(currentState, currentOrRange(currentState), actualEntrySlippage, actualEntryLatencyMs);
            riskGateEngine.logDecision(currentState, currentOrRange(currentState), actualEntryLatencyMs, actualEntrySlippage, decision);
            if (!decision.allowed()) {
                logReject(currentState, decision.reason());
                pendingEntry = null;
                return;
            }
        } catch (Exception e) {
            logReject(stateManager.getSnapshot(), e.getMessage());
            pendingEntry = null;
            return;
        }
        openTrade(plannedEntry.signal(), plannedEntry.state(), plannedEntry.signalTime(), fill);
        pendingEntry = null;
    }

    private void scheduleExit(
        ActiveTradeExecution trade,
        String reason,
        String exitType,
        Instant signalTime,
        double expectedExit,
        double volatilityPoints,
        long tickGapMs
    ) {
        if (pendingExit != null) {
            return;
        }
        ExecutionSimulator.ExecutionPlan plan = executionSimulator.planExit(signalTime, volatilityPoints, tickGapMs);
        pendingExit = new PendingExit(trade, reason, exitType, expectedExit, plan);
        log.info(
            "Planned exit reason={} expectedExit={} eligibleAt={} latencyMs={}",
            reason,
            expectedExit,
            marketSessionService.toMarketTime(plan.executionTime()),
            plan.latencyMs()
        );
    }

    private void executePlannedExit(PendingExit pendingExit, MarketTick tick) {
        ExecutionSimulator.SimulatedFill fill = executionSimulator.fillExit(
            pendingExit.trade().direction(),
            pendingExit.expectedExit(),
            tick,
            pendingExit.plan()
        );
        long actualExitLatencyMs = Duration.between(pendingExit.plan().signalTime(), fill.executionTime()).toMillis();
        try {
            strictValidationService.validateExitExecution(
                pendingExit.trade().tp1Hit() ? "RUNNER" : "PANIC_EXIT",
                pendingExit.expectedExit(),
                fill.actualPrice(),
                actualExitLatencyMs
            );
        } catch (Exception e) {
            log.warn("Exit execution validation failed, using estimated exit reason={}", e.getMessage());
            closeTrade(
                pendingExit.trade(),
                exitAtPrice(pendingExit.trade(), pendingExit.expectedExit(), "EXIT_VALIDATION_FAILED", "ESTIMATED"),
                null,
                null
            );
            return;
        }
        double pnlInr = pnlInr(pendingExit.trade(), fill.actualPrice(), pendingExit.trade().remainingQuantity());
        TradeExit exit = new TradeExit(true, pnlInr, pendingExit.reason(), fill.actualPrice(), pendingExit.exitType());
        closeTrade(pendingExit.trade(), exit, tick, fill);
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

    private void openTrade(
        Signal signal,
        TradingSessionSnapshot state,
        LocalDateTime signalTime,
        ExecutionSimulator.SimulatedFill fill
    ) {
        LocalDateTime executionTime = toLocalDateTime(fill.executionTime());
        double actualEntryPrice = fill.actualPrice();
        double tp1Distance = Math.max(1.0, volatilityNormalizer.getCurrentTP1());
        double tp1Level = "SHORT".equalsIgnoreCase(signal.getDirection())
            ? actualEntryPrice - tp1Distance
            : actualEntryPrice + tp1Distance;
        double initialRisk = Math.max(1.0, Math.abs(signal.getStopLoss() - actualEntryPrice));
        RiskEngine.EquitySnapshot equitySnapshot = riskEngine.snapshot();
        int quantityLots = positionSizer.sizePositionLots(initialRisk, equitySnapshot.currentEquity(), equitySnapshot.consecutiveLosses());
        if (quantityLots <= 0) {
            logReject(state, "INSUFFICIENT_RISK_BUDGET");
            return;
        }

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
            quantityLots,
            quantityLots,
            config.getInstrument().getLotSize(),
            config.getInstrument().getPointValue(),
            0.0,
            0.0,
            0.0,
            0.0,
            signal.getStopLoss()
        );

        activeSignalTime = signalTime;
        activeExecutionTime = executionTime;
        activeExpectedEntry = fill.expectedPrice();
        activeEntryLatencyMs = fill.latencyMs();
        pendingEntry = null;
        persistOpenedTrade(signal, trade, signalTime, executionTime, fill);

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
        refreshRiskAndSession(trade, actualEntryPrice);
        broadcastCurrentSessionState();
    }

    private void closeTrade(ActiveTradeExecution trade, TradeExit exit, MarketTick exitTick, ExecutionSimulator.SimulatedFill fill) {
        TradingSessionSnapshot state = stateManager.getSnapshot();
        double riskInr = Math.max(1.0, trade.initialRiskPoints() * trade.totalUnits() * trade.pointValue());
        double finalRealizedPnL = trade.realizedPnL() + exit.pnlInr();
        double realizedR = finalRealizedPnL / riskInr;
        double expectedExit = fill != null ? fill.expectedPrice() : (trade.tp1Hit() ? trade.trailingSL() : trade.stopLoss());
        boolean recovery = activeSignalTime == null || activeExecutionTime == null;
        String effectiveExitType = recovery ? "RECOVERED" : exit.exitType();
        double entrySlip = calculateEntrySlippage(trade.direction(), activeExpectedEntry, trade.entryPrice());
        double exitSlip = calculateExitSlippage(trade.direction(), expectedExit, exit.exitPrice());
        long exitLatencyMs = fill != null
            ? fill.latencyMs()
            : config.getInfra().getHeartbeat().getMaxSilenceMs();

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
            trade.lotSize(),
            trade.pointValue(),
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
        riskEngine.recordClosedTrade(finalRealizedPnL);
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
        pendingExit = null;
        refreshRiskAndSession(null, exit.exitPrice());
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
        // FIX: defensive canonicalisation. Reasons that flow in here include
        // raw exception messages (e.g. e.getMessage() from MarketDataException)
        // which can embed live numeric values. Without canonicalisation
        // rejectReasonCounts grows unbounded on a long-running JVM and
        // eventually OOMs the process, taking the in-memory edge tracker /
        // adaptive regime windows with it.
        String canonical = canonicalRejectReason(reason);
        rejectReasonCounts.computeIfAbsent(canonical, ignored -> new AtomicInteger()).incrementAndGet();
        // Hard ceiling on cardinality as a last line of defence in case a
        // future code path forgets to canonicalise.
        if (rejectReasonCounts.size() > 256) {
            rejectReasonCounts.entrySet().removeIf(e -> e.getValue().get() <= 1);
        }
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
        RiskEngine.EquitySnapshot equitySnapshot = riskEngine.refresh(state.activeTradeReference(), marketDataStateService.lastAcceptedTick().map(MarketTick::price).orElse(null));
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
        payload.put("currentEquity", equitySnapshot.currentEquity());
        payload.put("realizedPnlInr", equitySnapshot.realizedPnlInr());
        payload.put("unrealizedPnlInr", equitySnapshot.unrealizedPnlInr());
        payload.put("orHigh", isValidNumber(state.orHigh()) ? state.orHigh() : null);
        payload.put("orLow", isValidNumber(state.orLow()) ? state.orLow() : null);
        MarketDataStateService.MarketDataSnapshot marketDataSnapshot = marketDataStateService.snapshot();
        boolean marketOpen = marketSessionService.isMarketOpen();
        String priceSource = marketDataStateService.resolvePriceSource(marketOpen, config.getInfra().getHeartbeat().getMaxSilenceMs());
        Double liveLastPrice = "LIVE".equals(priceSource) && marketDataSnapshot.lastTick() != null
            ? marketDataSnapshot.lastTick().price()
            : null;
        payload.put("transport", marketDataSnapshot.transport() != null ? marketDataSnapshot.transport().name() : null);
        // FIX: surface "HOLIDAY" distinctly from intra-day "CLOSED" so the
        // dashboard does not look like a software-bug pause on every
        // weekend / NSE holiday.
        payload.put("marketStatus", marketSessionService.marketStatus());
        payload.put("priceSource", priceSource);
        payload.put("sessionActive", marketOpen && state.sessionActive());
        payload.put("lastPrice", liveLastPrice);
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
        tradingSessionService.updateRuntimeState(DEFAULT_SYMBOL, state, equitySnapshot);
        broadcastExecutor.execute(() -> webSocketService.sendSessionState(payload));
    }

    private void broadcastTradeData(TradeView tradeView) {
        broadcastExecutor.execute(() -> webSocketService.sendTradeExecution(tradeView.toMap()));
    }

    private TradeExit exitAtPrice(ActiveTradeExecution trade, double price, String reason, String exitType) {
        return new TradeExit(true, pnlInr(trade, price, trade.remainingQuantity()), reason, price, exitType);
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

    private void persistOpenedTrade(
        Signal signal,
        ActiveTradeExecution trade,
        LocalDateTime signalTime,
        LocalDateTime entryTime,
        ExecutionSimulator.SimulatedFill fill
    ) {
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
                .quantity(Math.max(0, trade.quantity()))
                .remainingQuantity(Math.max(0, trade.remainingQuantity()))
                .lotSize(trade.lotSize())
                .pointValue(decimal(trade.pointValue()))
                .realizedPnL(decimal(trade.realizedPnL()))
                .unrealizedPnL(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .maxFavorableExcursion(decimal(trade.mfe()))
                .maxAdverseExcursion(decimal(trade.mae()))
                .tp1Hit(trade.tp1Hit())
                .runnerActive(trade.runnerActive())
                .tailHalfLocked(trade.tailHalfLocked())
                .trailingStopLoss(decimal(trade.trailingSL()))
                .entryLatencyMs(fill.latencyMs())
                .entrySlippage(decimal(calculateEntrySlippage(trade.direction(), signal.getEntry(), trade.entryPrice())))
                .status("ACTIVE")
                .exitReason("OPEN")
                .exitType("REAL")
                .signalTime(signalTime)
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
            entity.setRemainingQuantity(Math.max(0, trade.remainingQuantity()));
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
            entity.setRemainingQuantity(0);
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
            entity.setRemainingQuantity(0);
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
        return trade.markToMarketPnl(currentPrice);
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

    private void restoreActiveTrade() {
        try {
            Optional<Trade> persisted = tradeRepository.findFirstBySymbolAndStatusOrderByEntryTimeDesc(DEFAULT_SYMBOL, "ACTIVE");
            if (persisted.isEmpty()) {
                riskEngine.refresh(null, null);
                return;
            }

            Trade trade = persisted.get();
            ActiveTradeExecution activeTrade = new ActiveTradeExecution(
                normalizeDirection(trade.getDirection()),
                trade.getEntryPrice().doubleValue(),
                trade.getStopLoss().doubleValue(),
                trade.getTargetPrice().doubleValue(),
                Math.abs(trade.getEntryPrice().doubleValue() - trade.getStopLoss().doubleValue()),
                Boolean.TRUE.equals(trade.getTp1Hit()),
                Boolean.TRUE.equals(trade.getRunnerActive()),
                false,
                Boolean.TRUE.equals(trade.getTailHalfLocked()),
                trade.getQuantity(),
                trade.getRemainingQuantity(),
                trade.getLotSize(),
                trade.getPointValue().doubleValue(),
                trade.getRealizedPnL().doubleValue(),
                trade.getMaxFavorableExcursion().doubleValue(),
                trade.getMaxAdverseExcursion().doubleValue(),
                0.0,
                trade.getTrailingStopLoss().doubleValue()
            );
            activeTradeId = trade.getId();
            activeSignalTime = trade.getSignalTime();
            activeExecutionTime = trade.getEntryTime();
            activeExpectedEntry = trade.getExpectedEntryPrice() != null ? trade.getExpectedEntryPrice().doubleValue() : trade.getEntryPrice().doubleValue();
            activeEntryLatencyMs = trade.getEntryLatencyMs() == null ? 0L : trade.getEntryLatencyMs();
            stateManager.update(current -> new TradingSessionSnapshot(
                marketSessionService.isTradingSessionActive(marketSessionService.now()),
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
                activeTrade,
                "STATE_RESTORED"
            ));
            riskEngine.refresh(activeTrade, marketDataStateService.lastAcceptedTick().map(MarketTick::price).orElse(trade.getEntryPrice().doubleValue()));
        } catch (Exception e) {
            log.warn("Unable to restore active trade state: {}", e.getMessage());
        }
    }

    private void refreshRiskAndSession(ActiveTradeExecution trade, Double currentPrice) {
        RiskEngine.EquitySnapshot equitySnapshot = riskEngine.refresh(trade, currentPrice);
        tradingSessionService.updateRuntimeState(DEFAULT_SYMBOL, stateManager.getSnapshot(), equitySnapshot);
    }

    private double estimateVolatilityPoints() {
        List<Candle> history = candleAggregator.getValidHistory();
        if (history.isEmpty()) {
            return 10.0;
        }
        Candle last = history.get(history.size() - 1);
        return Math.max(1.0, last.high - last.low);
    }

    private long estimateTickGapMs() {
        // Use the actual measured inter-arrival time from the live Angel One
        // feed. If no tick has been observed yet (very first tick), fall back
        // to the configured maxSilenceMs so the latency clamp behaves
        // conservatively without requiring any synthetic constant.
        long interArrivalMs = marketDataStateService.lastInterArrivalMs();
        if (interArrivalMs > 0L) {
            return interArrivalMs;
        }
        return Math.max(1L, config.getInfra().getHeartbeat().getMaxSilenceMs());
    }

    private double pnlInr(ActiveTradeExecution trade, double price, int lots) {
        double pnlPoints = "SHORT".equalsIgnoreCase(trade.direction())
            ? trade.entryPrice() - price
            : price - trade.entryPrice();
        return pnlPoints * trade.unitsForLots(lots) * trade.pointValue();
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

    /**
     * Map an arbitrary reason string to a stable, bounded-cardinality code.
     * Strips dynamic numeric tails (e.g. "LIVE_TICK_STALE: age=812ms max=2000ms"
     * collapses to "LIVE_TICK_STALE") so rejectReasonCounts cannot grow
     * without bound on a long-running JVM.
     */
    private static String canonicalRejectReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        String trimmed = reason.trim();
        // Strip anything after the first ':' or '(' — that is where dynamic
        // values are conventionally appended in this codebase.
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            trimmed = trimmed.substring(0, colon);
        }
        int paren = trimmed.indexOf('(');
        if (paren > 0) {
            trimmed = trimmed.substring(0, paren);
        }
        // Hard cap on length as final defence.
        if (trimmed.length() > 64) {
            trimmed = trimmed.substring(0, 64);
        }
        return trimmed.trim();
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

    private boolean isEntryPlanExpired(PendingEntry plannedEntry, Instant observedAt) {
        Instant expiry = plannedEntry.plan().executionTime().plusMillis(config.getExecution().getLatency().getHardBlockMs());
        return observedAt.isAfter(expiry);
    }

    private boolean isExitPlanExpired(PendingExit plannedExit, Instant observedAt) {
        Instant expiry = plannedExit.plan().executionTime().plusMillis(config.getExecution().getLatency().getPanicMs());
        return observedAt.isAfter(expiry);
    }

    private record PendingEntry(
        Signal signal,
        TradingSessionSnapshot state,
        LocalDateTime signalTime,
        ExecutionSimulator.ExecutionPlan plan,
        boolean executed
    ) {}

    private record PendingExit(
        ActiveTradeExecution trade,
        String reason,
        String exitType,
        double expectedExit,
        ExecutionSimulator.ExecutionPlan plan
    ) {}
}
