package com.riskpilot.controller;

import com.riskpilot.exception.RiskPilotException;
import com.riskpilot.exception.TradingException;
import com.riskpilot.model.Trade;
import com.riskpilot.model.TradingSignal;
import com.riskpilot.model.TradingSession;
import com.riskpilot.service.ShadowExecutionEngine;
import com.riskpilot.service.TradingSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/trading")
@RequiredArgsConstructor
@Validated
@CrossOrigin(origins = "*")
public class TradingController {

    private final ShadowExecutionEngine shadowExecutionEngine;
    private final TradingSessionService tradingSessionService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getTradingStatus() {
        return ResponseEntity.ok(Map.of(
            "status", "ACTIVE",
            "timestamp", LocalDateTime.now(),
            "engine", "SHADOW_EXECUTION",
            "message", "RiskPilot trading engine is running"
        ));
    }

    @GetMapping("/sessions/current")
    public ResponseEntity<TradingSession> getCurrentSession(@RequestParam @NotBlank String symbol) {
        try {
            return ResponseEntity.ok(tradingSessionService.getCurrentSession(symbol));
        } catch (Exception e) {
            log.error("Error getting current session for symbol {}", symbol, e);
            throw new RiskPilotException("Failed to get current session: " + e.getMessage());
        }
    }

    @GetMapping("/trades/active")
    public ResponseEntity<List<Trade>> getActiveTrades(@RequestParam @NotBlank String symbol) {
        try {
            return ResponseEntity.ok(tradingSessionService.getActiveTrades(symbol));
        } catch (Exception e) {
            log.error("Error getting active trades for symbol {}", symbol, e);
            throw new RiskPilotException("Failed to get active trades: " + e.getMessage());
        }
    }

    @GetMapping("/signals/recent")
    public ResponseEntity<List<TradingSignal>> getRecentSignals(
        @RequestParam @NotBlank String symbol,
        @RequestParam(defaultValue = "10") int limit
    ) {
        try {
            return ResponseEntity.ok(tradingSessionService.getRecentSignals(symbol, limit));
        } catch (Exception e) {
            log.error("Error getting recent signals for symbol {}", symbol, e);
            throw new RiskPilotException("Failed to get recent signals: " + e.getMessage());
        }
    }

    @GetMapping("/trades/history")
    public ResponseEntity<List<Trade>> getTradeHistory(
        @RequestParam @NotBlank String symbol,
        @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
        @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        try {
            return ResponseEntity.ok(tradingSessionService.getTradeHistory(symbol, startDate, endDate));
        } catch (Exception e) {
            log.error("Error getting trade history for symbol {}", symbol, e);
            throw new RiskPilotException("Failed to get trade history: " + e.getMessage());
        }
    }

    @PostMapping("/signals/manual")
    public ResponseEntity<Map<String, Object>> createManualSignal(@Valid @RequestBody TradingSignal signal) {
        try {
            tradingSessionService.processManualSignal(signal);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "Manual signal created successfully");
            if (signal.getId() != null) {
                response.put("signalId", signal.getId());
            }
            response.put("timestamp", LocalDateTime.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error creating manual signal", e);
            throw new TradingException("Failed to create manual signal: " + e.getMessage());
        }
    }

    @PostMapping("/trades/{tradeId}/close")
    public ResponseEntity<Map<String, Object>> closeTrade(
        @PathVariable Long tradeId,
        @RequestParam(required = false) String reason
    ) {
        try {
            tradingSessionService.closeTrade(tradeId, reason);
            return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Trade closed successfully",
                "tradeId", tradeId,
                "timestamp", LocalDateTime.now()
            ));
        } catch (Exception e) {
            log.error("Error closing trade {}", tradeId, e);
            throw new TradingException("Failed to close trade: " + e.getMessage());
        }
    }

    @GetMapping("/metrics/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics(
        @RequestParam @NotBlank String symbol,
        @RequestParam(defaultValue = "30") int days
    ) {
        try {
            return ResponseEntity.ok(tradingSessionService.getPerformanceMetrics(symbol, days));
        } catch (Exception e) {
            log.error("Error getting performance metrics for symbol {}", symbol, e);
            throw new RiskPilotException("Failed to get performance metrics: " + e.getMessage());
        }
    }

    @PostMapping("/engine/restart")
    public ResponseEntity<Map<String, Object>> restartEngine() {
        try {
            shadowExecutionEngine.restart();
            return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Trading engine restarted successfully",
                "timestamp", LocalDateTime.now()
            ));
        } catch (Exception e) {
            log.error("Error restarting trading engine", e);
            throw new RiskPilotException("Failed to restart engine: " + e.getMessage());
        }
    }
}
