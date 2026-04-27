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
import com.riskpilot.event.CandleClosedEvent;
import com.riskpilot.model.ActiveTradeExecution;
import com.riskpilot.model.Candle;
import com.riskpilot.model.GateDecision;
import com.riskpilot.model.Regime;
import com.riskpilot.model.Signal;
import com.riskpilot.model.TimePhase;
import com.riskpilot.model.Trade;
import com.riskpilot.model.TradeExit;
import com.riskpilot.model.TradingSessionSnapshot;
import com.riskpilot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowExecutionEngine {
    private static final String DEFAULT_SYMBOL = "NIFTY";

    private final RiskPilotProperties config;
    private final RiskGateEngine riskGateEngine;
    private final KillSwitchEngine killSwitchEngine;
    private final SessionStateManager stateManager;
    private final CandleAggregator candleAggregator;
    private final TrapEngine trapEngine;
    private final VixService vixService;
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

    private String lastTriggeredCandleTime = "";
    private LocalDateTime activeSignalTime;
    private double activeExpectedEntry;
    private Long activeTradeId;
    private volatile RegimeConfidenceEngine.RegimeScore lastRegimeConfidenceScore;

    public synchronized void evaluateTick(double currentPrice) {
        if (killSwitchEngine.isKillSwitchTriggered()) {
            log.warn("Kill switch active, ignoring tick");
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

        updateActiveTradeState(trade, state.lastRejectReason(), currentPrice);
    }

    @EventListener
    public synchronized void onCandleClosed(CandleClosedEvent event) {
        evaluateCandle(event.candle());
    }

    public synchronized void evaluateCandle(Candle candle) {
        volatilityNormalizer.updateOpeningRange(candle.high, candle.low, candle.timestamp());

        List<Candle> history = candleAggregator.getValidHistory();
        double atr = computeSimpleAtr(history, 5);
        regimeFilter.processCandle(
            candle.open,
            candle.high,
            candle.low,
            candle.close,
            candle.volume(),
            candle.timestamp(),
            atr
        );

        updateSessionStateFromTime(candle.timestamp().toLocalTime());
        TradingSessionSnapshot state = stateManager.getSnapshot();

        if (state.tradeActive() && state.activeTradeReference() != null) {
            ActiveTradeExecution trade = ActiveTradeExecution.fromCandleClose(state.activeTradeReference(), candle);
            if (riskGateEngine.shouldForceLateSessionExit(state)) {
                closeTrade(trade, exitAtPrice(trade, candle.close, "TIME_CUTOFF_EXIT"));
                return;
            }
            updateActiveTradeState(trade, state.lastRejectReason(), candle.close);
            return;
        }

        maybeOpenTrade(state, history);
    }

    public synchronized void evaluateCandleClose() {
        List<Candle> history = candleAggregator.getValidHistory();
        if (history.isEmpty()) {
            return;
        }
        evaluateCandle(history.get(history.size() - 1));
    }

    public synchronized void restart() {
        cancelPersistedActiveTrade("ENGINE_RESTART");
        stateManager.resetDaily();
        candleAggregator.clearHistory();
        volatilityNormalizer.reset();
        regimeFilter.reset();
        edgeTracker.reset();
        lastTriggeredCandleTime = "";
        activeSignalTime = null;
        activeExpectedEntry = 0.0;
        activeTradeId = null;
        lastRegimeConfidenceScore = null;
        broadcastCurrentSessionState();
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

        if (!strictValidationService.canExecuteNewTrade()) {
            logReject(state, "STRICT_LIMIT_BLOCK");
            return;
        }

        int size = history.size();
        List<Candle> priorCandles = history.subList(Math.max(0, size - 8), size - 2);
        double localResistance = priorCandles.stream().mapToDouble(c -> c.high).max().orElse(latest.high);
        double localSupport = priorCandles.stream().mapToDouble(c -> c.low).min().orElse(latest.low);

        Signal signal = trapEngine.detectTrap(history, localSupport, localResistance, vixService.getIndiaVix());
        if (signal == null) {
            return;
        }

        try {
            strictValidationService.validateRegime(state.regime().name());
            strictValidationService.validateTimePhase(latest.timestamp().toLocalTime());
            strictValidationService.validateLatency(0L);
            strictValidationService.validateSlippage("ENTRY", 0.0);
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

        openTrade(signal, state);
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

    private void openTrade(Signal signal, TradingSessionSnapshot state) {
        LocalDateTime now = LocalDateTime.now();
        double tp1Distance = Math.max(1.0, volatilityNormalizer.getCurrentTP1());
        double tp1Level = "SHORT".equalsIgnoreCase(signal.getDirection())
            ? signal.getEntry() - tp1Distance
            : signal.getEntry() + tp1Distance;
        double positionScale = switch (state.timePhase()) {
            case MID -> config.getTimePhase().getMid().getPositionScale();
            case LATE -> 0.0;
            default -> config.getTimePhase().getEarly().getPositionScale();
        };
        double initialRisk = Math.max(1.0, Math.abs(signal.getStopLoss() - signal.getEntry()));

        ActiveTradeExecution trade = new ActiveTradeExecution(
            signal.getEntry(),
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

        activeSignalTime = now;
        activeExpectedEntry = signal.getEntry();
        persistOpenedTrade(signal, trade, now);

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

    private void closeTrade(ActiveTradeExecution trade, TradeExit exit) {
        TradingSessionSnapshot state = stateManager.getSnapshot();
        double riskPoints = Math.max(1.0, trade.initialRisk());
        double realizedR = exit.pnlPoints() / riskPoints;
        double finalRealizedPnL = trade.realizedPnL() + exit.pnlPoints();
        double expectedExit = trade.tp1Hit() ? trade.trailingSL() : trade.stopLoss();
        double entrySlip = activeSignalTime != null ? Math.abs(trade.entryPrice() - activeExpectedEntry) : 0.0;
        double exitSlip = Math.abs(exit.exitPrice() - expectedExit);

        try {
            strictValidationService.validateSlippage(trade.tp1Hit() ? "RUNNER" : "PANIC_EXIT", exitSlip);
        } catch (Exception e) {
            log.warn("Exit slippage validation triggered: {}", e.getMessage());
        }

        if (activeSignalTime != null) {
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

            broadcastTradeData(activeSignalTime, trade, exit, realizedR);
        }

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
        finalizePersistedTrade(trade, exit, finalRealizedPnL);

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
        activeExpectedEntry = 0.0;
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
        RegimeFilter.RegimeMetrics regimeMetrics = regimeFilter.getCurrentRegime();
        payload.put("regimeFilterScore", regimeMetrics != null ? regimeMetrics.getRegimeScore() : null);
        payload.put("regimeConfidenceScore", lastRegimeConfidenceScore != null ? lastRegimeConfidenceScore.getTotalScore() : null);
        payload.put("regimeConfidenceReason", lastRegimeConfidenceScore != null ? lastRegimeConfidenceScore.getReason() : null);
        payload.put("reducedMode", lastRegimeConfidenceScore != null && lastRegimeConfidenceScore.isReducedMode());
        webSocketService.sendSessionState(payload);
        PlainWebSocketConfig.TradeDataWebSocketHandler.broadcastSessionState(payload);
    }

    private void broadcastTradeData(LocalDateTime signalTime, ActiveTradeExecution trade, TradeExit exit, double realizedR) {
        Map<String, Object> tradeData = new LinkedHashMap<>();
        tradeData.put("id", signalTime + "_" + trade.entryPrice());
        tradeData.put("signalTime", signalTime.toString());
        tradeData.put("direction", trade.tp1Level() < trade.entryPrice() ? "SHORT" : "LONG");
        tradeData.put("expectedEntry", activeExpectedEntry);
        tradeData.put("actualEntry", trade.entryPrice());
        tradeData.put("quantity", trade.quantity());
        tradeData.put("remainingQuantity", trade.remainingQuantity());
        tradeData.put("latencySec", Duration.between(signalTime, LocalDateTime.now()).toMillis() / 1000.0);
        tradeData.put("slippage", trade.entryPrice() - activeExpectedEntry);
        tradeData.put("mfe", trade.mfe());
        tradeData.put("mae", trade.mae());
        tradeData.put("tp1Hit", trade.tp1Hit());
        tradeData.put("runnerCaptured", trade.runnerActive());
        tradeData.put("realizedR", realizedR);
        tradeData.put("exitReason", exit.reason());
        tradeData.put("exitTime", LocalDateTime.now().toString());

        PlainWebSocketConfig.TradeDataWebSocketHandler.broadcastTradeData(tradeData);
        webSocketService.sendTradeExecution(tradeData);
    }

    private TradeExit exitAtPrice(ActiveTradeExecution trade, double price, String reason) {
        double points = trade.tp1Level() < trade.entryPrice()
            ? trade.entryPrice() - price
            : price - trade.entryPrice();
        double size = trade.tp1Hit() ? trade.remainingSize() : trade.positionSize();
        return new TradeExit(true, points * size, reason, price);
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

    private void persistOpenedTrade(Signal signal, ActiveTradeExecution trade, LocalDateTime entryTime) {
        try {
            cancelPersistedActiveTrade("STALE_RECOVERY");
            Trade persistedTrade = Trade.builder()
                .symbol(resolveSignalSymbol(signal))
                .direction(resolveTradeDirection(trade))
                .entryPrice(decimal(trade.entryPrice()))
                .stopLoss(decimal(trade.stopLoss()))
                .targetPrice(decimal(trade.tp1Level()))
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
                .status("ACTIVE")
                .exitReason("OPEN")
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
            tradeRepository.save(entity);
            activeTradeId = entity.getId();
        } catch (Exception e) {
            log.warn("Unable to sync active shadow trade: {}", e.getMessage());
        }
    }

    private void finalizePersistedTrade(ActiveTradeExecution trade, TradeExit exit, double finalRealizedPnL) {
        try {
            Optional<Trade> persistedTrade = findPersistedActiveTrade();
            if (persistedTrade.isEmpty()) {
                return;
            }

            Trade entity = persistedTrade.get();
            entity.setStopLoss(decimal(trade.stopLoss()));
            entity.setTargetPrice(decimal(trade.tp1Level()));
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
        return trade.tp1Level() < trade.entryPrice() ? "SHORT" : "LONG";
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
}
