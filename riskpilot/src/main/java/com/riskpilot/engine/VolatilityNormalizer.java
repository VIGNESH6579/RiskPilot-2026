package com.riskpilot.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class VolatilityNormalizer {

    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 45);
    private static final double TP1_VOLATILITY_RATIO_MIN = 0.10;
    private static final double TP1_VOLATILITY_RATIO_MAX = 0.15;
    private static final double FIXED_TP1_FALLBACK = 15.0;

    private final AtomicReference<Double> openingRange = new AtomicReference<>(0.0);
    private final AtomicReference<Double> currentTP1 = new AtomicReference<>(FIXED_TP1_FALLBACK);
    private final AtomicReference<LocalDateTime> lastUpdate = new AtomicReference<>(LocalDateTime.now());

    @Data
    public static class TP1Calculation {
        private final double openingRange;
        private final double volatilityRatio;
        private final double calculatedTP1;
        private final double finalTP1;
        private final boolean usedVolatilityNormalization;
        private final LocalDateTime timestamp;

        public TP1Calculation(double openingRange, double volatilityRatio, 
                            double calculatedTP1, double finalTP1, boolean usedVolatilityNormalization) {
            this.openingRange = openingRange;
            this.volatilityRatio = volatilityRatio;
            this.calculatedTP1 = calculatedTP1;
            this.finalTP1 = finalTP1;
            this.usedVolatilityNormalization = usedVolatilityNormalization;
            this.timestamp = LocalDateTime.now();
        }
    }

    /**
     * Update opening range and recalculate TP1
     */
    public synchronized void updateOpeningRange(double high, double low, LocalDateTime timestamp) {
        if (timestamp.toLocalTime().isBefore(OPENING_RANGE_END)) {
            double dayRange = high - low;
            openingRange.set(dayRange);
            recalculateTP1();
            log.info("🌅 Opening Range updated: {:.1f} → TP1: {:.1f}", dayRange, currentTP1.get());
        }
    }

    /**
     * Recalculate TP1 based on current volatility
     */
    private void recalculateTP1() {
        double or = openingRange.get();
        if (or <= 0) {
            currentTP1.set(FIXED_TP1_FALLBACK);
            return;
        }

        // Calculate volatility-normalized TP1
        double volatilityRatio = (TP1_VOLATILITY_RATIO_MIN + TP1_VOLATILITY_RATIO_MAX) / 2.0;
        double calculatedTP1 = or * volatilityRatio;

        // Apply bounds
        double finalTP1 = Math.max(12.0, Math.min(calculatedTP1, or * TP1_VOLATILITY_RATIO_MAX));

        // Use volatility normalization if OR is significant
        boolean usedNormalization = or > 80.0; // Only normalize if meaningful range

        currentTP1.set(usedNormalization ? finalTP1 : FIXED_TP1_FALLBACK);
        lastUpdate.set(LocalDateTime.now());

        log.debug("📊 TP1 Calculation: OR={:.1f}, Ratio={:.3f}, TP1={:.1f}, Normalized={}", 
                or, volatilityRatio, finalTP1, usedNormalization);
    }

    /**
     * Get current TP1 for trading
     */
    public double getCurrentTP1() {
        return currentTP1.get();
    }

    /**
     * Get TP1 calculation details
     */
    public TP1Calculation getTP1Details() {
        double or = openingRange.get();
        double volatilityRatio = (TP1_VOLATILITY_RATIO_MIN + TP1_VOLATILITY_RATIO_MAX) / 2.0;
        double calculatedTP1 = or * volatilityRatio;
        double finalTP1 = currentTP1.get();
        boolean usedNormalization = or > 80.0;

        return new TP1Calculation(or, volatilityRatio, calculatedTP1, finalTP1, usedNormalization);
    }

    /**
     * Force TP1 recalculation (for testing or manual override)
     */
    public synchronized void forceRecalculation() {
        recalculateTP1();
        log.info("🔄 TP1 force recalculated: {:.1f}", currentTP1.get());
    }

    /**
     * Reset normalizer
     */
    public void reset() {
        openingRange.set(0.0);
        currentTP1.set(FIXED_TP1_FALLBACK);
        lastUpdate.set(LocalDateTime.now());
        log.info("🔄 Volatility normalizer reset");
    }

    /**
     * Get statistics
     */
    public java.util.Map<String, Object> getStats() {
        return java.util.Map.of(
            "openingRange", openingRange.get(),
            "currentTP1", currentTP1.get(),
            "lastUpdate", lastUpdate.get().toString(),
            "usingVolatilityNormalization", openingRange.get() > 80.0
        );
    }
}
