package com.riskpilot.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class RegimeFilter {

    private static final int TREND_WINDOW = 5;
    private static final double OR_EXPANSION_THRESHOLD = 120.0;
    private static final double OR_EXPANSION_MIN = 90.0;
    private static final double ATR_EXPANSION_RATIO = 1.2;
    private static final double TREND_EFFICIENCY_MIN = 0.6;
    private static final double BREAKOUT_HOLD_RATE_MIN = 0.5;
    private static final int MIN_REGIME_SCORE = 4;
    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 45);

    private final Queue<CandleData> candleHistory = new ConcurrentLinkedQueue<>();
    private final Queue<BreakoutData> breakoutHistory = new ConcurrentLinkedQueue<>();
    private final AtomicReference<RegimeMetrics> currentRegime = new AtomicReference<>();
    private final AtomicReference<Double> openingRange = new AtomicReference<>(0.0);
    private final AtomicReference<Double> openingATR = new AtomicReference<>(0.0);

    @Data
    public static class CandleData {
        private final double open;
        private final double high;
        private final double low;
        private final double close;
        private final double volume;
        private final LocalDateTime timestamp;
        private final double atr;

        public CandleData(double open, double high, double low, double close, double volume, 
                         LocalDateTime timestamp, double atr) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
            this.timestamp = timestamp;
            this.atr = atr;
        }

        public double getEfficiency() {
            double range = high - low;
            return range > 0 ? Math.abs(close - open) / range : 0.0;
        }
    }

    @Data
    public static class BreakoutData {
        private final double breakoutPrice;
        private final boolean held;
        private final LocalDateTime timestamp;

        public BreakoutData(double breakoutPrice, boolean held, LocalDateTime timestamp) {
            this.breakoutPrice = breakoutPrice;
            this.held = held;
            this.timestamp = timestamp;
        }
    }

    @Data
    public static class RegimeMetrics {
        private final int regimeScore;
        private final double orRange;
        private final double atrRatio;
        private final double trendEfficiency;
        private final double breakoutHoldRate;
        private final boolean tradingAllowed;
        private final LocalDateTime timestamp;
        private final List<String> blockingReasons;

        public RegimeMetrics(int regimeScore, double orRange, double atrRatio,
                           double trendEfficiency, double breakoutHoldRate,
                           boolean tradingAllowed, List<String> blockingReasons) {
            this.regimeScore = regimeScore;
            this.orRange = orRange;
            this.atrRatio = atrRatio;
            this.trendEfficiency = trendEfficiency;
            this.breakoutHoldRate = breakoutHoldRate;
            this.tradingAllowed = tradingAllowed;
            this.timestamp = LocalDateTime.now();
            this.blockingReasons = new ArrayList<>(blockingReasons);
        }
    }

    /**
     * Process new candle and update regime metrics
     */
    public synchronized void processCandle(double open, double high, double low, double close, 
                                         double volume, LocalDateTime timestamp, double atr) {
        CandleData candle = new CandleData(open, high, low, close, volume, timestamp, atr);
        
        // Update history
        candleHistory.offer(candle);
        if (candleHistory.size() > 20) { // Keep last 20 candles
            candleHistory.poll();
        }

        // BUG-FIX: OR range must span the FULL opening period (9:15-9:45), not just the last candle.
        // Accumulate the highest-high and lowest-low across all pre-9:45 candles.
        if (timestamp.toLocalTime().isBefore(OPENING_RANGE_END)) {
            double currentOrHigh = candleHistory.stream()
                    .filter(c -> c.getTimestamp().toLocalTime().isBefore(OPENING_RANGE_END))
                    .mapToDouble(CandleData::getHigh)
                    .max()
                    .orElse(high);
            double currentOrLow = candleHistory.stream()
                    .filter(c -> c.getTimestamp().toLocalTime().isBefore(OPENING_RANGE_END))
                    .mapToDouble(CandleData::getLow)
                    .min()
                    .orElse(low);
            double dayRange = currentOrHigh - currentOrLow;
            openingRange.set(Math.max(0.0, dayRange));
            openingATR.set(atr);
            log.info("🌅 Opening Range updated: high={}, low={}, range={}, ATR: {}", currentOrHigh, currentOrLow, dayRange, atr);
        }

        // Detect breakouts
        detectBreakouts(candle);

        // Compute regime metrics
        RegimeMetrics metrics = computeRegimeMetrics();
        currentRegime.set(metrics);

        log.debug("📊 Regime updated: Score={}, Trading={}, OR={}, ATR={}, Eff={}, Hold={}",
                metrics.getRegimeScore(), metrics.isTradingAllowed(), metrics.getOrRange(),
                metrics.getAtrRatio(), metrics.getTrendEfficiency(), metrics.getBreakoutHoldRate());
    }

    /**
     * Detect breakouts and track hold rates
     */
    private void detectBreakouts(CandleData candle) {
        double orRange = openingRange.get();
        if (orRange == 0) return; // No opening range yet

        // Simple breakout detection from the latest candles.
        // We need to compare the current candle (t0) against the range of PRIOR candles.
        List<CandleData> allCandles = new ArrayList<>(candleHistory);
        if (allCandles.size() < 2) return;

        // Get the high/low of the candles BEFORE the current one
        List<CandleData> priorCandles = allCandles.subList(0, allCandles.size() - 1);
        int lookback = Math.min(priorCandles.size(), 5);
        List<CandleData> recentPrior = priorCandles.subList(priorCandles.size() - lookback, priorCandles.size());

        double priorHigh = recentPrior.stream().mapToDouble(CandleData::getHigh).max().orElse(Double.MAX_VALUE);
        double priorLow = recentPrior.stream().mapToDouble(CandleData::getLow).min().orElse(0.0);

        // A breakout occurs if the current candle's high/low exceeds the prior range
        boolean isHighBreakout = candle.getHigh() > priorHigh;
        boolean isLowBreakout = candle.getLow() < priorLow;

        if (isHighBreakout || isLowBreakout) {
            // A breakout "holds" if the close is also beyond the prior level
            boolean held = (isHighBreakout && candle.getClose() > priorHigh) ||
                          (isLowBreakout && candle.getClose() < priorLow);
            
            BreakoutData breakout = new BreakoutData(isHighBreakout ? priorHigh : priorLow, held, candle.getTimestamp());
            breakoutHistory.offer(breakout);
            if (breakoutHistory.size() > 10) {
                breakoutHistory.poll();
            }
        }


    }

    /**
     * Compute regime metrics
     */
    private RegimeMetrics computeRegimeMetrics() {
        // BUG-A FIX: Early-session insufficient-data bypass.
        //
        // BEFORE: with fewer than 10 candles in history, only the OR range
        // contributes to the score (max +2). ATR ratio, trend efficiency, and
        // breakout hold rate all return 0.0 / empty → "neutral" (no score, no block).
        // Score = 2 < MIN_REGIME_SCORE (4) → tradingAllowed = false → regime = CHOP
        // → RiskGateEngine rejects with ADAPTIVE_REGIME_BLOCKED.
        // This blocks ALL trades for the first ~50 minutes of the session
        // (6 pre-OR candles + first few post-OR candles).
        //
        // AFTER: if we have fewer than 10 candles, we don't have enough data to
        // make a reliable regime judgement either way. Default to "allowed" and let
        // the other gate checks (OR range, VIX, slippage, etc.) do their job.
        // RegimeFilter will assert itself properly once data is available.
        List<CandleData> candleSnapshot = new ArrayList<>(candleHistory);
        if (candleSnapshot.size() < 10) {
            log.info("RegimeFilter: insufficient candles ({}) for regime evaluation — " +
                     "defaulting to tradingAllowed=true (early-session bypass)",
                     candleSnapshot.size());
            return new RegimeMetrics(
                MIN_REGIME_SCORE,          // score: at threshold (allowed)
                openingRange.get(),        // real OR range so adaptive OR check uses actual value
                0.0, 0.0, 0.0,            // ATR ratio, efficiency, hold rate: 0 = not yet computable
                true,                      // tradingAllowed
                List.of()                  // no blocking reasons
            );
        }

        List<String> blockingReasons = new ArrayList<>();
        int score = 0;

        // 1. Opening Range Expansion
        double orRange = openingRange.get();
        if (orRange > OR_EXPANSION_THRESHOLD) {
            score += 2;
        } else if (orRange > OR_EXPANSION_MIN) {
            score += 1;
        } else {
            blockingReasons.add("OR_EXPANSION_INSUFFICIENT");
        }

        // 2. Volatility Continuity (ATR Ratio)
        // BUG-FIX: computeATRRatio() returns 0.0 when either openingATR==0 (OR not built yet)
        // or no post-OR candles exist yet. Both are "insufficient data" states, not
        // "volatility dying". Treat 0.0 as neutral to avoid blocking early-session trades.
        double atrRatio = computeATRRatio();
        if (atrRatio > ATR_EXPANSION_RATIO) {
            score += 1;
        } else if (atrRatio > 0.0 && atrRatio < 1.0) {
            // Only penalize when we actually have data showing contraction
            blockingReasons.add("VOLATILITY_DYING");
        }
        // atrRatio == 0.0 → no post-OR candles yet → skip (neutral)

        // 3. Directional Efficiency
        // BUG-FIX: computeTrendEfficiency() returns 0.0 when fewer than TREND_WINDOW (5)
        // candles are available. This is an "insufficient data" state, not choppy market.
        // Treat as neutral (no score, no penalty) until we have enough data.
        double trendEfficiency = computeTrendEfficiency();
        if (candleSnapshot.size() < TREND_WINDOW) {
            // Not enough candles yet — skip this check entirely (neutral)
            log.debug("Candle history ({}) < TREND_WINDOW ({}) — skipping trend efficiency check",
                candleSnapshot.size(), TREND_WINDOW);
        } else if (trendEfficiency > TREND_EFFICIENCY_MIN) {
            score += 2;
        } else if (trendEfficiency < 0.4) {
            blockingReasons.add("CHOPPY_MARKET");
        }

        // 4. Breakout Hold Rate
        // BUG-FIX: When breakoutHistory is empty (e.g. after restart before any breakout is
        // detected), computeBreakoutHoldRate() returns 0.0, which adds FAKE_BREAKOUTS and
        // blocks all trading even on a fully valid day. Empty history means "no data yet",
        // not "all breakouts failed". Skip the penalty; award no point (neutral).
        double breakoutHoldRate = computeBreakoutHoldRate();
        List<BreakoutData> currentBreakouts = new ArrayList<>(breakoutHistory);
        if (currentBreakouts.isEmpty()) {
            // No breakouts observed yet — insufficient data to penalize
            log.debug("Breakout history empty — skipping breakout hold rate check");
        } else if (breakoutHoldRate > BREAKOUT_HOLD_RATE_MIN) {
            score += 1;
        } else {
            blockingReasons.add("FAKE_BREAKOUTS");
        }

        boolean tradingAllowed = score >= MIN_REGIME_SCORE;

        // BUG-FIX: If we have restored candles but insufficient post-OR history to compute
        // ATR ratio and trend efficiency (data gaps from restore), the score will be
        // artificially low even on a valid trading day. Log the computed score for diagnostics.
        if (!tradingAllowed) {
            log.debug("Regime score {} < {} (MIN) — blocking: {}", score, MIN_REGIME_SCORE, blockingReasons);
        }

        return new RegimeMetrics(score, orRange, atrRatio, trendEfficiency, 
                               breakoutHoldRate, tradingAllowed, blockingReasons);
    }

    /**
     * Compute ATR ratio (current vs opening)
     */
    private double computeATRRatio() {
        double openingAtr = openingATR.get();
        if (openingAtr == 0) return 0.0;

        List<CandleData> postOpeningRange = new ArrayList<>(candleHistory).stream()
                .filter(c -> c.getTimestamp().toLocalTime().isAfter(OPENING_RANGE_END))
                .toList();

        if (postOpeningRange.isEmpty()) return 0.0;

        int size = postOpeningRange.size();
        List<CandleData> recent = postOpeningRange.subList(Math.max(0, size - 5), size);

        double currentATR = recent.stream()
                .mapToDouble(CandleData::getAtr)
                .average()
                .orElse(openingAtr);

        return currentATR / openingAtr;
    }

    /**
     * Compute trend efficiency
     */
    private double computeTrendEfficiency() {
        List<CandleData> allCandles = new ArrayList<>(candleHistory);
        int size = allCandles.size();
        List<CandleData> recent = allCandles.subList(Math.max(0, size - TREND_WINDOW), size);

        if (recent.size() < TREND_WINDOW) return 0.0;

        return recent.stream()
                .mapToDouble(CandleData::getEfficiency)
                .average()
                .orElse(0.0);
    }

    /**
     * Compute breakout hold rate
     */
    private double computeBreakoutHoldRate() {
        List<BreakoutData> breakouts = new ArrayList<>(breakoutHistory);
        if (breakouts.isEmpty()) return 0.0;

        long held = breakouts.stream()
                .mapToLong(b -> b.isHeld() ? 1 : 0)
                .sum();

        return (double) held / breakouts.size();
    }

    /**
     * Check if trading is allowed
     */
    public boolean isTradingAllowed() {
        RegimeMetrics regime = currentRegime.get();
        return regime != null && regime.isTradingAllowed();
    }

    /**
     * Get current regime metrics
     */
    public RegimeMetrics getCurrentRegime() {
        return currentRegime.get();
    }

    /**
     * Get regime statistics
     */
    public Map<String, Object> getStats() {
        RegimeMetrics regime = currentRegime.get();
        return Map.of(
            "regimeScore", regime != null ? regime.getRegimeScore() : 0,
            "tradingAllowed", regime != null && regime.isTradingAllowed(),
            "orRange", regime != null ? regime.getOrRange() : 0.0,
            "atrRatio", regime != null ? regime.getAtrRatio() : 0.0,
            "trendEfficiency", regime != null ? regime.getTrendEfficiency() : 0.0,
            "breakoutHoldRate", regime != null ? regime.getBreakoutHoldRate() : 0.0,
            "blockingReasons", regime != null ? regime.getBlockingReasons() : List.of(),
            "candleHistory", candleHistory.size(),
            "breakoutHistory", breakoutHistory.size()
        );
    }

    /**
     * Reset regime filter
     */
    public void reset() {
        candleHistory.clear();
        breakoutHistory.clear();
        currentRegime.set(null);
        openingRange.set(0.0);
        openingATR.set(0.0);
        log.info("🔄 Regime filter reset");
    }
}
