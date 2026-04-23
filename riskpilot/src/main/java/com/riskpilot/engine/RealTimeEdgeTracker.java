package com.riskpilot.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class RealTimeEdgeTracker {

    private static final int WINDOW_SIZE = 8;
    private static final int INTRADAY_WINDOW = 5;
    private static final int DECAY_THRESHOLD = 4;
    private static final double EXPECTANCY_MIN = 0.05;
    private static final double TP1_RATE_MIN = 0.6;
    private static final double RUNNER_RATE_MIN = 0.15;
    private static final double ENTRY_SLIPPAGE_MAX = 2.0;
    private static final double TAIL_CONTRUTION_MAX = 0.8;

    private final Queue<TradeResult> rollingWindow = new ConcurrentLinkedQueue<>();
    private final Queue<TradeResult> intradayWindow = new ConcurrentLinkedQueue<>();
    private final AtomicInteger totalTrades = new AtomicInteger(0);
    private final AtomicReference<EdgeMetrics> currentMetrics = new AtomicReference<>();
    private final AtomicReference<Instant> lastUpdate = new AtomicReference<>(Instant.now());

    @Data
    public static class TradeResult {
        private final double realizedR;
        private final boolean tp1Hit;
        private final boolean runnerCaptured;
        private final double entrySlippage;
        private final double runnerSlippage;
        private final Instant timestamp;

        public TradeResult(double realizedR, boolean tp1Hit, boolean runnerCaptured,
                          double entrySlippage, double runnerSlippage) {
            this.realizedR = realizedR;
            this.tp1Hit = tp1Hit;
            this.runnerCaptured = runnerCaptured;
            this.entrySlippage = entrySlippage;
            this.runnerSlippage = runnerSlippage;
            this.timestamp = Instant.now();
        }
    }

    @Data
    public static class EdgeMetrics {
        private final double expectancy;
        private final double tp1Rate;
        private final double runnerRate;
        private final double avgEntrySlippage;
        private final double avgRunnerSlippage;
        private final double tailContribution;
        private final int decayScore;
        private final Instant timestamp;
        private final int windowSize;

        public EdgeMetrics(double expectancy, double tp1Rate, double runnerRate,
                          double avgEntrySlippage, double avgRunnerSlippage,
                          double tailContribution, int decayScore, int windowSize) {
            this.expectancy = expectancy;
            this.tp1Rate = tp1Rate;
            this.runnerRate = runnerRate;
            this.avgEntrySlippage = avgEntrySlippage;
            this.avgRunnerSlippage = avgRunnerSlippage;
            this.tailContribution = tailContribution;
            this.decayScore = decayScore;
            this.timestamp = Instant.now();
            this.windowSize = windowSize;
        }
    }

    /**
     * Add new trade result and update edge metrics in real-time
     */
    public synchronized void addTradeResult(double realizedR, boolean tp1Hit, boolean runnerCaptured,
                                          double entrySlippage, double runnerSlippage) {
        TradeResult result = new TradeResult(realizedR, tp1Hit, runnerCaptured, entrySlippage, runnerSlippage);
        
        // Update rolling window
        rollingWindow.offer(result);
        if (rollingWindow.size() > WINDOW_SIZE) {
            rollingWindow.poll();
        }

        // Update intraday window
        intradayWindow.offer(result);
        if (intradayWindow.size() > INTRADAY_WINDOW) {
            intradayWindow.poll();
        }

        totalTrades.incrementAndGet();
        lastUpdate.set(Instant.now());

        // Compute and update metrics
        EdgeMetrics metrics = computeMetrics();
        currentMetrics.set(metrics);

        log.debug("📊 Edge metrics updated: Expectancy={:.3f}, TP1={:.2f}, Runner={:.2f}, DecayScore={}", 
                metrics.getExpectancy(), metrics.getTp1Rate(), metrics.getRunnerRate(), metrics.getDecayScore());

        // Check for immediate kill conditions
        checkImmediateKillConditions(metrics);
    }

    /**
     * Compute edge metrics from current window
     */
    private EdgeMetrics computeMetrics() {
        List<TradeResult> trades = new ArrayList<>(rollingWindow);
        if (trades.isEmpty()) {
            return new EdgeMetrics(0, 0, 0, 0, 0, 0, 0, 0);
        }

        // Basic metrics
        double expectancy = trades.stream().mapToDouble(TradeResult::getRealizedR).average().orElse(0);
        double tp1Rate = trades.stream().mapToDouble(t -> t.isTp1Hit() ? 1 : 0).average().orElse(0);
        double runnerRate = trades.stream().mapToDouble(t -> t.isRunnerCaptured() ? 1 : 0).average().orElse(0);
        double avgEntrySlippage = trades.stream().mapToDouble(TradeResult::getEntrySlippage).average().orElse(0);
        double avgRunnerSlippage = trades.stream().mapToDouble(TradeResult::getRunnerSlippage).average().orElse(0);

        // Tail contribution (critical metric)
        double tailContribution = computeTailContribution(trades);

        // Decay score
        int decayScore = computeDecayScore(expectancy, tp1Rate, runnerRate, avgEntrySlippage, tailContribution);

        return new EdgeMetrics(expectancy, tp1Rate, runnerRate, avgEntrySlippage, avgRunnerSlippage,
                              tailContribution, decayScore, trades.size());
    }

    /**
     * Compute tail contribution - how much edge depends on best trades
     */
    private double computeTailContribution(List<TradeResult> trades) {
        if (trades.size() < 2) return 0.0;

        List<Double> sortedPnL = trades.stream()
                .map(TradeResult::getRealizedR)
                .sorted(Comparator.reverseOrder())
                .toList();

        double total = sortedPnL.stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return 0.0;

        double top2 = sortedPnL.stream().limit(2).mapToDouble(Double::doubleValue).sum();
        return top2 / total;
    }

    /**
     * Compute edge decay score
     */
    private int computeDecayScore(double expectancy, double tp1Rate, double runnerRate,
                                double avgEntrySlippage, double tailContribution) {
        int score = 0;

        if (expectancy < EXPECTANCY_MIN) score += 2;
        if (tp1Rate < TP1_RATE_MIN) score += 1;
        if (runnerRate < RUNNER_RATE_MIN) score += 2;
        if (avgEntrySlippage > ENTRY_SLIPPAGE_MAX) score += 1;
        if (tailContribution > TAIL_CONTRUTION_MAX) score += 2;

        return score;
    }

    /**
     * Check for immediate kill conditions
     */
    private void checkImmediateKillConditions(EdgeMetrics metrics) {
        List<String> killReasons = new ArrayList<>();

        // Immediate failures
        if (metrics.getExpectancy() < 0.04) {
            killReasons.add("EXPECTANCY_BREAKDOWN");
        }

        // Edge decay
        if (metrics.getDecayScore() >= DECAY_THRESHOLD) {
            killReasons.add("EDGE_DECAY");
        }

        // Tail failure
        if (metrics.getRunnerRate() < RUNNER_RATE_MIN) {
            killReasons.add("RUNNER_COLLAPSE");
        }

        // Intraday loss cluster
        if (hasIntradayLossCluster()) {
            killReasons.add("LOSS_CLUSTER");
        }

        if (!killReasons.isEmpty()) {
            triggerKillSwitch(killReasons);
        }
    }

    /**
     * Check for intraday loss cluster (last 5 trades)
     */
    private boolean hasIntradayLossCluster() {
        List<TradeResult> last5 = new ArrayList<>(intradayWindow);
        if (last5.size() < 5) return false;

        long losses = last5.stream().mapToLong(t -> t.getRealizedR() < 0 ? 1 : 0).sum();
        return losses >= 4;
    }

    /**
     * Trigger kill switch immediately
     */
    private void triggerKillSwitch(List<String> reasons) {
        log.error("🚨 REAL-TIME EDGE KILL SWITCH: {}", String.join(", ", reasons));
        
        // Write kill flag file
        try {
            java.nio.file.Files.write(
                java.nio.file.Paths.get("KILL_SWITCH.flag"),
                String.join("\n", reasons).getBytes()
            );
        } catch (Exception e) {
            log.error("Failed to write kill switch file: {}", e.getMessage());
        }
    }

    /**
     * Get current edge metrics
     */
    public EdgeMetrics getCurrentMetrics() {
        return currentMetrics.get();
    }

    /**
     * Check if edge is healthy
     */
    public boolean isEdgeHealthy() {
        EdgeMetrics metrics = currentMetrics.get();
        return metrics != null && metrics.getDecayScore() < DECAY_THRESHOLD;
    }

    /**
     * Get edge statistics
     */
    public Map<String, Object> getStats() {
        EdgeMetrics metrics = currentMetrics.get();
        return Map.of(
            "totalTrades", totalTrades.get(),
            "windowSize", metrics != null ? metrics.getWindowSize() : 0,
            "expectancy", metrics != null ? metrics.getExpectancy() : 0.0,
            "tp1Rate", metrics != null ? metrics.getTp1Rate() : 0.0,
            "runnerRate", metrics != null ? metrics.getRunnerRate() : 0.0,
            "decayScore", metrics != null ? metrics.getDecayScore() : 0,
            "tailContribution", metrics != null ? metrics.getTailContribution() : 0.0,
            "lastUpdate", lastUpdate.get().toString(),
            "edgeHealthy", isEdgeHealthy()
        );
    }

    /**
     * Reset tracker
     */
    public void reset() {
        rollingWindow.clear();
        intradayWindow.clear();
        totalTrades.set(0);
        currentMetrics.set(null);
        lastUpdate.set(Instant.now());
        log.info("🔄 Edge tracker reset");
    }
}
