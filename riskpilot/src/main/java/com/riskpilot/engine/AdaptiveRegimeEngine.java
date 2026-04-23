package com.riskpilot.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdaptiveRegimeEngine {

    private static final String CONFIG_FILE = "adaptive_regime.json";
    private static final int WINDOW_SIZE = 10;
    private static final int MIN_TRADES_FOR_ADAPTATION = 6;
    private static final double ALPHA = 0.3; // Smoothing factor
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<AdaptiveConfig> currentConfig = new AtomicReference<>();
    private final List<TradeResult> tradeWindow = new ArrayList<>();
    private volatile boolean freezeAdaptation = false;
    private volatile LocalDateTime lastAdaptationTime;

    @Data
    public static class AdaptiveConfig {
        private int minRegimeScore = 4;
        private double minORRange = 120.0;
        private double minATRRatio = 1.10;
        private double minEfficiency = 0.55;
        private double minBreakoutHoldRate = 0.50;
        private LocalDateTime lastUpdated = LocalDateTime.now();
    }

    @Data
    public static class TradeResult {
        private final double realizedR;
        private final boolean tp1Hit;
        private final boolean runnerCaptured;
        private final double entrySlippage;
        private final double runnerSlippage;
        private final SessionFeatures sessionFeatures;
        private final LocalDateTime timestamp;

        public TradeResult(double realizedR, boolean tp1Hit, boolean runnerCaptured,
                          double entrySlippage, double runnerSlippage, SessionFeatures sessionFeatures) {
            this.realizedR = realizedR;
            this.tp1Hit = tp1Hit;
            this.runnerCaptured = runnerCaptured;
            this.entrySlippage = entrySlippage;
            this.runnerSlippage = runnerSlippage;
            this.sessionFeatures = sessionFeatures;
            this.timestamp = LocalDateTime.now();
        }
    }

    @Data
    public static class SessionFeatures {
        private final double orRange;
        private final double atrRatio;
        private final double efficiency;
        private final double breakoutHoldRate;
        private final int regimeScore;

        public SessionFeatures(double orRange, double atrRatio, double efficiency,
                               double breakoutHoldRate, int regimeScore) {
            this.orRange = orRange;
            this.atrRatio = atrRatio;
            this.efficiency = efficiency;
            this.breakoutHoldRate = breakoutHoldRate;
            this.regimeScore = regimeScore;
        }
    }

    // BOUNDS for safety
    private static final Map<String, Object[]> BOUNDS = Map.of(
        "minRegimeScore", new Object[]{3, 6},
        "minORRange", new Object[]{90.0, 180.0},
        "minATRRatio", new Object[]{1.0, 1.5},
        "minEfficiency", new Object[]{0.45, 0.70},
        "minBreakoutHoldRate", new Object[]{0.40, 0.70}
    );

    // STEP sizes for adaptation
    private static final Map<String, Object> STEPS = Map.of(
        "minRegimeScore", 1,        // integer
        "minORRange", 10.0,         // points
        "minATRRatio", 0.05,
        "minEfficiency", 0.03,
        "minBreakoutHoldRate", 0.05
    );

    /**
     * Initialize configuration from file or defaults
     */
    public void initialize() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                AdaptiveConfig loaded = objectMapper.readValue(configFile, AdaptiveConfig.class);
                currentConfig.set(loaded);
                log.info("📊 Loaded adaptive config from file: {}", loaded);
            } else {
                AdaptiveConfig defaults = new AdaptiveConfig();
                currentConfig.set(defaults);
                persistConfig(defaults);
                log.info("📊 Created default adaptive config: {}", defaults);
            }
        } catch (Exception e) {
            log.error("Failed to initialize adaptive config, using defaults", e);
            currentConfig.set(new AdaptiveConfig());
        }
    }

    /**
     * Add trade result to window for adaptation
     */
    public synchronized void addTradeResult(double realizedR, boolean tp1Hit, boolean runnerCaptured,
                                          double entrySlippage, double runnerSlippage, SessionFeatures sessionFeatures) {
        TradeResult result = new TradeResult(realizedR, tp1Hit, runnerCaptured, entrySlippage, runnerSlippage, sessionFeatures);
        
        tradeWindow.add(result);
        if (tradeWindow.size() > WINDOW_SIZE) {
            tradeWindow.remove(0);
        }

        log.debug("📈 Added trade result to adaptive window: R={:.3f}, TP1={}, Runner={}, Window size={}", 
                realizedR, tp1Hit, runnerCaptured, tradeWindow.size());

        // Check if adaptation should run
        if (shouldAdapt()) {
            runAdaptation();
        }
    }

    /**
     * Determine if adaptation should run
     */
    private boolean shouldAdapt() {
        if (freezeAdaptation) {
            return false;
        }

        if (tradeWindow.size() < MIN_TRADES_FOR_ADAPTATION) {
            return false;
        }

        // Max 1 update per day or every 10 trades
        if (lastAdaptationTime != null && 
            lastAdaptationTime.toLocalDate().equals(LocalDateTime.now().toLocalDate())) {
            return false;
        }

        return true;
    }

    /**
     * Run adaptation process
     */
    private void runAdaptation() {
        try {
            String signal = computeAdaptationSignal();
            log.info("🔄 Adaptation signal: {}", signal);

            if (!"HOLD".equals(signal)) {
                AdaptiveConfig oldConfig = currentConfig.get();
                AdaptiveConfig newConfig = adaptParameters(oldConfig, signal);
                AdaptiveConfig smoothedConfig = smoothParameters(oldConfig, newConfig);
                
                currentConfig.set(smoothedConfig);
                persistConfig(smoothedConfig);
                lastAdaptationTime = LocalDateTime.now();

                log.info("🔄 Adapted config: {} -> {}", oldConfig, smoothedConfig);
            }
        } catch (Exception e) {
            log.error("Failed to run adaptation", e);
        }
    }

    /**
     * Compute adaptation signal based on recent performance
     */
    private String computeAdaptationSignal() {
        if (tradeWindow.size() < MIN_TRADES_FOR_ADAPTATION) {
            return "HOLD";
        }

        RobustMetrics metrics = computeRobustMetrics();
        
        // BAD: tighten filters (be more selective)
        if (metrics.expectancy < 0.04 || 
            metrics.tp1Rate < 0.60 || 
            metrics.runnerRate < 0.15 || 
            metrics.avgEntrySlippage > 2.0 || 
            metrics.avgRunnerSlippage > 6.0) {
            return "TIGHTEN";
        }

        // GOOD: cautiously loosen (allow a bit more flow)
        if (metrics.expectancy > 0.08 && 
            metrics.tp1Rate >= 0.65 && 
            metrics.runnerRate >= 0.20 && 
            metrics.avgEntrySlippage <= 2.0) {
            return "LOOSEN";
        }

        return "HOLD";
    }

    @Data
    private static class RobustMetrics {
        private final double expectancy;
        private final double tp1Rate;
        private final double runnerRate;
        private final double avgEntrySlippage;
        private final double avgRunnerSlippage;
    }

    /**
     * Compute robust metrics with tail-aware expectancy
     */
    private RobustMetrics computeRobustMetrics() {
        List<Double> realizedRs = new ArrayList<>();
        int tp1Hits = 0, runnerCaptured = 0;
        List<Double> entrySlippages = new ArrayList<>();
        List<Double> runnerSlippages = new ArrayList<>();

        for (TradeResult trade : tradeWindow) {
            realizedRs.add(trade.realizedR);
            if (trade.tp1Hit) tp1Hits++;
            if (trade.runnerCaptured) runnerCaptured++;
            entrySlippages.add(trade.entrySlippage);
            runnerSlippages.add(trade.runnerSlippage);
        }

        // Tail-aware expectancy: remove top 1-2 trades
        List<Double> sortedRs = new ArrayList<>(realizedRs);
        Collections.sort(sortedRs);
        List<Double> coreRs = sortedRs.size() >= 8 ? 
                sortedRs.subList(0, sortedRs.size() - 2) : sortedRs;
        
        double expectancy = coreRs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double tp1Rate = (double) tp1Hits / tradeWindow.size();
        double runnerRate = (double) runnerCaptured / tradeWindow.size();
        double avgEntrySlippage = median(entrySlippages);
        double avgRunnerSlippage = median(runnerSlippages);

        return new RobustMetrics(expectancy, tp1Rate, runnerRate, avgEntrySlippage, avgRunnerSlippage);
    }

    /**
     * Compute median value
     */
    private double median(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 0 ? (sorted.get(n/2 - 1) + sorted.get(n/2)) / 2.0 : sorted.get(n/2);
    }

    /**
     * Adapt parameters based on signal
     */
    private AdaptiveConfig adaptParameters(AdaptiveConfig config, String signal) {
        AdaptiveConfig newConfig = new AdaptiveConfig();
        newConfig.setMinRegimeScore(config.getMinRegimeScore());
        newConfig.setMinORRange(config.getMinORRange());
        newConfig.setMinATRRatio(config.getMinATRRatio());
        newConfig.setMinEfficiency(config.getMinEfficiency());
        newConfig.setMinBreakoutHoldRate(config.getMinBreakoutHoldRate());

        int direction = "TIGHTEN".equals(signal) ? 1 : -1;

        // Apply bounded steps
        newConfig.setMinRegimeScore(clamp(config.getMinRegimeScore() + direction * (Integer) STEPS.get("minRegimeScore"), 
                                       (Integer) BOUNDS.get("minRegimeScore")[0], (Integer) BOUNDS.get("minRegimeScore")[1]));
        newConfig.setMinORRange(clamp(config.getMinORRange() + direction * (Double) STEPS.get("minORRange"), 
                                    (Double) BOUNDS.get("minORRange")[0], (Double) BOUNDS.get("minORRange")[1]));
        newConfig.setMinATRRatio(clamp(config.getMinATRRatio() + direction * (Double) STEPS.get("minATRRatio"), 
                                      (Double) BOUNDS.get("minATRRatio")[0], (Double) BOUNDS.get("minATRRatio")[1]));
        newConfig.setMinEfficiency(clamp(config.getMinEfficiency() + direction * (Double) STEPS.get("minEfficiency"), 
                                      (Double) BOUNDS.get("minEfficiency")[0], (Double) BOUNDS.get("minEfficiency")[1]));
        newConfig.setMinBreakoutHoldRate(clamp(config.getMinBreakoutHoldRate() + direction * (Double) STEPS.get("minBreakoutHoldRate"), 
                                            (Double) BOUNDS.get("minBreakoutHoldRate")[0], (Double) BOUNDS.get("minBreakoutHoldRate")[1]));

        return newConfig;
    }

    /**
     * Apply smoothing to avoid flip-flopping
     */
    private AdaptiveConfig smoothParameters(AdaptiveConfig oldConfig, AdaptiveConfig newConfig) {
        AdaptiveConfig smoothed = new AdaptiveConfig();
        
        smoothed.setMinRegimeScore((int) Math.round((1 - ALPHA) * oldConfig.getMinRegimeScore() + ALPHA * newConfig.getMinRegimeScore()));
        smoothed.setMinORRange((1 - ALPHA) * oldConfig.getMinORRange() + ALPHA * newConfig.getMinORRange());
        smoothed.setMinATRRatio((1 - ALPHA) * oldConfig.getMinATRRatio() + ALPHA * newConfig.getMinATRRatio());
        smoothed.setMinEfficiency((1 - ALPHA) * oldConfig.getMinEfficiency() + ALPHA * newConfig.getMinEfficiency());
        smoothed.setMinBreakoutHoldRate((1 - ALPHA) * oldConfig.getMinBreakoutHoldRate() + ALPHA * newConfig.getMinBreakoutHoldRate());
        smoothed.setLastUpdated(LocalDateTime.now());

        return smoothed;
    }

    /**
     * Clamp value within bounds
     */
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Persist configuration to file
     */
    private void persistConfig(AdaptiveConfig config) {
        try {
            objectMapper.writeValue(new File(CONFIG_FILE), config);
            log.debug("💾 Persisted adaptive config to file");
        } catch (Exception e) {
            log.error("Failed to persist adaptive config", e);
        }
    }

    /**
     * Get current adaptive configuration
     */
    public AdaptiveConfig getCurrentConfig() {
        return currentConfig.get();
    }

    /**
     * Freeze adaptation (e.g., on kill-switch trigger)
     */
    public void freezeAdaptation() {
        freezeAdaptation = true;
        log.warn("🔒 Adaptation frozen due to kill-switch trigger");
    }

    /**
     * Unfreeze adaptation
     */
    public void unfreezeAdaptation() {
        freezeAdaptation = false;
        log.info("🔓 Adaptation unfrozen");
    }

    /**
     * Get adaptation statistics
     */
    public Map<String, Object> getStats() {
        AdaptiveConfig config = currentConfig.get();
        return Map.of(
            "currentConfig", config,
            "windowSize", tradeWindow.size(),
            "freezeAdaptation", freezeAdaptation,
            "lastAdaptationTime", lastAdaptationTime,
            "readyToAdapt", shouldAdapt()
        );
    }

    /**
     * Reset adaptation engine
     */
    public void reset() {
        tradeWindow.clear();
        freezeAdaptation = false;
        lastAdaptationTime = null;
        currentConfig.set(new AdaptiveConfig());
        log.info("🔄 Adaptive regime engine reset");
    }
}
