package com.riskpilot.controller;

import com.riskpilot.model.TradingSessionSnapshot;
import com.riskpilot.service.OptionChainService;
import com.riskpilot.service.SessionStateManager;
import com.riskpilot.service.CandleAggregator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/engine")
public class EngineController {
    private static final Logger logger = LoggerFactory.getLogger(EngineController.class);

    @Autowired
    private SessionStateManager stateManager;

    @Autowired
    private OptionChainService optionChainService;

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

    @GetMapping("/state")
    public Map<String, Object> getEngineState() {
        TradingSessionSnapshot snapshot = stateManager.getSnapshot();
        OptionChainService.OptionChainSnapshot marketSnapshot = optionChainService.getLastKnownSnapshot();
        boolean marketOpen = optionChainService.isMarketOpen();
        boolean marketDataHealthy = !marketOpen || (marketSnapshot.live() && marketSnapshot.spot() > 0.0);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sessionActive", snapshot.sessionActive());
        state.put("feedHealthy", snapshot.feedStable() && !candleAggregator.isFeedUnstable() && marketDataHealthy);
        state.put("heartbeatAlive", snapshot.heartbeatAlive());
        state.put("tradeActive", snapshot.tradeActive());
        state.put("tradesTaken", snapshot.tradesTaken());
        state.put("regime", snapshot.regime().name());
        state.put("timePhase", snapshot.timePhase().name());
        state.put("orHigh", Double.isFinite(snapshot.orHigh()) ? snapshot.orHigh() : null);
        state.put("orLow", Double.isFinite(snapshot.orLow()) ? snapshot.orLow() : null);
        state.put("marketOpen", marketOpen);
        state.put("marketDataSource", marketSnapshot.source());
        state.put("marketDataLive", marketSnapshot.live());
        state.put("lastRejectReason", snapshot.lastRejectReason());
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
