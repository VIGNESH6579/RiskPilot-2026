package com.riskpilot.engine;

import com.riskpilot.model.TradingSessionSnapshot;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;

@Slf4j
@Component
public class RegimeConfidenceEngine {

    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 45);
    private static final LocalTime EARLY_SESSION_END = LocalTime.of(10, 30);
    
    @Data
    public static class RegimeScore {
        private final int totalScore;
        private final boolean tradingAllowed;
        private final boolean reducedMode;
        private final String reason;
        private final ComponentScores components;

        public RegimeScore(int totalScore, boolean tradingAllowed, boolean reducedMode, 
                          String reason, ComponentScores components) {
            this.totalScore = totalScore;
            this.tradingAllowed = tradingAllowed;
            this.reducedMode = reducedMode;
            this.reason = reason;
            this.components = components;
        }
    }

    @Data
    public static class ComponentScores {
        private final int orRangeScore;
        private final int breakoutFollowScore;
        private final int volatilityExpansionScore;
        private final int marketEfficiencyScore;
        private final int fakeBreakoutScore;
        private final int earlyMomentumScore;

        public ComponentScores(int orRangeScore, int breakoutFollowScore, int volatilityExpansionScore,
                             int marketEfficiencyScore, int fakeBreakoutScore, int earlyMomentumScore) {
            this.orRangeScore = orRangeScore;
            this.breakoutFollowScore = breakoutFollowScore;
            this.volatilityExpansionScore = volatilityExpansionScore;
            this.marketEfficiencyScore = marketEfficiencyScore;
            this.fakeBreakoutScore = fakeBreakoutScore;
            this.earlyMomentumScore = earlyMomentumScore;
        }
    }

    /**
     * Evaluate regime confidence based on session state and market structure
     */
    public RegimeScore evaluate(TradingSessionSnapshot state, List<CandleData> candles) {
        ComponentScores components = calculateComponents(state, candles);
        int totalScore = components.getOrRangeScore() + components.getBreakoutFollowScore() + 
                        components.getVolatilityExpansionScore() + components.getMarketEfficiencyScore() + 
                        components.getFakeBreakoutScore() + components.getEarlyMomentumScore();

        // Hard decisions - no interpretation
        if (totalScore < 55) {
            return new RegimeScore(totalScore, false, false, "LOW_QUALITY_DAY", components);
        }

        if (totalScore < 70) {
            return new RegimeScore(totalScore, true, true, "REDUCED_MODE", components);
        }

        return new RegimeScore(totalScore, true, false, "FULL_MODE", components);
    }

    /**
     * Calculate all component scores
     */
    private ComponentScores calculateComponents(TradingSessionSnapshot state, List<CandleData> candles) {
        int orRangeScore = scoreORRange(state);
        int breakoutFollowScore = scoreBreakoutFollowThrough(candles);
        int volatilityExpansionScore = scoreVolatilityExpansion(candles);
        int marketEfficiencyScore = scoreMarketEfficiency(candles);
        int fakeBreakoutScore = scoreFakeBreakoutRate(candles);
        int earlyMomentumScore = scoreEarlyMomentum(candles);

        return new ComponentScores(orRangeScore, breakoutFollowScore, volatilityExpansionScore,
                                 marketEfficiencyScore, fakeBreakoutScore, earlyMomentumScore);
    }

    /**
     * 1. Opening Range Quality (weight: 25)
     * Bad OR = fake breakouts = trap failure
     */
    private int scoreORRange(TradingSessionSnapshot state) {
        double orRange = Math.max(0.0, state.orHigh() - state.orLow());
        
        if (orRange < 90) return 0;
        if (orRange < 120) return 10;
        if (orRange < 150) return 18;
        return 25; // >150
    }

    /**
     * 2. Breakout Follow-through (weight: 20)
     * % of candles closing beyond OR boundary
     */
    private int scoreBreakoutFollowThrough(List<CandleData> candles) {
        if (candles.size() < 5) return 0; // Not enough data

        double orHigh = candles.stream()
                .filter(c -> c.timestamp.toLocalTime().isBefore(OPENING_RANGE_END))
                .mapToDouble(c -> c.high)
                .max()
                .orElse(0.0);
        
        double orLow = candles.stream()
                .filter(c -> c.timestamp.toLocalTime().isBefore(OPENING_RANGE_END))
                .mapToDouble(c -> c.low)
                .min()
                .orElse(Double.MAX_VALUE);

        if (orHigh == 0.0 || orLow == Double.MAX_VALUE) return 0;

        // Count candles closing beyond OR boundaries
        long totalBreakouts = candles.stream()
                .filter(c -> c.timestamp.toLocalTime().isAfter(OPENING_RANGE_END))
                .count();

        long followThrough = candles.stream()
                .filter(c -> c.timestamp.toLocalTime().isAfter(OPENING_RANGE_END))
                .filter(c -> c.close > orHigh || c.close < orLow)
                .count();

        if (totalBreakouts == 0) return 0;

        double followRate = (double) followThrough / totalBreakouts;
        
        if (followRate < 0.40) return 0;
        if (followRate < 0.55) return 8;
        if (followRate < 0.70) return 14;
        return 20; // >70%
    }

    /**
     * 3. Volatility Expansion (weight: 15)
     * ATR ratio = current ATR / previous ATR
     */
    private int scoreVolatilityExpansion(List<CandleData> candles) {
        if (candles.size() < 10) return 0;

        // Calculate ATR for recent vs previous period
        double recentATR = calculateATR(candles.subList(0, Math.min(5, candles.size())));
        double previousATR = calculateATR(candles.subList(Math.max(0, candles.size() - 5), candles.size()));

        if (previousATR == 0.0) return 0;

        double atrRatio = recentATR / previousATR;
        
        if (atrRatio < 1.0) return 0;
        if (atrRatio < 1.2) return 6;
        if (atrRatio < 1.5) return 11;
        return 15; // >1.5
    }

    /**
     * 4. Market Efficiency (weight: 15)
     * efficiency = net_move / total_range
     */
    private int scoreMarketEfficiency(List<CandleData> candles) {
        if (candles.size() < 3) return 0;

        double netMove = Math.abs(candles.get(candles.size() - 1).close - candles.get(0).open);
        double totalRange = candles.stream()
                .mapToDouble(c -> c.high - c.low)
                .sum();

        if (totalRange == 0.0) return 0;

        double efficiency = netMove / totalRange;
        
        if (efficiency < 0.4) return 0;
        if (efficiency < 0.55) return 6;
        if (efficiency < 0.7) return 11;
        return 15; // >0.7
    }

    /**
     * 5. Fake Breakout Rate (weight: 15)
     * fake_rate = failed_breakouts / total_breakouts
     */
    private int scoreFakeBreakoutRate(List<CandleData> candles) {
        if (candles.size() < 8) return 0;

        double orHigh = candles.stream()
                .filter(c -> c.timestamp.toLocalTime().isBefore(OPENING_RANGE_END))
                .mapToDouble(c -> c.high)
                .max()
                .orElse(0.0);
        
        double orLow = candles.stream()
                .filter(c -> c.timestamp.toLocalTime().isBefore(OPENING_RANGE_END))
                .mapToDouble(c -> c.low)
                .min()
                .orElse(Double.MAX_VALUE);

        if (orHigh == 0.0 || orLow == Double.MAX_VALUE) return 0;

        // Count fake breakouts (breakout that fails within 1-2 candles)
        long totalBreakouts = 0;
        long fakeBreakouts = 0;

        for (int i = 1; i < candles.size() - 1; i++) {
            CandleData current = candles.get(i);
            CandleData next = candles.get(i + 1);
            
            if (current.timestamp.toLocalTime().isAfter(OPENING_RANGE_END)) {
                if (current.high > orHigh || current.low < orLow) {
                    totalBreakouts++;
                    // Check if it failed in the next candle
                    boolean failed = (current.high > orHigh && next.close < orHigh) ||
                                   (current.low < orLow && next.close > orLow);
                    if (failed) fakeBreakouts++;
                }
            }
        }

        if (totalBreakouts == 0) return 10; // Default score if no breakouts

        double fakeRate = (double) fakeBreakouts / totalBreakouts;
        
        if (fakeRate > 0.60) return 0;
        if (fakeRate > 0.40) return 6;
        if (fakeRate > 0.20) return 11;
        return 15; // <20%
    }

    /**
     * 6. Early Momentum Quality (weight: 10)
     * avg candle body size (first 1 hr)
     */
    private int scoreEarlyMomentum(List<CandleData> candles) {
        List<CandleData> earlyCandles = candles.stream()
                .filter(c -> c.timestamp.toLocalTime().isBefore(EARLY_SESSION_END))
                .toList();

        if (earlyCandles.isEmpty()) return 0;

        double avgBodySize = earlyCandles.stream()
                .mapToDouble(c -> Math.abs(c.close - c.open))
                .average()
                .orElse(0.0);

        double avgRange = earlyCandles.stream()
                .mapToDouble(c -> c.high - c.low)
                .average()
                .orElse(0.0);

        if (avgRange == 0.0) return 0;

        double bodyToRangeRatio = avgBodySize / avgRange;
        
        if (bodyToRangeRatio < 0.3) return 0;  // weak
        if (bodyToRangeRatio < 0.5) return 5;  // moderate
        return 10; // strong
    }

    /**
     * Simple ATR calculation
     */
    private double calculateATR(List<CandleData> candles) {
        if (candles.size() < 2) return 0.0;

        double totalRange = 0.0;
        for (int i = 1; i < candles.size(); i++) {
            CandleData prev = candles.get(i - 1);
            CandleData curr = candles.get(i);
            
            double highLow = curr.high - curr.low;
            double highClose = Math.abs(curr.high - prev.close);
            double lowClose = Math.abs(curr.low - prev.close);
            
            totalRange += Math.max(highLow, Math.max(highClose, lowClose));
        }

        return totalRange / (candles.size() - 1);
    }

    /**
     * Get regime confidence statistics
     */
    public java.util.Map<String, Object> getStats() {
        return java.util.Map.of(
            "engine", "RegimeConfidenceEngine",
            "maxScore", 100,
            "fullModeThreshold", 70,
            "reducedModeThreshold", 55,
            "blockThreshold", 55,
            "components", java.util.Map.of(
                "orRange", 25,
                "breakoutFollow", 20,
                "volatilityExpansion", 15,
                "marketEfficiency", 15,
                "fakeBreakout", 15,
                "earlyMomentum", 10
            )
        );
    }

    @Data
    public static class CandleData {
        private final double open;
        private final double high;
        private final double low;
        private final double close;
        private final java.time.LocalDateTime timestamp;

        public CandleData(double open, double high, double low, double close, java.time.LocalDateTime timestamp) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.timestamp = timestamp;
        }
    }
}
