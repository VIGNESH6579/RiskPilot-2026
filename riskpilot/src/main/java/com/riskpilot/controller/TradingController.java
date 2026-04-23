package com.riskpilot.controller;

import com.riskpilot.model.Trade;
import com.riskpilot.model.TradingSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
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
        try {
            Map<String, Object> status = Map.of(
                "status", "ACTIVE",
                "timestamp", LocalDateTime.now(),
                "engine", "SHADOW_EXECUTION",
                "message", "RiskPilot trading engine is running"
            );
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Error getting trading status", e);
            throw new com.riskpilot.exception.RiskPilotException("Failed to get trading status: " + e.getMessage());
        }
    }

    @GetMapping("/sessions/current")
    public ResponseEntity<TradingSession> getCurrentSession(
            @RequestParam @NotBlank String symbol) {
        try {
            TradingSession session = tradingSessionService.getCurrentSession(symbol);
            if (session == null) {
                throw new com.riskpilot.exception.RiskPilotException("No active session found for symbol: " + symbol);
            }
            return ResponseEntity.ok(session);
        } catch (Exception e) {
            log.error("Error getting current session for symbol: {}", symbol, e);
            throw new com.riskpilot.exception.RiskPilotException("Failed to get current session: " + e.getMessage());
        }
    }

    @GetMapping("/trades/active")
    public ResponseEntity<List<Trade>> getActiveTrades(
            @RequestParam @NotBlank String symbol) {
        try {
            List<Trade> activeTrades = tradingSessionService.getActiveTrades(symbol);
            return ResponseEntity.ok(activeTrades);
        } catch (Exception e) {
            log.error("Error getting active trades for symbol: {}", symbol, e);
            throw new com.riskpilot.exception.RiskPilotException("Failed to get active trades: " + e.getMessage());
        }
    }

    @GetMapping("/signals/recent")
    public ResponseEntity<List<TradingSignal>> getRecentSignals(
            @RequestParam @NotBlank String symbol,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            List<TradingSignal> signals = tradingSessionService.getRecentSignals(symbol, limit);
            return ResponseEntity.ok(signals);
        } catch (Exception e) {
            log.error("Error getting recent signals for symbol: {}", symbol, e);
            throw new com.riskpilot.exception.RiskPilotException("Failed to get recent signals: " + e.getMessage());
        }
    }

    @GetMapping("/trades/history")
    public ResponseEntity<List<Trade>> getTradeHistory(
            @RequestParam @NotBlank String symbol,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        try {
            List<Trade> trades = tradingSessionService.getTradeHistory(symbol, startDate, endDate);
            return ResponseEntity.ok(trades);
        } catch (Exception e) {
            log.error("Error getting trade history for symbol: {}", symbol, e);
            throw new com.riskpilot.exception.RiskPilotException("Failed to get trade history: " + e.getMessage());
        }
    }

    @PostMapping("/signals/manual")
    public ResponseEntity<Map<String, Object>> createManualSignal(
            @Valid @RequestBody TradingSignal signal) {
        try {
            log.info("Creating manual signal: {}", signal);
            tradingSessionService.processManualSignal(signal);
            
            Map<String, Object> response = Map.of(
                "status", "SUCCESS",
                "message", "Manual signal created successfully",
                "signalId", signal.getId(),
                "timestamp", LocalDateTime.now()
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error creating manual signal", e);
            throw new com.riskpilot.exception.TradingException("Failed to create manual signal: " + e.getMessage());
        }
    }

    @PostMapping("/trades/{tradeId}/close")
    public ResponseEntity<Map<String, Object>> closeTrade(
            @PathVariable Long tradeId,
            @RequestParam(required = false) String reason) {
        try {
            log.info("Closing trade {} with reason: {}", tradeId, reason);
            tradingSessionService.closeTrade(tradeId, reason);
            
            Map<String, Object> response = Map.of(
                "status", "SUCCESS",
                "message", "Trade closed successfully",
                "tradeId", tradeId,
                "timestamp", LocalDateTime.now()
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error closing trade: {}", tradeId, e);
            throw new com.riskpilot.exception.TradingException("Failed to close trade: " + e.getMessage());
        }
    }

    @GetMapping("/metrics/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics(
            @RequestParam @NotBlank String symbol,
            @RequestParam(defaultValue = "30") int days) {
        try {
            Map<String, Object> metrics = tradingSessionService.getPerformanceMetrics(symbol, days);
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("Error getting performance metrics for symbol: {}", symbol, e);
            throw new com.riskpilot.exception.RiskPilotException("Failed to get performance metrics: " + e.getMessage());
        }
    }

    @PostMapping("/engine/restart")
    public ResponseEntity<Map<String, Object>> restartEngine() {
        try {
            log.info("Restarting trading engine...");
            shadowExecutionEngine.restart();
            
            Map<String, Object> response = Map.of(
                "status", "SUCCESS",
                "message", "Trading engine restarted successfully",
                "timestamp", LocalDateTime.now()
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error restarting trading engine", e);
            throw new com.riskpilot.exception.RiskPilotException("Failed to restart engine: " + e.getMessage());
        }
    }
}
