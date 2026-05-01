package com.riskpilot.controller;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.TradingSessionSnapshot;
import com.riskpilot.service.CandleAggregator;
import com.riskpilot.service.MarketDataStateService;
import com.riskpilot.service.MarketSessionService;
import com.riskpilot.service.SessionStateManager;
import com.riskpilot.service.ShadowExecutionEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitor")
@RequiredArgsConstructor
public class MonitoringController {
    private static final int MIN_REGIME_CANDLES = 7;

    private final SessionStateManager sessionStateManager;
    private final MarketDataStateService marketDataStateService;
    private final RiskPilotProperties riskPilotProperties;
    private final MarketSessionService marketSessionService;
    private final ShadowExecutionEngine shadowExecutionEngine;
    private final CandleAggregator candleAggregator;

    @GetMapping("/state")
    public Map<String, Object> state() {
        TradingSessionSnapshot snapshot = sessionStateManager.getSnapshot();
        boolean marketOpen = marketSessionService.isMarketOpen();
        String priceSource = marketDataStateService.resolvePriceSource(
            marketOpen,
            riskPilotProperties.getInfra().getHeartbeat().getMaxSilenceMs()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionActive", marketOpen && snapshot.sessionActive());
        payload.put("regime", regimeDisplayText(snapshot));
        payload.put("volatilityQualified", snapshot.volatilityQualified());
        payload.put("timePhase", snapshot.timePhase().name());
        payload.put("tradeActive", snapshot.tradeActive());
        payload.put("tradesTaken", snapshot.tradesTaken());
        payload.put("feedStable", snapshot.feedStable());
        payload.put("heartbeatAlive", snapshot.heartbeatAlive());
        payload.put("dailyLossR", snapshot.cumulativeDailyLossR());
        payload.put("lastRejectReason", snapshot.lastRejectReason());
        payload.put("orHigh", snapshot.orHigh());
        payload.put("orLow", snapshot.orLow());
        var marketData = marketDataStateService.snapshot();
        payload.put("transport", marketData.transport() != null ? marketData.transport().name() : null);
        payload.put("sourceAgeMs", marketData.lastTick() != null ? marketData.lastTick().sourceAgeMs() : null);
        payload.put("feedBlocked", marketData.feedBlocked());
        payload.put("feedBlockReason", marketData.blockReason());
        payload.put("halted", marketData.halted());
        payload.put("consecutiveRejectedTicks", marketData.consecutiveRejectedTicks());
        payload.put("parseFailureCount", marketData.parseFailureCount());
        payload.put("ready", marketData.ready());
        payload.put("priceSource", priceSource);
        payload.put("operationalStatus", resolveOperationStatus(priceSource));
        return payload;
    }

    private String regimeDisplayText(TradingSessionSnapshot snapshot) {
        int candleCount = candleAggregator.getValidHistory().size();
        if (candleCount < MIN_REGIME_CANDLES) {
            return "INITIALIZING (" + candleCount + "/" + MIN_REGIME_CANDLES + ")";
        }
        return snapshot.regime().name();
    }

    private String resolveOperationStatus(String priceSource) {
        if ("MARKET_CLOSED".equals(priceSource)) {
            return "DORMANT";
        }
        if ("STALE".equals(priceSource)) {
            return "DEGRADED";
        }
        return shadowExecutionEngine.isOperationallyBlocked() ? "OPERATIONALLY_BLOCKED" : "ACTIVE";
    }
}
