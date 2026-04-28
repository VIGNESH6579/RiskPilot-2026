package com.riskpilot.controller;

import com.riskpilot.service.CandleAggregator;
import com.riskpilot.service.MarketSessionService;
import com.riskpilot.service.MarketDataStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/engine")
public class EngineController {
    private static final Logger logger = LoggerFactory.getLogger(EngineController.class);

    @Autowired
    private CandleAggregator candleAggregator;

    @Autowired
    private MarketDataStateService marketDataStateService;

    @Autowired
    private MarketSessionService marketSessionService;

    @GetMapping("/health")
    public Map<String, Object> getHealth() {
        var marketData = marketDataStateService.snapshot();
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", marketData.feedBlocked() ? "BLOCKED" : "ONLINE");
        health.put("feedStable", candleAggregator.isFeedUnstable() ? "UNSTABLE" : "STABLE");
        health.put("transport", marketData.transport() != null ? marketData.transport().name() : null);
        health.put("sourceAgeMs", marketData.lastTick() != null ? marketData.lastTick().sourceAgeMs() : null);
        health.put("halted", marketData.halted());
        health.put("consecutiveRejectedTicks", marketData.consecutiveRejectedTicks());
        health.put("parseFailureCount", marketData.parseFailureCount());
        health.put("ready", marketData.ready());
        health.put("marketOpen", marketSessionService.isMarketOpen());
        health.put("timestamp", Instant.now().toString());
        return health;
    }

    @GetMapping("/state")
    public Map<String, Object> getEngineState() {
        var marketData = marketDataStateService.snapshot();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sessionActive", true);
        state.put("feedHealthy", !candleAggregator.isFeedUnstable());
        state.put("transport", marketData.transport() != null ? marketData.transport().name() : null);
        state.put("sourceAgeMs", marketData.lastTick() != null ? marketData.lastTick().sourceAgeMs() : null);
        state.put("halted", marketData.halted());
        state.put("consecutiveRejectedTicks", marketData.consecutiveRejectedTicks());
        state.put("parseFailureCount", marketData.parseFailureCount());
        state.put("ready", marketData.ready());
        state.put("marketOpen", marketSessionService.isMarketOpen());
        state.put("timestamp", Instant.now().toString());
        return state;
    }

    @GetMapping("/candle-history")
    public Map<String, Object> getCandleHistory() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("candleHistorySize", candleAggregator.getValidHistory().size());
        response.put("timestamp", Instant.now().toString());
        return response;
    }

    @PostMapping("/reset")
    public Map<String, Object> resetSession() {
        try {
            logger.info("Resetting session");
            candleAggregator.clearHistory();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "RESET_COMPLETE");
            return response;
        } catch (Exception e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ERROR");
            response.put("error", "Reset failed");
            return response;
        }
    }

}
