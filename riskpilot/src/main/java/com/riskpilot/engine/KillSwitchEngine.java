package com.riskpilot.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * PRODUCTION Kill Switch Engine — DB-persisted, restart-proof, externally controllable.
 *
 * BEFORE (critical bug):
 * Kill switch stored only in a local file (KILL_SWITCH.flag).
 * Render free tier containers are EPHEMERAL — every restart wipes the filesystem.
 * A kill-switch triggered during a crash IS LOST on restart → trading resumes unsafely.
 *
 * AFTER:
 * PRIMARY store: PostgreSQL kill_switch_log table (survives restarts).
 * SECONDARY: in-memory volatile flag (fast path, no DB hit on every tick).
 * TERTIARY: file fallback when DB is unreachable.
 * On startup: reads DB to restore state before any trading starts.
 */
@Slf4j
@Component
public class KillSwitchEngine {

    private static final long KILL_SWITCH_CACHE_TTL_MS = 2_000L;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${KILL_SWITCH_PATH:KILL_SWITCH.flag}")
    private String killSwitchPath;

    private final JdbcTemplate jdbcTemplate;

    private volatile boolean killSwitchActive = false;
    private volatile long lastDbCheckMs = 0L;
    private volatile List<String> activeReasons = List.of();

    public KillSwitchEngine(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * On startup: restore kill switch state from DB so a container restart
     * never silently re-enables trading after a kill-switch event.
     */
    @PostConstruct
    public void restoreFromDatabase() {
        try {
            List<String> reasons = jdbcTemplate.queryForList(
                "SELECT reason FROM kill_switch_log WHERE cleared_at IS NULL " +
                "ORDER BY triggered_at DESC LIMIT 20",
                String.class
            );
            if (!reasons.isEmpty()) {
                killSwitchActive = true;
                activeReasons = List.copyOf(reasons);
                log.error("🚨 KILL SWITCH RESTORED FROM DB on startup — {} active: {}",
                    reasons.size(), String.join(", ", reasons));
            } else {
                killSwitchActive = false;
                log.info("✅ Kill switch: no active entries in DB — system clear");
            }
        } catch (DataAccessException e) {
            log.warn("DB unavailable during kill switch restore — checking file fallback: {}", e.getMessage());
            killSwitchActive = readKillSwitchFile();
            if (killSwitchActive) {
                log.error("🚨 KILL SWITCH ACTIVE (from file fallback)");
            }
        }
    }

    public boolean isKillSwitchTriggered() {
        long now = System.currentTimeMillis();
        if (now - lastDbCheckMs < KILL_SWITCH_CACHE_TTL_MS) {
            return killSwitchActive;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - lastDbCheckMs < KILL_SWITCH_CACHE_TTL_MS) {
                return killSwitchActive;
            }
            refreshFromDb();
            lastDbCheckMs = System.currentTimeMillis();
        }
        return killSwitchActive;
    }

    public KillSwitchSnapshot getCurrentState() {
        return new KillSwitchSnapshot(
            killSwitchActive,
            new ArrayList<>(activeReasons),
            LocalDateTime.now(IST).toString()
        );
    }

    public void writeKillSwitch(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return;
        killSwitchActive = true;
        activeReasons = List.copyOf(reasons);
        log.error("🚨 KILL SWITCH ACTIVATED — {}", String.join(", ", reasons));
        persistToDb(reasons);
        writeKillSwitchFile(reasons);
    }

    public void clearKillSwitch() {
        try {
            jdbcTemplate.update(
                "UPDATE kill_switch_log SET cleared_at = ? WHERE cleared_at IS NULL",
                java.sql.Timestamp.from(Instant.now())
            );
        } catch (DataAccessException e) {
            log.error("Failed to clear kill switch in DB: {}", e.getMessage());
        }
        try {
            Files.deleteIfExists(Paths.get(killSwitchPath));
        } catch (Exception e) {
            log.warn("Failed to delete kill switch file: {}", e.getMessage());
        }
        killSwitchActive = false;
        activeReasons = List.of();
        lastDbCheckMs = System.currentTimeMillis();
        log.info("✅ Kill switch cleared — trading can resume after manual inspection");
    }

    public KillSwitchSnapshot evaluateInternal(MetricsWindow metrics) {
        List<String> reasons = new ArrayList<>();
        if (metrics.expectancy < 0.0)              reasons.add("EXPECTANCY_NEGATIVE");
        else if (metrics.expectancy < 0.04)        reasons.add("EXPECTANCY_DEGRADING");
        if (metrics.medianEntrySlippage > 3.0)     reasons.add("ENTRY_SLIPPAGE_CRITICAL");
        else if (metrics.medianEntrySlippage > 2.5) reasons.add("ENTRY_SLIPPAGE_HIGH");
        if (metrics.medianRunnerSlippage > 7.0)    reasons.add("RUNNER_SLIPPAGE_CRITICAL");
        if (metrics.runnerRate < 0.10)             reasons.add("RUNNER_FAILURE");
        else if (metrics.runnerRate < 0.15)        reasons.add("RUNNER_WEAK");
        if (metrics.lossStreak >= 7)               reasons.add("LOSS_STREAK_CRITICAL");
        else if (metrics.lossStreak >= 5)          reasons.add("LOSS_STREAK_WARNING");
        if (metrics.heartbeatPanics > 1)           reasons.add("HEARTBEAT_FAILURE");
        if (metrics.missingTickSessions > 3)       reasons.add("FEED_UNSTABLE");

        boolean triggered = !reasons.isEmpty();
        if (triggered) {
            log.error("🚨 INTERNAL KILL SWITCH — {}", String.join(", ", reasons));
            writeKillSwitch(reasons);
        }
        return new KillSwitchSnapshot(triggered, reasons, LocalDateTime.now(IST).toString());
    }

    private void refreshFromDb() {
        try {
            List<String> reasons = jdbcTemplate.queryForList(
                "SELECT reason FROM kill_switch_log WHERE cleared_at IS NULL ORDER BY triggered_at DESC LIMIT 20",
                String.class
            );
            killSwitchActive = !reasons.isEmpty();
            activeReasons = reasons.isEmpty() ? List.of() : List.copyOf(reasons);
        } catch (DataAccessException e) {
            log.warn("DB kill switch refresh failed — retaining cached state: {}", e.getMessage());
        }
    }

    private void persistToDb(List<String> reasons) {
        try {
            for (String reason : reasons) {
                jdbcTemplate.update(
                    "INSERT INTO kill_switch_log (reason, triggered_at) VALUES (?, ?)",
                    reason, java.sql.Timestamp.from(Instant.now())
                );
            }
        } catch (DataAccessException e) {
            log.error("Failed to persist kill switch to DB: {}", e.getMessage());
        }
    }

    private void writeKillSwitchFile(List<String> reasons) {
        try {
            Path path = Paths.get(killSwitchPath);
            Files.write(path, String.join("\n", reasons).getBytes(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);
        } catch (Exception e) {
            log.error("Failed to write kill switch file: {}", e.getMessage());
        }
    }

    private boolean readKillSwitchFile() {
        Path path = Paths.get(killSwitchPath);
        if (!Files.exists(path)) return false;
        try {
            List<String> lines = Files.readAllLines(path);
            if (!lines.isEmpty()) {
                activeReasons = List.copyOf(lines);
                return true;
            }
        } catch (Exception e) {
            log.warn("Kill switch file unreadable: {}", e.getMessage());
        }
        return false;
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
}
