package com.riskpilot.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class KillSwitchEngine {
    private static final long KILL_SWITCH_CACHE_TTL_MS = 2_000L;

    // BUG-025: Configurable kill-switch path
    @Value("${KILL_SWITCH_PATH:KILL_SWITCH.flag}")
    private String killSwitchPath;
    private volatile long lastKillSwitchCheckMs = 0L;
    private volatile boolean cachedKillSwitchTriggered = false;

    private Path getKillPath() {
        return Paths.get(killSwitchPath);
    }

    @Data
    public static class KillSwitchSnapshot {
        private final boolean triggered;
        private final List<String> reasons;
        private final String timestamp;

        public KillSwitchSnapshot(boolean triggered, List<String> reasons, String timestamp) {
            this.triggered = triggered;
            this.reasons = new ArrayList<>(reasons);
            this.timestamp = timestamp;
        }
    }

    @Data
    public static class MetricsWindow {
        private final double expectancy;
        private final double runnerRate;
        private final double medianEntrySlippage;
        private final double medianRunnerSlippage;
        private final int lossStreak;
        private final int heartbeatPanics;
        private final int missingTickSessions;

        public MetricsWindow(double expectancy, double runnerRate, double medianEntrySlippage, 
                           double medianRunnerSlippage, int lossStreak, int heartbeatPanics, 
                           int missingTickSessions) {
            this.expectancy = expectancy;
            this.runnerRate = runnerRate;
            this.medianEntrySlippage = medianEntrySlippage;
            this.medianRunnerSlippage = medianRunnerSlippage;
            this.lossStreak = lossStreak;
            this.heartbeatPanics = heartbeatPanics;
            this.missingTickSessions = missingTickSessions;
        }
    }

    /**
     * Check if kill-switch is triggered by external system.
     * BUG-025: Uses configurable path from KILL_SWITCH_PATH env var.
     */
    public boolean isKillSwitchTriggered() {
        long now = System.currentTimeMillis();
        if (now - lastKillSwitchCheckMs < KILL_SWITCH_CACHE_TTL_MS) {
            return cachedKillSwitchTriggered;
        }

        synchronized (this) {
            now = System.currentTimeMillis();
            if (now - lastKillSwitchCheckMs < KILL_SWITCH_CACHE_TTL_MS) {
                return cachedKillSwitchTriggered;
            }
            cachedKillSwitchTriggered = readKillSwitchFile();
            lastKillSwitchCheckMs = now;
            return cachedKillSwitchTriggered;
        }
    }

    private boolean readKillSwitchFile() {
        Path killPath = getKillPath();
        if (Files.exists(killPath)) {
            try {
                List<String> lines = Files.readAllLines(killPath);
                if (!lines.isEmpty()) {
                    log.error("🚨 KILL SWITCH ACTIVATED - Reasons: {}", String.join(", ", lines));
                    return true;
                }
            } catch (Exception e) {
                log.error("Error reading kill-switch file: {}", e.getMessage());
                return true; // Fail safe - if we can't read, assume killed
            }
        }
        return false;
    }

    /**
     * Get current kill-switch state.
     * BUG-025: Uses configurable path from KILL_SWITCH_PATH env var.
     */
    public KillSwitchSnapshot getCurrentState() {
        if (isKillSwitchTriggered()) {
            try {
                List<String> lines = Files.readAllLines(getKillPath());
                return new KillSwitchSnapshot(true, lines, java.time.LocalDateTime.now().toString());
            } catch (Exception e) {
                return new KillSwitchSnapshot(true, List.of("FILE_READ_ERROR"), java.time.LocalDateTime.now().toString());
            }
        }
        return new KillSwitchSnapshot(false, List.of(), java.time.LocalDateTime.now().toString());
    }

    /**
     * Clear kill-switch (for manual restart after investigation).
     * BUG-025: Uses configurable path from KILL_SWITCH_PATH env var.
     */
    public void clearKillSwitch() {
        try {
            Files.deleteIfExists(getKillPath());
            cachedKillSwitchTriggered = false;
            lastKillSwitchCheckMs = System.currentTimeMillis();
            log.info("✅ Kill-switch cleared - system can restart");
        } catch (Exception e) {
            log.error("Failed to clear kill-switch: {}", e.getMessage());
        }
    }

    /**
     * Internal kill-switch evaluation (for Java-level monitoring)
     */
    public KillSwitchSnapshot evaluateInternal(MetricsWindow metrics) {
        List<String> reasons = new ArrayList<>();

        // EDGE COLLAPSE
        if (metrics.expectancy < 0.0) {
            reasons.add("EXPECTANCY_NEGATIVE");
        } else if (metrics.expectancy < 0.04) {
            reasons.add("EXPECTANCY_DEGRADING");
        }

        // EXECUTION FAILURE
        if (metrics.medianEntrySlippage > 3.0) {
            reasons.add("ENTRY_SLIPPAGE_CRITICAL");
        } else if (metrics.medianEntrySlippage > 2.5) {
            reasons.add("ENTRY_SLIPPAGE_HIGH");
        }

        if (metrics.medianRunnerSlippage > 7.0) {
            reasons.add("RUNNER_SLIPPAGE_CRITICAL");
        }

        // STRUCTURAL FAILURE
        if (metrics.runnerRate < 0.10) {
            reasons.add("RUNNER_FAILURE");
        } else if (metrics.runnerRate < 0.15) {
            reasons.add("RUNNER_WEAK");
        }

        // RISK BREACH
        if (metrics.lossStreak >= 7) {
            reasons.add("LOSS_STREAK_CRITICAL");
        } else if (metrics.lossStreak >= 5) {
            reasons.add("LOSS_STREAK_WARNING");
        }

        // INFRA FAILURE
        if (metrics.heartbeatPanics > 1) {
            reasons.add("HEARTBEAT_FAILURE");
        }

        if (metrics.missingTickSessions > 3) {
            reasons.add("FEED_UNSTABLE");
        }

        boolean triggered = !reasons.isEmpty();
        if (triggered) {
            log.error("🚨 INTERNAL KILL SWITCH - Reasons: {}", String.join(", ", reasons));
        }

        return new KillSwitchSnapshot(triggered, reasons, java.time.LocalDateTime.now().toString());
    }

    /**
     * Write kill-switch file.
     * BUG-025: Uses configurable path from KILL_SWITCH_PATH env var.
     * BUG-037: Uses SYNC option for fsync to disk.
     */
    public void writeKillSwitch(List<String> reasons) {
        try {
            Path killPath = getKillPath();
            // BUG-037: Files.write with SYNC option for durability
            Files.write(killPath, String.join("\n", reasons).getBytes(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);
            cachedKillSwitchTriggered = true;
            lastKillSwitchCheckMs = System.currentTimeMillis();
            log.error("🚨 KILL SWITCH WRITTEN - System will halt");
        } catch (Exception e) {
            log.error("Failed to write kill-switch file: {}", e.getMessage());
        }
    }
}
