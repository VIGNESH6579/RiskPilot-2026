package com.riskpilot.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
    private final AtomicReference<Double> openingHigh = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> openingLow = new AtomicReference<>(Double.NaN);
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

        public CandleData(double open, double high, double low, double close, double volume, LocalDateTime timestamp, double atr) {
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
            return range > 0.0 ? Math.abs(close - open) / range : 0.0;
        }
    }

    @Data
    public static class BreakoutData {
        private final double breakoutPrice;
        private final boolean held;
        private final LocalDateTime timestamp;
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

        public RegimeMetrics(
            int regimeScore,
            double orRange,
            double atrRatio,
            double trendEfficiency,
            double breakoutHoldRate,
            boolean tradingAllowed,
            List<String> blockingReasons
        ) {
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

    public synchronized void processCandle(
        double open,
        double high,
        double low,
        double close,
        double volume,
        LocalDateTime timestamp,
        double atr
    ) {
        CandleData candle = new CandleData(open, high, low, close, volume, timestamp, atr);
        candleHistory.offer(candle);
        if (candleHistory.size() > 20) {
            candleHistory.poll();
        }

        updateOpeningRange(candle);
        detectBreakouts(candle);
        currentRegime.set(computeRegimeMetrics());
    }

    private void updateOpeningRange(CandleData candle) {
        if (candle.getTimestamp().toLocalTime().isAfter(OPENING_RANGE_END)) {
            return;
        }

        double high = Double.isNaN(openingHigh.get()) ? candle.getHigh() : Math.max(openingHigh.get(), candle.getHigh());
        double low = Double.isNaN(openingLow.get()) ? candle.getLow() : Math.min(openingLow.get(), candle.getLow());
        openingHigh.set(high);
        openingLow.set(low);
        openingRange.set(Math.max(0.0, high - low));
        if (openingATR.get() <= 0.0 && candle.getAtr() > 0.0) {
            openingATR.set(candle.getAtr());
        }
    }

    private void detectBreakouts(CandleData candle) {
        List<CandleData> candles = new ArrayList<>(candleHistory);
        if (candles.size() < 2 || openingRange.get() <= 0.0) {
            return;
        }

        List<CandleData> priorCandles = candles.subList(Math.max(0, candles.size() - 4), candles.size() - 1);
        double priorHigh = priorCandles.stream().mapToDouble(CandleData::getHigh).max().orElse(candle.getHigh());
        double priorLow = priorCandles.stream().mapToDouble(CandleData::getLow).min().orElse(candle.getLow());
        boolean brokeHigh = candle.getHigh() > priorHigh;
        boolean brokeLow = candle.getLow() < priorLow;
        if (!brokeHigh && !brokeLow) {
            return;
        }

        double breakoutPrice = brokeHigh ? priorHigh : priorLow;
        boolean held = brokeHigh ? candle.getClose() >= priorHigh : candle.getClose() <= priorLow;
        breakoutHistory.offer(new BreakoutData(breakoutPrice, held, candle.getTimestamp()));
        if (breakoutHistory.size() > 10) {
            breakoutHistory.poll();
        }
    }

    private RegimeMetrics computeRegimeMetrics() {
        List<String> blockingReasons = new ArrayList<>();
        int score = 0;

        double orRange = openingRange.get();
        if (orRange > OR_EXPANSION_THRESHOLD) {
            score += 2;
        } else if (orRange > OR_EXPANSION_MIN) {
            score += 1;
        } else {
            blockingReasons.add("OR_EXPANSION_INSUFFICIENT");
        }

        double atrRatio = computeATRRatio();
        if (atrRatio > ATR_EXPANSION_RATIO) {
            score += 1;
        } else if (atrRatio < 1.0) {
            blockingReasons.add("VOLATILITY_DYING");
        }

        double trendEfficiency = computeTrendEfficiency();
        if (trendEfficiency > TREND_EFFICIENCY_MIN) {
            score += 2;
        } else if (trendEfficiency < 0.4) {
            blockingReasons.add("CHOPPY_MARKET");
        }

        double breakoutHoldRate = computeBreakoutHoldRate();
        if (breakoutHoldRate > BREAKOUT_HOLD_RATE_MIN) {
            score += 1;
        } else {
            blockingReasons.add("FAKE_BREAKOUTS");
        }

        return new RegimeMetrics(score, orRange, atrRatio, trendEfficiency, breakoutHoldRate, score >= MIN_REGIME_SCORE, blockingReasons);
    }

    private double computeATRRatio() {
        double baseAtr = openingATR.get();
        if (baseAtr <= 0.0) {
            return 0.0;
        }

        List<CandleData> candles = new ArrayList<>(candleHistory);
        List<CandleData> postOpening = candles.stream()
            .filter(c -> c.getTimestamp().toLocalTime().isAfter(OPENING_RANGE_END))
            .toList();
        if (postOpening.isEmpty()) {
            return 0.0;
        }

        List<CandleData> recent = postOpening.subList(Math.max(0, postOpening.size() - TREND_WINDOW), postOpening.size());
        double currentAtr = recent.stream().mapToDouble(CandleData::getAtr).average().orElse(baseAtr);
        return currentAtr / baseAtr;
    }

    private double computeTrendEfficiency() {
        List<CandleData> candles = new ArrayList<>(candleHistory);
        if (candles.size() < TREND_WINDOW) {
            return 0.0;
        }

        List<CandleData> recent = candles.subList(candles.size() - TREND_WINDOW, candles.size());
        return recent.stream().mapToDouble(CandleData::getEfficiency).average().orElse(0.0);
    }

    private double computeBreakoutHoldRate() {
        List<BreakoutData> breakouts = new ArrayList<>(breakoutHistory);
        if (breakouts.isEmpty()) {
            return 0.0;
        }

        long held = breakouts.stream().filter(BreakoutData::isHeld).count();
        return (double) held / breakouts.size();
    }

    public boolean isTradingAllowed() {
        RegimeMetrics regime = currentRegime.get();
        return regime != null && regime.isTradingAllowed();
    }

    public RegimeMetrics getCurrentRegime() {
        return currentRegime.get();
    }

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

    public void reset() {
        candleHistory.clear();
        breakoutHistory.clear();
        currentRegime.set(null);
        openingHigh.set(Double.NaN);
        openingLow.set(Double.NaN);
        openingRange.set(0.0);
        openingATR.set(0.0);
    }
}
