package com.riskpilot.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class VolatilityNormalizer {

    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 45);
    private static final double TP1_VOLATILITY_RATIO_MIN = 0.10;
    private static final double TP1_VOLATILITY_RATIO_MAX = 0.15;
    private static final double FIXED_TP1_FALLBACK = 15.0;

    private final AtomicReference<Double> openingHigh = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> openingLow = new AtomicReference<>(Double.NaN);
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

        public TP1Calculation(
            double openingRange,
            double volatilityRatio,
            double calculatedTP1,
            double finalTP1,
            boolean usedVolatilityNormalization
        ) {
            this.openingRange = openingRange;
            this.volatilityRatio = volatilityRatio;
            this.calculatedTP1 = calculatedTP1;
            this.finalTP1 = finalTP1;
            this.usedVolatilityNormalization = usedVolatilityNormalization;
            this.timestamp = LocalDateTime.now();
        }
    }

    public synchronized void updateOpeningRange(double high, double low, LocalDateTime timestamp) {
        if (timestamp.toLocalTime().isAfter(OPENING_RANGE_END)) {
            return;
        }

        double currentHigh = openingHigh.get();
        double currentLow = openingLow.get();
        double updatedHigh = Double.isNaN(currentHigh) ? high : Math.max(currentHigh, high);
        double updatedLow = Double.isNaN(currentLow) ? low : Math.min(currentLow, low);

        openingHigh.set(updatedHigh);
        openingLow.set(updatedLow);
        openingRange.set(Math.max(0.0, updatedHigh - updatedLow));
        recalculateTP1();
        lastUpdate.set(LocalDateTime.now());
    }

    private void recalculateTP1() {
        double orRange = openingRange.get();
        if (orRange <= 0.0) {
            currentTP1.set(FIXED_TP1_FALLBACK);
            return;
        }

        double volatilityRatio = (TP1_VOLATILITY_RATIO_MIN + TP1_VOLATILITY_RATIO_MAX) / 2.0;
        double calculatedTP1 = orRange * volatilityRatio;
        double finalTP1 = Math.max(12.0, Math.min(calculatedTP1, orRange * TP1_VOLATILITY_RATIO_MAX));
        currentTP1.set(orRange > 80.0 ? finalTP1 : FIXED_TP1_FALLBACK);
    }

    public double getCurrentTP1() {
        return currentTP1.get();
    }

    public TP1Calculation getTP1Details() {
        double orRange = openingRange.get();
        double volatilityRatio = (TP1_VOLATILITY_RATIO_MIN + TP1_VOLATILITY_RATIO_MAX) / 2.0;
        return new TP1Calculation(
            orRange,
            volatilityRatio,
            orRange * volatilityRatio,
            currentTP1.get(),
            orRange > 80.0
        );
    }

    public synchronized void forceRecalculation() {
        recalculateTP1();
        lastUpdate.set(LocalDateTime.now());
    }

    public void reset() {
        openingHigh.set(Double.NaN);
        openingLow.set(Double.NaN);
        openingRange.set(0.0);
        currentTP1.set(FIXED_TP1_FALLBACK);
        lastUpdate.set(LocalDateTime.now());
    }

    public Map<String, Object> getStats() {
        return Map.of(
            "openingRange", openingRange.get(),
            "currentTP1", currentTP1.get(),
            "lastUpdate", lastUpdate.get().toString(),
            "usingVolatilityNormalization", openingRange.get() > 80.0
        );
    }
}
