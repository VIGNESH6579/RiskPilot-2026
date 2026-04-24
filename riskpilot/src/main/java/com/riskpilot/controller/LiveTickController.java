package com.riskpilot.controller;

import com.riskpilot.service.CandleAggregator;
import com.riskpilot.service.ShadowExecutionEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 🎯 CRITICAL: WebSocket Bridge from Frontend to Trading Engine
 * 
 * This receives LIVE price ticks and feeds them to the trading engine!
 */
@Controller
public class LiveTickController {
    private static final Logger logger = LoggerFactory.getLogger(LiveTickController.class);

    @Autowired
    private ShadowExecutionEngine shadowExecutionEngine;

    @Autowired
    private CandleAggregator candleAggregator;

    /**
     * ⭐ MAIN ENTRY: Receives live ticks from frontend WebSocket
     */
    @MessageMapping("/tick")
    @SendTo("/topic/engine-state")
    public Map<String, Object> processTick(TickRequest request) {
        try {
            logger.debug("📍 Received tick: price={}, volume={}", request.getPrice(), request.getVolume());

            LocalDateTime tickTime = request.getTimestamp() != null 
                ? LocalDateTime.parse(request.getTimestamp())
                : LocalDateTime.now();

            // Step 1: Update candles
            candleAggregator.processTick(tickTime, request.getPrice(), request.getVolume());
            
            // Step 2: Evaluate in engine (THIS IS THE FIX!)
            shadowExecutionEngine.evaluateTick(request.getPrice());
            
            // Step 3: Return state
            return getEngineState();

        } catch (Exception e) {
            logger.error("❌ Error processing tick", e);
            return errorResponse("Tick processing failed: " + e.getMessage());
        }
    }

    private Map<String, Object> getEngineState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sessionActive", true);
        state.put("feedHealthy", true);
        state.put("timestamp", LocalDateTime.now().toString());
        return state;
    }

    private Map<String, Object> errorResponse(String error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", error);
        response.put("timestamp", LocalDateTime.now().toString());
        return response;
    }

    public static class TickRequest {
        private double price;
        private long volume;
        private String timestamp;

        public double getPrice() { return price; }
        public void setPrice(double price) { this.price = price; }
        public long getVolume() { return volume; }
        public void setVolume(long volume) { this.volume = volume; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }
}
