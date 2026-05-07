package com.riskpilot.controller;

import com.riskpilot.model.Trade;
import com.riskpilot.model.TradingSignal;
import com.riskpilot.service.ShadowExecutionEngine;
import com.riskpilot.service.TradingSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PRODUCTION Trading Controller.
 *
 * BEFORE problems:
 * 1. @CrossOrigin(origins = "*") — overrides SecurityConfig CORS; any site could call these APIs.
 *    REMOVED entirely. CORS is now controlled exclusively by SecurityConfig.
 *
 * 2. POST /engine/restart protected in SecurityConfig BUT SecurityConfig had
 *    ".requestMatchers("/api/v1/**").permitAll()" evaluated FIRST → permAll won.
 *    FIX: @PreAuthorize at method level as defense-in-depth (two security layers).
 *
 * 3. restartEngine() had no kill-switch guard → ADMIN could restart while kill switch active.
 *    FIX: Hard block on restart if kill switch is active. Must clear first.
 *
 * 4. New endpoints: /kill-switch/state (GET) and /kill-switch/clear (POST) for ops team.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/trading")
@RequiredArgsConstructor
@Validated
// REMOVED: @CrossOrigin(origins = "*")
public class TradingController {

    private final ShadowExecutionEngine shadowExecutionEngine;
    private final TradingSessionService tradingSessionService;
    private final com.riskpilot.engine.KillSwitchEngine killSwitchEngine;

    // ---- Public read-only endpoints ----

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getTradingStatus() {
        return ResponseEntity.ok(Map.of(
            "status", "ACTIVE",
            "timestamp", LocalDateTime.now(),
            "engine", "SHADOW_EXECUTION",
            "killSwitchActive", killSwitchEngine.isKillSwitchTriggered(),
            "message", "RiskPilot trading engine is running"
        ));
    }

    @GetMapping("/sessions/current")
    public ResponseEntity<Object> getCurrentSession(@RequestParam @NotBlank String symbol) {
        var session = tradingSessionService.getCurrentSession(symbol);
        if (session == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(session);
    }

    @GetMapping("/trades/active")
    public ResponseEntity<List<Trade>> getActiveTrades(@RequestParam @NotBlank String symbol) {
        return ResponseEntity.ok(tradingSessionService.getActiveTrades(symbol));
    }

    @GetMapping("/signals/recent")
    public ResponseEntity<List<TradingSignal>> getRecentSignals(
            @RequestParam @NotBlank String symbol,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(tradingSessionService.getRecentSignals(symbol, limit));
    }

    @GetMapping("/trades/history")
    public ResponseEntity<List<Trade>> getTradeHistory(
            @RequestParam @NotBlank String symbol,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        return ResponseEntity.ok(tradingSessionService.getTradeHistory(symbol, startDate, endDate));
    }

    @GetMapping("/metrics/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics(
            @RequestParam @NotBlank String symbol,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(tradingSessionService.getPerformanceMetrics(symbol, days));
    }

    // ---- ADMIN-ONLY write operations ----

    @PostMapping("/signals/manual")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createManualSignal(@Valid @RequestBody TradingSignal signal) {
        if (killSwitchEngine.isKillSwitchTriggered()) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "REJECTED",
                "reason", "KILL_SWITCH_ACTIVE",
                "killSwitchState", killSwitchEngine.getCurrentState()
            ));
        }
        log.info("Admin creating manual signal: {}", signal);
        tradingSessionService.processManualSignal(signal);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Manual signal created successfully");
        response.put("signalId", signal.getId());
        response.put("timestamp", LocalDateTime.now());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/trades/{tradeId}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> closeTrade(
            @PathVariable Long tradeId,
            @RequestParam(required = false) String reason) {
        log.info("Admin closing trade {} reason={}", tradeId, reason);
        tradingSessionService.closeTrade(tradeId, reason);
        return ResponseEntity.ok(Map.of(
            "status", "SUCCESS",
            "message", "Trade closed successfully",
            "tradeId", tradeId,
            "timestamp", LocalDateTime.now()
        ));
    }

    @PostMapping("/engine/restart")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> restartEngine() {
        // Hard guard: refuse restart while kill switch is active
        if (killSwitchEngine.isKillSwitchTriggered()) {
            log.error("Engine restart BLOCKED — kill switch active: {}",
                killSwitchEngine.getCurrentState().getReasons());
            return ResponseEntity.status(503).body(Map.of(
                "status", "REJECTED",
                "reason", "KILL_SWITCH_ACTIVE",
                "message", "Clear the kill switch before restarting the engine",
                "killSwitchState", killSwitchEngine.getCurrentState()
            ));
        }
        log.warn("Admin restarting trading engine");
        shadowExecutionEngine.restart();
        return ResponseEntity.ok(Map.of(
            "status", "SUCCESS",
            "message", "Trading engine restarted",
            "timestamp", LocalDateTime.now()
        ));
    }

    @GetMapping("/kill-switch/state")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> getKillSwitchState() {
        return ResponseEntity.ok(killSwitchEngine.getCurrentState());
    }

    @PostMapping("/kill-switch/clear")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> clearKillSwitch() {
        log.warn("Admin clearing kill switch — operator has acknowledged the incident");
        killSwitchEngine.clearKillSwitch();
        return ResponseEntity.ok(Map.of(
            "status", "SUCCESS",
            "message", "Kill switch cleared. Call /engine/restart to resume trading.",
            "timestamp", LocalDateTime.now()
        ));
    }
}
