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

        // Set opening range (first candle of the day)
        if (timestamp.toLocalTime().isBefore(OPENING_RANGE_END)) {
            double dayRange = high - low;
            openingRange.set(dayRange);
            openingATR.set(atr);
            log.info("🌅 Opening Range set: {:.1f}, ATR: {:.1f}", dayRange, atr);
        }

        // Detect breakouts
        detectBreakouts(candle);

        // Compute regime metrics
        RegimeMetrics metrics = computeRegimeMetrics();
        currentRegime.set(metrics);

        log.debug("📊 Regime updated: Score={}, Trading={}, OR={:.1f}, ATR={:.2f}, Eff={:.2f}, Hold={:.2f}",
                metrics.getRegimeScore(), metrics.isTradingAllowed(), metrics.getOrRange(),
                metrics.getAtrRatio(), metrics.getTrendEfficiency(), metrics.getBreakoutHoldRate());
    }

    /**
     * Detect breakouts and track hold rates
     */
    private void detectBreakouts(CandleData candle) {
        double orRange = openingRange.get();
        if (orRange == 0) return; // No opening range yet

        // Simple breakout detection
        double highBreakout = candleHistory.stream()
                .limit(3) // Last 3 candles
                .mapToDouble(CandleData::getHigh)
                .max()
                .orElse(candle.getHigh());

        double lowBreakout = candleHistory.stream()
                .limit(3)
                .mapToDouble(CandleData::getLow)
                .min()
                .orElse(candle.getLow());

        // Check if breakout held (simplified)
        boolean highHeld = candle.getClose() > highBreakout;
        boolean lowHeld = candle.getClose() < lowBreakout;

        if (highBreakout > 0 || lowBreakout > 0) {
            BreakoutData breakout = new BreakoutData(highBreakout, highHeld || lowHeld, candle.getTimestamp());
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
        double atrRatio = computeATRRatio();
        if (atrRatio > ATR_EXPANSION_RATIO) {
            score += 1;
        } else if (atrRatio < 1.0) {
            blockingReasons.add("VOLATILITY_DYING");
        }

        // 3. Directional Efficiency
        double trendEfficiency = computeTrendEfficiency();
        if (trendEfficiency > TREND_EFFICIENCY_MIN) {
            score += 2;
        } else if (trendEfficiency < 0.4) {
            blockingReasons.add("CHOPPY_MARKET");
        }

        // 4. Breakout Hold Rate
        double breakoutHoldRate = computeBreakoutHoldRate();
        if (breakoutHoldRate > BREAKOUT_HOLD_RATE_MIN) {
            score += 1;
        } else {
            blockingReasons.add("FAKE_BREAKOUTS");
        }

        boolean tradingAllowed = score >= MIN_REGIME_SCORE;

        return new RegimeMetrics(score, orRange, atrRatio, trendEfficiency, 
                               breakoutHoldRate, tradingAllowed, blockingReasons);
    }

    /**
     * Compute ATR ratio (current vs opening)
     */
    private double computeATRRatio() {
        double openingAtr = openingATR.get();
        if (openingAtr == 0) return 0.0;

        List<CandleData> recent = new ArrayList<>(candleHistory).stream()
                .filter(c -> c.getTimestamp().toLocalTime().isAfter(OPENING_RANGE_END))
                .limit(5)
                .toList();

        if (recent.isEmpty()) return 0.0;

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
        List<CandleData> recent = new ArrayList<>(candleHistory).stream()
                .limit(TREND_WINDOW)
                .toList();

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
