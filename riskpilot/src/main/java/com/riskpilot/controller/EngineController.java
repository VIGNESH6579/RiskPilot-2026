package com.riskpilot.controller;

import com.riskpilot.service.CandleAggregator;
import com.riskpilot.service.ShadowExecutionEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/v1/engine")
public class EngineController {
    private static final Logger logger = LoggerFactory.getLogger(EngineController.class);
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private LocalDateTime lastCandleSlot = null;

    @Autowired
    private ShadowExecutionEngine shadowExecutionEngine;

    @Autowired
    private CandleAggregator candleAggregator;

    @GetMapping("/health")
    public Map<String, Object> getHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "ONLINE");
        health.put("feedStable", candleAggregator.isFeedUnstable() ? "UNSTABLE" : "STABLE");
        health.put("timestamp", LocalDateTime.now().toString());
        return health;
    }

    @PostMapping("/test-tick")
    public Map<String, Object> sendTestTick(@RequestParam double price) {
        try {
            logger.info("📍 TEST TICK: {}", price);
            LocalDateTime receivedAt = LocalDateTime.now();
            candleAggregator.processTick(
                receivedAt,
                price,
                1000,
                sequenceCounter.incrementAndGet(),
                receivedAt
            );
            shadowExecutionEngine.evaluateTick(price);
            int candleMinute = (receivedAt.getMinute() / 5) * 5;
            LocalDateTime currentSlot = receivedAt.withMinute(candleMinute).withSecond(0).withNano(0);
            if (lastCandleSlot != null && currentSlot.isAfter(lastCandleSlot)) {
                shadowExecutionEngine.evaluateCandleClose();
            }
            lastCandleSlot = currentSlot;
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "SUCCESS");
            response.put("price", price);
            response.put("timestamp", LocalDateTime.now().toString());
            return response;
        } catch (Exception e) {
            logger.error("❌ Test tick failed", e);
            return errorResponse("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/state")
    public Map<String, Object> getEngineState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sessionActive", true);
        state.put("feedHealthy", !candleAggregator.isFeedUnstable());
        state.put("timestamp", LocalDateTime.now().toString());
        return state;
    }

    @GetMapping("/candle-history")
    public Map<String, Object> getCandleHistory() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("candleHistorySize", candleAggregator.getValidHistory().size());
        response.put("timestamp", LocalDateTime.now().toString());
        return response;
    }

    @PostMapping("/reset")
    public Map<String, Object> resetSession() {
        try {
            logger.info("🔄 Resetting session...");
            candleAggregator.clearHistory();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "RESET_COMPLETE");
            return response;
        } catch (Exception e) {
            return errorResponse("Reset failed");
        }
    }

    private Map<String, Object> errorResponse(String error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ERROR");
        response.put("error", error);
        return response;
    }
}
