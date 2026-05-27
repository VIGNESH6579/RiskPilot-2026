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
import com.riskpilot.model.Trade;
import com.riskpilot.model.TradeExit;
import com.riskpilot.model.TradingSessionSnapshot;
import com.riskpilot.model.CandleEntity;
import com.riskpilot.repository.CandleRepository;
import com.riskpilot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;

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
    private final TradingSafetyManager tradingSafetyManager;
    // BUG-FIX: ShadowExecutionEngine never persisted trades to DB — history was always empty
    private final TradeRepository tradeRepository;
    // BUG-FIX: Restore today's candles from DB on startup so engine isn't blind after restart
    private final CandleRepository candleRepository;

    @org.springframework.beans.factory.annotation.Value("${TRADING_SYMBOL:NIFTY}")
    private String tradingSymbol;

    // Injected from env var PAPER_MODE=true to override config bean for convenience
    @org.springframework.beans.factory.annotation.Value("${PAPER_MODE:false}")
    private boolean paperModeOverride;

    // FIX: Allow MAX_TRADES_PER_DAY env var to override application.yml at runtime.
    // Set this in Render Dashboard → Environment without needing a redeployment.
    // 0 means "not set" — in that case, the yml-configured value is used as-is.
    @org.springframework.beans.factory.annotation.Value("${MAX_TRADES_PER_DAY:0}")
    private int maxTradesPerDayEnvOverride;

    private final List<RegimeConfidenceEngine.CandleData> candleHistory = new ArrayList<>();
    private final ConcurrentHashMap<String, AtomicLong> rejectReasonCounts = new ConcurrentHashMap<>();

    private static final int MAX_REJECT_REASONS = 256;

    private String lastTriggeredCandleTime = "";
    private String lastEvaluatedCandleTime = "";
    private String lastStoredCandleTime = "";
    private LocalDateTime activeSignalTime;
    private double activeExpectedEntry;
    private boolean dayBlockedByFirstTradeFailure;
    // FIX: Cache last live price so the 10-second scheduler broadcast
    // doesn't send spot=0 and blank the frontend price card.
    private volatile double lastKnownSpot = 0.0;

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
        // FIX: persist last known live price for use by the scheduler broadcast
        if (currentPrice > 0) lastKnownSpot = currentPrice;

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

        // FIX BUG-H: when TP1 is hit and the entire position was exited (small lots < 5),
        // remainingSize == 0.  There is no runner to trail and checkStopLoss returns noExit()
        // (guarded above).  We must close the trade here with exit.pnl() == 0 (runner pnl)
        // so that the TP1 pnl already in trade.realizedPnL() gets booked to the balance.
        if (trade.tp1Hit() && trade.remainingSize() <= 0.0) {
            TradeExit fullTp1Exit = new TradeExit(true, 0.0, "TP1_FULL_EXIT", currentPrice);
            closeTrade(trade, fullTp1Exit);
            return;
        }

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
        // BUG-FIX: Guard against re-evaluating the same candle on every tick.
        // lastEvaluatedCandleTime tracks the last candle that entered signal evaluation;
        // without this, detectTrap() fires once per second on the same closed candle.
        if (newestCandle.time.equals(lastEvaluatedCandleTime)) {
            return;
        }
        lastEvaluatedCandleTime = newestCandle.time;
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
        // Keep the per-tick dedup in sync so evaluateLatestClosedCandleSignal
        // doesn't re-fire for this candle on the very next tick.
        lastEvaluatedCandleTime = newestCandle.time;
    }

    @Scheduled(cron = "0 14 9 * * *", zone = "Asia/Kolkata")
    public void executeDailyHardReset() {
        // FIX: Preserve paper balance across sessions so P&L compounds correctly.
        // Only trading counters/state reset at 9:14 AM — balance carries over from yesterday.
        double currentBalance = stateManager.getSnapshot().paperBalance();
        restart();
        // Restore balance after reset (don't reset to initial 5L if already different)
        if (currentBalance > 0 && currentBalance != 500000.0) {
            stateManager.update(current -> new TradingSessionSnapshot(
                current.sessionActive(), current.regime(), current.volatilityQualified(),
                current.timePhase(), current.tradesTaken(), current.tradeActive(),
                current.feedStable(), current.heartbeatAlive(), current.orHigh(), current.orLow(),
                current.cumulativeDailyLossR(), current.consecutiveLosses(),
                current.activeTradeReference(), current.lastRejectReason(),
                currentBalance
            ));
        }
        log.info("\u2705 Daily hard reset complete — paper balance preserved: \u20b9{}", currentBalance);
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

        // BUG-FIX: VolatilityNormalizer must receive accumulated OR high/low (full 9:15-9:45 span),
        // not a single candle's H/L. Compute from candle history before passing in.
        if (candle.timestamp().toLocalTime().isBefore(java.time.LocalTime.of(9, 45))) {
            java.time.LocalTime orEnd = java.time.LocalTime.of(9, 45);
            List<Candle> history = candleAggregator.getValidHistory();
            double accHigh = history.stream()
                .filter(c -> c.timestamp().toLocalTime().isBefore(orEnd))
                .mapToDouble(c -> c.high)
                .max().orElse(candle.high);
            double accLow = history.stream()
                .filter(c -> c.timestamp().toLocalTime().isBefore(orEnd))
                .mapToDouble(c -> c.low)
                .min().orElse(candle.low);
            volatilityNormalizer.updateOpeningRange(accHigh, accLow, candle.timestamp());
        }
        lastStoredCandleTime = candle.time;
    }

    private void updateRegimeFilter(Candle candle) {
        // BUG-FIX: was passing (high - low) as ATR - that is just candle range, not ATR.
        // Compute a proper 14-period ATR from candle history instead.
        double atr = calculateSimpleAtr(candleAggregator.getValidHistory(), 14);
        if (atr <= 0.0) atr = Math.abs(candle.high - candle.low); // fallback for first candle
        regimeFilter.processCandle(
            candle.open, candle.high, candle.low, candle.close,
            candle.volume(), candle.timestamp(), atr
        );
    }

    private void updateSessionStateFromTime(LocalTime now) {
        TimePhase phase = now.isBefore(LocalTime.NOON)
            ? TimePhase.EARLY
            : (now.isBefore(LocalTime.of(13, 30)) ? TimePhase.MID : TimePhase.LATE);

        // Resolve regime from RegimeFilter (actual market-structure analysis)
        // Fall back to existing snapshot regime so we never regress to UNKNOWN once assessed
        RegimeFilter.RegimeMetrics regimeMetrics = regimeFilter.getCurrentRegime();
        final Regime resolvedRegime;
        if (regimeMetrics != null) {
            resolvedRegime = regimeMetrics.isTradingAllowed() ? Regime.TREND : Regime.CHOP;
        } else {
            // No candle data yet — will be set properly once ticks arrive; keep existing
            resolvedRegime = null; // sentinel: resolved inside lambda from current
        }

        stateManager.update(current -> {
            boolean sessionActive = marketSessionService.isMarketOpen();
            double orHigh = current.orHigh();
            double orLow = current.orLow();

            List<Candle> history = candleAggregator.getValidHistory();

            // Restore OR from full candle history (handles service restart mid-session).
            // If OR not yet built (values are still infinite), scan all pre-09:45 candles.
            if (!history.isEmpty()) {
                boolean orNotBuilt = !Double.isFinite(orHigh) || !Double.isFinite(orLow);
                if (orNotBuilt) {
                    // Full scan: restore from any candle whose timestamp is before 09:45
                    for (Candle c : history) {
                        if (c.timestamp().toLocalTime().isBefore(LocalTime.of(9, 45))) {
                            orHigh = Double.isFinite(orHigh) ? Math.max(orHigh, c.high) : c.high;
                            orLow  = Double.isFinite(orLow)  ? Math.min(orLow,  c.low)  : c.low;
                        }
                    }
                } else if (now.isBefore(LocalTime.of(9, 45))) {
                    // Still inside OR window: extend from latest candle
                    Candle last = history.get(history.size() - 1);
                    orHigh = Math.max(orHigh, last.high);
                    orLow  = Math.min(orLow,  last.low);
                }
            }

            // Use resolved regime; if regimeFilter has no data yet, keep whatever the snapshot
            // already has (TREND/CHOP from a prior tick), falling back to CHOP (not UNKNOWN)
            Regime regime = resolvedRegime != null
                ? resolvedRegime
                : (current.regime() != Regime.UNKNOWN ? current.regime() : Regime.CHOP);

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

    /**
     * Scheduled heartbeat that keeps session state fresh even when no Angel One ticks
     * are arriving (outside market hours or during feed gaps). Runs every 10 seconds.
     * Without this, the session stays as the initial snapshot (sessionActive=false,
     * regime=UNKNOWN) whenever the tick poller produces no data.
     */
    @Scheduled(fixedDelay = 10_000)
    public synchronized void refreshSessionStatePeriodically() {
        LocalTime now = marketSessionService.nowIst().toLocalTime();
        // Only update from this path if no trade is currently active (tick path takes priority)
        TradingSessionSnapshot current = stateManager.getSnapshot();
        if (current.tradeActive()) {
            return; // tick-level path is handling it
        }
        updateSessionStateFromTime(now);
        broadcastCurrentSessionState();
    }

    private void evaluateSignalIfEligible(Candle candle, TradingSessionSnapshot state) {
        if (!state.sessionActive() || state.tradeActive()) {
            return;
        }

        // BUG-FIX: Check safety before entry!
        if (!tradingSafetyManager.isSafeToTrade()) {
            logReject(state, "SAFETY_BLOCK_BEFORE_ENTRY");
            return;
        }

        if (state.tradesTaken() >= config.getRisk().getMaxTradesPerDay()) {
            logReject(state, "MAX_TRADES_EXCEEDED");
            return;
        }

        // FIX: Use TrapEngine to detect signals with calculated support/resistance
        List<Candle> history = candleAggregator.getValidHistory();

        // FIX: VixService.getIndiaVix() returns -1.0 when its circuit breaker fires
        // (both Angel One and Yahoo sources failed).  A value of -1.0 passed into
        // TrapEngine.detectTrap() causes the VIX range check:
        //   if (vix < minVix || vix > maxVix)   →   -1.0 < 12.0  →  TRUE
        // which returns null on every single tick → zero signals → zero trades.
        // Treat any non-positive VIX as "unknown; use conservative fallback".
        double currentVix = 15.0; // conservative fallback
        try {
            double fetched = vixService.getIndiaVix();
            if (fetched > 0.0) {
                currentVix = fetched;
            } else {
                log.warn("⚠️ VIX circuit breaker active (returned {}) — using fallback VIX={} for signal evaluation",
                    fetched, currentVix);
            }
        } catch (Exception e) {
            log.warn("VixService.getIndiaVix() failed, using fallback VIX={}: {}", currentVix, e.getMessage());
        }

        // Calculate support and resistance from recent price structure
        double[] supportResistance = calculateSupportResistance(history);
        double localSupport = supportResistance[0];
        double localResistance = supportResistance[1];

        Signal signal = trapEngine.detectTrap(
            history,
            localSupport,
            localResistance,
            currentVix,
            calculateSimpleAtr(history, 14)
        );

        if (signal == null || candle.time.equals(lastTriggeredCandleTime)) {
            return;
        }

        // FIX: Use evaluateEntry with proper parameters instead of evaluate(snapshot, signal)
        // BUG-FIX: orHigh = -Inf, orLow = +Inf until first pre-09:45 candle is processed.
        // Math.max(0.0, -Inf - +Inf) = 0.0, making earlySessionBypass always false.
        // If OR is not yet built from the snapshot, compute it directly from candle history.
        double orRange;
        if (Double.isFinite(state.orHigh()) && Double.isFinite(state.orLow())) {
            orRange = Math.max(0.0, state.orHigh() - state.orLow());
        } else {
            // OR not yet in snapshot: compute on-the-fly from pre-09:45 candles
            double computedHigh = Double.NEGATIVE_INFINITY;
            double computedLow  = Double.POSITIVE_INFINITY;
            for (Candle c : history) {
                if (c.timestamp().toLocalTime().isBefore(java.time.LocalTime.of(9, 45))) {
                    computedHigh = Math.max(computedHigh, c.high);
                    computedLow  = Math.min(computedLow,  c.low);
                }
            }
            orRange = (Double.isFinite(computedHigh) && Double.isFinite(computedLow))
                ? Math.max(0.0, computedHigh - computedLow)
                : 0.0;
        }
        double entrySlippage = calculateEntrySlippage(signal, candle);  // Calculate from market conditions
        long latencyMs = calculateLatency(activeSignalTime);  // Calculate from signal timing
        
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
            signal.getDirection(),  // direction
            signal.getEntry(),  // entryPrice
            signal.getStopLoss(),  // stopLoss
            signal.getTarget(),  // tp1Level
            riskPoints,  // initialRiskPoints
            false,  // tp1Hit
            false,  // runnerActive
            signal.getQuantity(),  // positionSize
            signal.getQuantity(),  // remainingSize
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
        double totalSize = trade.positionSize();

        // FIX BUG-A: totalPnlPoints must include BOTH the TP1 portion (already booked
        // in trade.realizedPnL()) AND the runner/exit portion (exit.pnl()).
        // Previously only exit.pnl() was used, causing TP1 profit to be invisible to
        // the balance, realizedR, and cumulativeDailyLossR calculations.
        double totalPnlPoints = trade.realizedPnL() + exit.pnl();

        // FIX BUG-B: realizedR uses totalPnlPoints, not just exit.pnl().
        // Formula: R = totalPnlPoints / (riskPts * totalSize)
        // riskPts = |entry - sl| in index points; totalSize = lots/units at entry.
        // riskPts * totalSize = total rupee-risk in point-units (before ₹50 multiplier).
        double realizedR = totalPnlPoints / (riskPts * totalSize);
        
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

        // firstTradeFailure: if the FIRST trade of the day stopped out with deep MAE (no TP1 hit,
        // realizedR <= -1R, MAE < -150 pts), block the rest of the day — likely a bad tape.
        // Threshold raised from 80 → 150 to avoid killing days on normal stop-outs.
        // In PAPER_MODE this block is fully disabled so we can observe all trades.
        double maeThreshold = config.isPaperMode() ? Double.NEGATIVE_INFINITY : -150.0;
        boolean firstTradeFailure = !config.isPaperMode()
            && state.tradesTaken() == 1 && !trade.tp1Hit() && trade.mae() < maeThreshold && realizedR <= -1.0;
        if (firstTradeFailure) {
            dayBlockedByFirstTradeFailure = true;
        }

        int newConsecutiveLosses = realizedR < 0.0 ? state.consecutiveLosses() + 1 : 0;

        // FIX BUG-BALANCE: balanceChange = totalPnlPoints directly (no multiplier).
        // qty is sized as: qty = riskCapital / slDistance, so qty * slDistance = riskCapital (in ₹).
        // Therefore totalPnlPoints (= price_diff * qty) is already in rupees.
        // The old * 50 multiplier was wrong — it inflated every P&L by 50x.
        double balanceChange = totalPnlPoints;
        
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

        // BUG-FIX: Feed trade results to RealTimeEdgeTracker and AdaptiveRegimeEngine
        // so edge decay detection and adaptive filtering learn from completed trades.
        try {
            edgeTracker.addTradeResult(realizedR, trade.tp1Hit(), trade.runnerActive(),
                entrySlippage, runnerSlippage);
        } catch (Exception e) {
            log.warn("Failed to update RealTimeEdgeTracker with trade result: {}", e.getMessage());
        }
        try {
            adaptiveRegimeEngine.addTradeResult(realizedR, trade.tp1Hit(), trade.runnerActive(),
                entrySlippage, runnerSlippage, sessionFeatures);
        } catch (Exception e) {
            log.warn("Failed to update AdaptiveRegimeEngine with trade result: {}", e.getMessage());
        }

        // BUG-FIX: Persist shadow trade to DB so trade history and performance metrics work.
        // Without this, every trade was lost on restart and the dashboard showed 0 trades.
        try {
            final ActiveTradeExecution finalTrade = trade;
            final TradeExit finalExit = exit;
            final double finalRealizedR = realizedR;
            final double finalTotalPnlPoints = totalPnlPoints;
            final LocalDateTime finalSignalTime = signalTime;
            transactionTemplate.execute(status -> {
                // FIX: Was hardcoded "NIFTY" — now uses the configured trading symbol
                String symbol = (tradingSymbol != null && !tradingSymbol.isBlank())
                    ? tradingSymbol.trim().toUpperCase() : "NIFTY";
                Trade dbTrade = Trade.builder()
                    .symbol(symbol)
                    .direction(finalTrade.direction())
                    .entryPrice(BigDecimal.valueOf(finalTrade.entryPrice()))
                    .stopLoss(BigDecimal.valueOf(finalTrade.stopLoss()))
                    .targetPrice(BigDecimal.valueOf(finalTrade.tp1Level()))
                    .positionSize(BigDecimal.valueOf(finalTrade.positionSize()))
                    .remainingSize(BigDecimal.valueOf(finalTrade.remainingSize()))
                    // FIX BUG-D: store TOTAL PnL (TP1 + runner), not just the exit leg.
                    // trade.realizedPnL() holds the TP1 partial profit; exit.pnl() holds
                    // the runner/SL exit profit.  Sum = true closed-trade P&L in points.
                    .realizedPnL(BigDecimal.valueOf(finalTotalPnlPoints))
                    .unrealizedPnL(BigDecimal.ZERO)
                    .maxFavorableExcursion(BigDecimal.valueOf(finalTrade.mfe()))
                    .maxAdverseExcursion(BigDecimal.valueOf(finalTrade.mae()))
                    .tp1Hit(finalTrade.tp1Hit())
                    .runnerActive(finalTrade.runnerActive())
                    .tailHalfLocked(false)
                    .trailingStopLoss(BigDecimal.valueOf(finalTrade.trailingSL()))
                    // FIX BUG-E: persist initialRiskPoints and realizedR so the frontend
                    // can display accurate R-multiples from DB-loaded history rows.
                    .initialRiskPoints(BigDecimal.valueOf(finalTrade.initialRiskPoints() * finalTrade.positionSize()))
                    .realizedR(BigDecimal.valueOf(finalRealizedR))
                    .status("CLOSED")
                    .exitReason(finalExit.exitReason() != null ? finalExit.exitReason() : "UNKNOWN")
                    .entryTime(finalSignalTime != null ? finalSignalTime : LocalDateTime.now())
                    .exitTime(LocalDateTime.now())
                    .build();
                tradeRepository.save(dbTrade);
                log.info("✅ Shadow trade persisted to DB: direction={}, totalPnl={}, R={}",
                    finalTrade.direction(), finalTotalPnlPoints, finalRealizedR);
                return null;
            });
        } catch (Exception e) {
            log.warn("⚠️ Failed to persist shadow trade to DB (non-fatal): {}", e.getMessage());
        }

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
        // Handle both BUY/SELL and LONG/SHORT naming conventions
        boolean isLong = trade.direction().equalsIgnoreCase("BUY") || trade.direction().equalsIgnoreCase("LONG");
        // BUG-FIX: Include remainingSize in PnL so it matches checkStopLoss() output.
        // Previously this returned raw points with no size multiplier, causing balanceChange
        // to be severely understated for forced exits (TIME_CUTOFF, VOLATILITY_COLLAPSE).
        double exitSize = trade.remainingSize() > 0 ? trade.remainingSize() : trade.positionSize();
        double pnl = (isLong ? (price - trade.entryPrice()) : (trade.entryPrice() - price)) * exitSize;
        return new TradeExit(true, pnl, reason, price);
    }

    /** Sync PAPER_MODE and MAX_TRADES_PER_DAY env vars into the shared config bean so all engines see them. */
    @PostConstruct
    public void syncPaperMode() {
        if (paperModeOverride && !config.isPaperMode()) {
            config.setPaperMode(true);
            log.warn("⚠️ PAPER_MODE enabled via env var — live-money guards bypassed");
        }
        if (maxTradesPerDayEnvOverride > 0) {
            config.getRisk().setMaxTradesPerDay(maxTradesPerDayEnvOverride);
            log.info("✅ MAX_TRADES_PER_DAY overridden via env var: {}", maxTradesPerDayEnvOverride);
        }
    }

    /**
     * Cold-start recovery (Fix #1).
     * On Render free tier the JVM restarts from scratch every deploy.
     * Restore today's closed trades from DB so tradesTaken, consecutiveLosses,
     * cumulativeDailyLossR, and paperBalance are correct before the first tick.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreSessionCandles() {
        // ── 1. Restore candle history ──────────────────────────────────────────
        try {
            LocalDate today = LocalDate.now();
            String[] symbolsToTry = {"NIFTY", "BANKNIFTY"};
            java.util.List<com.riskpilot.model.CandleEntity> todayCandles = java.util.Collections.emptyList();
            for (String sym : symbolsToTry) {
                todayCandles = candleRepository.findBySymbolAndDateOrderByTimestampAsc(sym, today);
                if (!todayCandles.isEmpty()) break;
            }
            if (!todayCandles.isEmpty()) {
                // BUG-CRITICAL-FIX: Previously called candleAggregator.addCandle() which only
                // fills historicalBuffer but never calls ingestClosedCandleForIndicators().
                // RegimeFilter.processCandle() was therefore never called for restored candles,
                // leaving openingRange=0.0, causing ADAPTIVE_OR_TOO_SMALL to block ALL trades
                // all day after every restart.
                //
                // Fix: reset lastStoredCandleTime so ingestClosedCandleForIndicators() processes
                // every restored candle, then call both addCandle AND ingestClosedCandleForIndicators
                // in order. This fully populates RegimeFilter, VolatilityNormalizer, and
                // candleHistory (for RegimeConfidenceEngine) before the first live tick arrives.
                lastStoredCandleTime = ""; // ensure dedup doesn't skip any restored candle
                int restoredCount = 0;
                for (com.riskpilot.model.CandleEntity entity : todayCandles) {
                    Candle restoredCandle = entity.toCandle();
                    candleAggregator.addCandle(restoredCandle);
                    ingestClosedCandleForIndicators(restoredCandle);
                    restoredCount++;
                }
                // Sync session OR high/low from restored candle history
                updateSessionStateFromTime(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")));
                log.info("✅ Restored {} candles for {} from DB — RegimeFilter, VolatilityNormalizer, and candleHistory fully populated", restoredCount, today);
            } else {
                log.info("No persisted candles found for today ({}) — engine will build from live ticks", today);
            }
        } catch (Exception e) {
            log.warn("⚠️ Failed to restore session candles from DB (non-fatal): {}", e.getMessage());
        }

        // ── 2. Restore session counters from today's closed trades (Fix #1) ───
        try {
            LocalDate today = LocalDate.now();
            LocalDateTime dayStart = today.atStartOfDay();
            LocalDateTime dayEnd   = today.plusDays(1).atStartOfDay();

            // FIX: NEVER use tradeRepository.findAll() — it loads the entire trades table
            // into memory on every startup, which is an O(N) full-table scan that becomes
            // catastrophically slow as trade history grows. Use a date-bounded query instead.
            List<Trade> closedToday = tradeRepository.findByStatusAndExitTimeBetween("CLOSED", dayStart, dayEnd);

            if (closedToday.isEmpty()) {
                log.info("No closed trades found for today — session counters start at zero");
                return;
            }

            int tradesTaken = closedToday.size();
            double cumulativeDailyLossR = 0.0;
            int consecutiveLosses = 0;
            double paperBalance = stateManager.getSnapshot().paperBalance();

            for (Trade t : closedToday) {
                double r = t.getRealizedR() != null ? t.getRealizedR().doubleValue() : 0.0;
                cumulativeDailyLossR += r;
                // paperBalance adjustment: realizedPnL already in rupees
                double pnl = t.getRealizedPnL() != null ? t.getRealizedPnL().doubleValue() : 0.0;
                paperBalance += pnl;
                if (r < 0.0) {
                    consecutiveLosses++;
                } else {
                    consecutiveLosses = 0; // reset streak on a win
                }
            }

            final double finalBalance    = paperBalance;
            final double finalLossR      = cumulativeDailyLossR;
            final int    finalConsec     = consecutiveLosses;
            final int    finalTradesTaken = tradesTaken;

            stateManager.update(current -> new TradingSessionSnapshot(
                current.sessionActive(),
                current.regime(),
                current.volatilityQualified(),
                current.timePhase(),
                finalTradesTaken,
                false,
                current.feedStable(),
                current.heartbeatAlive(),
                current.orHigh(),
                current.orLow(),
                finalLossR,
                finalConsec,
                null,
                "RESTORED_FROM_DB",
                finalBalance
            ));

            log.info("✅ Session state restored from DB: tradesTaken={}, consecutiveLosses={}, "
                + "cumulativeDailyLossR={}R, paperBalance=₹{}",
                finalTradesTaken, finalConsec, String.format("%.2f", finalLossR),
                String.format("%.0f", finalBalance));
        } catch (Exception e) {
            log.warn("⚠️ Failed to restore session state from DB (non-fatal): {}", e.getMessage());
        }
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
        // FIX: use lastKnownSpot so the 10-second scheduler broadcast doesn't
        // send spot=0.0, which was blanking the NIFTY price card on the frontend.
        broadcastCurrentSessionState(lastKnownSpot);
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
        // FIX: VixService returns -1.0 when circuit breaker fires; clamp to 0
        // so the frontend never shows a negative VIX on the dashboard.
        payload.put("spot", currentPrice > 0 ? currentPrice : 0.0);
        double broadcastVix = vixService.getIndiaVix();
        payload.put("vix", broadcastVix > 0.0 ? broadcastVix : 0.0);
        payload.put("marketOpen", marketSessionService.isMarketOpen());

        // Dynamic Support/Resistance based on current price
        double spot = currentPrice > 0 ? currentPrice : 0.0;
        if (spot > 0) {
            int support = (int) (Math.floor(spot / 50.0) * 50);
            int resistance = (int) (Math.ceil(spot / 50.0) * 50);
            if (resistance == support) resistance += 50;
            payload.put("support", support);
            payload.put("resistance", resistance);
        }

        // Add detailed regime metrics so the frontend can show WHY trading is blocked
        RegimeFilter.RegimeMetrics regimeMetrics = regimeFilter.getCurrentRegime();
        if (regimeMetrics != null) {
            payload.put("regimeScore", regimeMetrics.getRegimeScore());
            payload.put("regimeTrading", regimeMetrics.isTradingAllowed());
            payload.put("atrRatio", Math.round(regimeMetrics.getAtrRatio() * 100.0) / 100.0);
            payload.put("trendEfficiency", Math.round(regimeMetrics.getTrendEfficiency() * 100.0) / 100.0);
            payload.put("breakoutHoldRate", Math.round(regimeMetrics.getBreakoutHoldRate() * 100.0) / 100.0);
            payload.put("blockingReasons", String.join(", ", regimeMetrics.getBlockingReasons()));
        }

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

    /**
     * BUG-CRITICAL-FIX: Support/Resistance MUST exclude the two most recent candles
     * (t0 = current/latest, t1 = breakout candle) from the lookback.
     *
     * Root cause of NO_TRADES: TrapEngine checks:
     *   SHORT: t1.high > localResistance (t1 broke above resistance)
     *   LONG:  t1.low  < localSupport    (t1 broke below support)
     *
     * When t1 is included in the lookback that computes localResistance = highestHigh,
     * t1.high IS the highestHigh, so t1.high > highestHigh is always FALSE.
     * Same logic for LONG: if t1 IS the lowestLow, t1.low < lowestLow is always FALSE.
     * Result: trap conditions could never fire → zero trades.
     *
     * Fix: compute S/R from candles PRIOR to t1 (i.e. exclude last 2 from lookback).
     */
    private double[] calculateSupportResistance(List<Candle> history) {
        // Need at least 3 candles: [... historical ..., t1, t0]
        if (history.size() < 3) {
            return new double[]{0.0, 0.0};
        }

        // Exclude t0 (index size-1) and t1 (index size-2) from support/resistance calculation.
        // S/R must be derived from the candles BEFORE the breakout candle (t1).
        int endIdx = history.size() - 2; // exclusive: up to but not including t1
        int lookbackPeriod = Math.min(20, endIdx);
        int startIdx = endIdx - lookbackPeriod;

        double highestHigh = Double.NEGATIVE_INFINITY;
        double lowestLow = Double.POSITIVE_INFINITY;

        for (int i = startIdx; i < endIdx; i++) {
            Candle candle = history.get(i);
            highestHigh = Math.max(highestHigh, candle.high);
            lowestLow = Math.min(lowestLow, candle.low);
        }

        if (!Double.isFinite(highestHigh) || !Double.isFinite(lowestLow)) {
            Candle lastCandle = history.get(history.size() - 1);
            return new double[]{lastCandle.close, lastCandle.close};
        }

        return new double[]{lowestLow, highestHigh};
    }

    private double calculateEntrySlippage(Signal signal, Candle candle) {
        if (signal == null || candle == null) {
            return 0.0;
        }

        double expectedEntry = signal.getEntry();
        double actualPrice = candle.close;
        double slippage = Math.abs(actualPrice - expectedEntry);

        double maxSlippage = expectedEntry * 0.02;
        return Math.min(slippage, maxSlippage);
    }

    private long calculateLatency(LocalDateTime signalTime) {
        if (signalTime == null) {
            return 0L;
        }
        return java.time.Duration.between(signalTime, LocalDateTime.now()).toMillis();
    }
}
