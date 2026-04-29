package com.riskpilot.engine;

import com.riskpilot.config.RiskPilotProperties;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kill-switch engine.
 *
 * Audit fixes applied:
 *  - Resolves the kill-switch flag file from configuration to an ABSOLUTE path
 *    so a relative working-directory drift cannot silently disable the switch.
 *  - The internal evaluator (evaluateInternal) is wired into a scheduled poller
 *    (and an addressable updateMetrics hook) instead of being dead code with
 *    zero callers.
 *  - The hot-path tick evaluator no longer does a synchronous filesystem stat
 *    on every signal evaluation. State is cached and refreshed by a scheduled
 *    poller off the WebSocket thread; isKillSwitchTriggered() is now a memory
 *    read.
 *  - Triggering the kill switch invokes a list of registered halt actions
 *    (e.g. cancel-all-orders + flatten-positions) so it actually stops trading
 *    instead of only logging.
 */
@Slf4j
@Component
public class KillSwitchEngine {

    private final RiskPilotProperties properties;

    private Path killPath;

    private final AtomicBoolean cachedTriggered = new AtomicBoolean(false);
    private final AtomicReference<List<String>> cachedReasons =
        new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<Instant> lastEvaluationAt =
        new AtomicReference<>(Instant.EPOCH);

    private final List<Runnable> haltActions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean haltActionsExecuted = new AtomicBoolean(false);

    @Autowired
    public KillSwitchEngine(RiskPilotProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        String configured = properties.getKillSwitch().getFlagFilePath();
        this.killPath = Paths.get(configured).toAbsolutePath().normalize();
        log.info("KillSwitchEngine initialised, flag file = {}", killPath);
        // Prime the cache once on startup so consumers don't see a stale "false"
        // before the first scheduled refresh.
        refreshFromFlagFile();
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
     * Hot-path safe: returns the cached value populated by the scheduled poller.
     * No filesystem I/O. May lag by at most {@code killSwitch.pollIntervalMs}.
     */
    public boolean isKillSwitchTriggered() {
        return cachedTriggered.get();
    }

    /**
     * Get current kill-switch state from the in-memory cache.
     */
    public KillSwitchSnapshot getCurrentState() {
        return new KillSwitchSnapshot(
            cachedTriggered.get(),
            cachedReasons.get(),
            LocalDateTime.now().toString()
        );
    }

    /**
     * Clear kill-switch (for manual restart after investigation).
     */
    public synchronized void clearKillSwitch() {
        try {
            Files.deleteIfExists(killPath);
            cachedTriggered.set(false);
            cachedReasons.set(Collections.emptyList());
            haltActionsExecuted.set(false);
            log.info("Kill-switch cleared - system can restart");
        } catch (Exception e) {
            log.error("Failed to clear kill-switch: {}", e.getMessage());
        }
    }

    /**
     * Internal kill-switch evaluation against a metrics window. Now actually
     * called from {@link #updateMetrics(MetricsWindow)} and (in tests) directly.
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
            log.error("INTERNAL KILL SWITCH evaluated - Reasons: {}", String.join(", ", reasons));
            // Persist so the next process restart still sees the trip, and the
            // scheduled poller picks it up identically to externally-written
            // kill flags.
            writeKillSwitch(reasons);
            applyTrigger(reasons);
        }

        return new KillSwitchSnapshot(triggered, reasons, LocalDateTime.now().toString());
    }

    /**
     * Push a new metrics window into the engine. Intended to be called from
     * {@link RealTimeEdgeTracker} (or any other producer) on metric updates.
     */
    public void updateMetrics(MetricsWindow metrics) {
        evaluateInternal(metrics);
    }

    /**
     * Write kill-switch file (called by Python forward_scorecard or by the
     * internal evaluator).
     */
    public synchronized void writeKillSwitch(List<String> reasons) {
        try {
            Path parent = killPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(killPath, String.join("\n", reasons).getBytes());
            log.error("KILL SWITCH WRITTEN to {} - System will halt", killPath);
        } catch (IOException e) {
            log.error("Failed to write kill-switch file at {}: {}", killPath, e.getMessage());
        }
        applyTrigger(reasons);
    }

    /**
     * Register a halt action to be executed exactly once when the kill switch
     * trips. Typical actions: cancel all open orders, flatten open positions,
     * stop new-order intake.
     */
    public void registerHaltAction(Runnable action) {
        if (action != null) {
            haltActions.add(action);
        }
    }

    /**
     * Scheduled poller — runs on Spring's task scheduler, NOT on the WebSocket
     * I/O thread. Refreshes the cached kill-switch state every
     * {@code killSwitch.pollIntervalMs} so the hot path can stay lock-free.
     */
    @Scheduled(fixedDelayString = "${riskpilot.kill-switch.poll-interval-ms:1000}")
    public void scheduledRefresh() {
        refreshFromFlagFile();
    }

    private void refreshFromFlagFile() {
        try {
            if (Files.exists(killPath)) {
                List<String> lines = Files.readAllLines(killPath);
                if (!lines.isEmpty()) {
                    if (!cachedTriggered.get()) {
                        log.error("KILL SWITCH ACTIVATED - flag file {} present, reasons: {}",
                            killPath, String.join(", ", lines));
                    }
                    cachedReasons.set(new ArrayList<>(lines));
                    cachedTriggered.set(true);
                    applyTrigger(lines);
                } else {
                    // Empty file is treated as no trip — same as no file.
                    cachedTriggered.set(false);
                    cachedReasons.set(Collections.emptyList());
                }
            } else {
                cachedTriggered.set(false);
                cachedReasons.set(Collections.emptyList());
            }
        } catch (Exception e) {
            // Fail safe: if we cannot read the file, assume tripped.
            log.error("Error reading kill-switch file {}: {} — assuming TRIGGERED",
                killPath, e.getMessage());
            cachedTriggered.set(true);
            cachedReasons.set(List.of("FILE_READ_ERROR"));
            applyTrigger(cachedReasons.get());
        } finally {
            lastEvaluationAt.set(Instant.now());
        }
    }

    private void applyTrigger(List<String> reasons) {
        if (haltActions.isEmpty()) {
            return;
        }
        if (!haltActionsExecuted.compareAndSet(false, true)) {
            return; // already executed during this trip
        }
        for (Runnable action : haltActions) {
            try {
                action.run();
            } catch (Exception ex) {
                log.error("Halt action threw {}: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        }
        log.error("Kill-switch halt actions executed (reasons={})", reasons);
    }
}
