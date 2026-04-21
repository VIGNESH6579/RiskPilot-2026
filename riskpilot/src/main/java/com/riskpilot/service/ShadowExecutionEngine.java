package com.riskpilot.service;

import com.riskpilot.model.ActiveTrade;
import com.riskpilot.model.Candle;
import com.riskpilot.model.Regime;
import com.riskpilot.model.Signal;
import com.riskpilot.model.TimePhase;
import com.riskpilot.model.TradeExit;
import com.riskpilot.model.TradingSessionSnapshot;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ShadowExecutionEngine {

    private final SessionStateManager stateManager;
    private final CandleAggregator candleAggregator;
    private final TrapEngine trapEngine;
    private final RiskGateEngine riskGateEngine;
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
        LiveMetricsLogger liveMetricsLogger,
        WebSocketService webSocketService
    ) {
        this.stateManager = stateManager;
        this.candleAggregator = candleAggregator;
        this.trapEngine = trapEngine;
        this.riskGateEngine = riskGateEngine;
        this.liveMetricsLogger = liveMetricsLogger;
        this.webSocketService = webSocketService;
    }

    public synchronized void evaluateTick(double currentPrice) {
        TradingSessionSnapshot state = stateManager.getSnapshot();

        if (state.tradeActive() && state.activeTradeReference() != null) {
            ActiveTrade trade = state.activeTradeReference();
            trade = updateExcursions(trade, currentPrice);
            trade = handleTickTP1(trade, currentPrice);
            TradeExit exit = checkStopLoss(trade, currentPrice);

            if (exit.triggered()) {
                closeTrade(trade, exit);
                return;
            }
            updateActiveTradeState(trade, state.lastRejectReason());
            broadcastCurrentSessionState();
        }
    }

    public synchronized void evaluateCandleClose() {
        List<Candle> strictHistory = candleAggregator.getValidHistory();
        if (strictHistory.size() < 10) {
            return;
        }

        Candle newestCandle = strictHistory.get(strictHistory.size() - 1);
        updateSessionStateFromTime(LocalTime.parse(newestCandle.time));

        if (dayBlockedByFirstTradeFailure) {
            logReject(stateManager.getSnapshot(), "FIRST_TRADE_FAILURE_DAY_BLOCK");
            return;
        }

        TradingSessionSnapshot state = stateManager.getSnapshot();
        if (state.tradeActive() && state.activeTradeReference() != null) {
            ActiveTrade updated = handleCandleClose(state.activeTradeReference(), newestCandle);
            updateActiveTradeState(updated, state.lastRejectReason());
            broadcastCurrentSessionState();
            return;
        }

        if (newestCandle.time.equals(lastTriggeredCandleTime)) {
            return;
        }

        GateDecision decision = riskGateEngine.evaluateEntry(state);
        if (!decision.allowed()) {
            logReject(state, decision.reason());
            return;
        }

        double localSupport = strictHistory.stream().skip(Math.max(0, strictHistory.size() - 6)).mapToDouble(c -> c.low).min().orElse(newestCandle.low);
        double localResistance = strictHistory.stream().skip(Math.max(0, strictHistory.size() - 6)).mapToDouble(c -> c.high).max().orElse(newestCandle.high);
        double syntheticVix = 16.0;
        Signal signal = trapEngine.detectTrap(strictHistory, localSupport, localResistance, syntheticVix);
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
        ActiveTrade trade = new ActiveTrade(
            signal.getEntry(),
            signal.getStopLoss(),
            signal.getTarget(),
            false,
            false,
            size,
            size,
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
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.positionSize(),
            trade.remainingSize(),
            trade.realizedPnL(),
            mfe,
            mae,
            trade.trailingSL()
        );
    }

    private ActiveTrade handleTickTP1(ActiveTrade trade, double currentPrice) {
        if (trade.tp1Hit()) {
            return trade;
        }

        if (currentPrice <= trade.tp1Level()) {
            double tp1Size = trade.positionSize() * 0.20;
            double remaining = trade.positionSize() - tp1Size;
            double pnl = (trade.entryPrice() - currentPrice) * tp1Size;
            return new ActiveTrade(
                trade.entryPrice(),
                trade.entryPrice(),
                trade.tp1Level(),
                true,
                true,
                trade.positionSize(),
                remaining,
                trade.realizedPnL() + pnl,
                trade.mfe(),
                trade.mae(),
                trade.entryPrice()
            );
        }

        return trade;
    }

    private ActiveTrade handleCandleClose(ActiveTrade trade, Candle candle) {
        if (!trade.runnerActive()) {
            return trade;
        }

        double newTrailingSL = candle.high + 10.0;
        double updatedSL = Math.min(trade.trailingSL(), newTrailingSL);
        return new ActiveTrade(
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.positionSize(),
            trade.remainingSize(),
            trade.realizedPnL(),
            trade.mfe(),
            trade.mae(),
            updatedSL
        );
    }

    private TradeExit checkStopLoss(ActiveTrade trade, double currentPrice) {
        double effectiveSL = trade.tp1Hit() ? trade.trailingSL() : trade.stopLoss();
        if (currentPrice >= effectiveSL) {
            double exitSize = trade.tp1Hit() ? trade.remainingSize() : trade.positionSize();
            double pnl = trade.realizedPnL() + ((trade.entryPrice() - currentPrice) * exitSize);
            String reason = trade.tp1Hit() ? "RUNNER_TRAIL_EXIT" : "STRUCTURAL_SL";
            return new TradeExit(true, pnl, reason, currentPrice);
        }
        return TradeExit.noExit();
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
