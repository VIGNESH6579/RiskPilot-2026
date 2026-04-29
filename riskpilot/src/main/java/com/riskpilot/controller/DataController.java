package com.riskpilot.controller;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.TradeLog;
import com.riskpilot.model.TradeView;
import com.riskpilot.repository.TradeLogRepository;
import com.riskpilot.service.MarketDataStateService;
import com.riskpilot.service.MarketSessionService;
import com.riskpilot.service.RiskEngine;
import com.riskpilot.service.ShadowExecutionEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/data")
@CrossOrigin
@RequiredArgsConstructor
public class DataController {

    private final MarketDataStateService marketDataStateService;
    private final TradeLogRepository tradeLogRepository;
    private final MarketSessionService marketSessionService;
    private final ShadowExecutionEngine shadowExecutionEngine;
    private final RiskPilotProperties riskPilotProperties;
    private final RiskEngine riskEngine;

    @GetMapping("/health")
    public Map<String, Object> health() {
        MarketDataStateService.MarketDataSnapshot snapshot = marketDataStateService.snapshot();
        boolean marketOpen = marketSessionService.isMarketOpen();
        String priceSource = marketDataStateService.resolvePriceSource(
            marketOpen,
            riskPilotProperties.getInfra().getHeartbeat().getMaxSilenceMs()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", snapshot.transport() != null ? snapshot.transport().name() : null);
        payload.put("price", snapshot.lastTick() != null ? snapshot.lastTick().price() : null);
        payload.put("spot", snapshot.lastTick() != null && snapshot.lastTick().price() > 0.0 ? snapshot.lastTick().price() : null);
        payload.put("sourceAgeMs", snapshot.lastTick() != null ? snapshot.lastTick().sourceAgeMs() : null);
        payload.put("lastFreshTickAt", snapshot.lastAcceptedAt());
        payload.put("healthy", marketOpen && snapshot.connected() && snapshot.subscribed() && snapshot.ready() && !snapshot.feedBlocked() && snapshot.lastTick() != null);
        payload.put("sessionActive", marketOpen);
        payload.put("feedBlocked", snapshot.feedBlocked());
        payload.put("feedBlockReason", snapshot.blockReason());
        payload.put("ready", snapshot.ready());
        payload.put("parseFailureCount", snapshot.parseFailureCount());
        payload.put("marketStatus", marketOpen ? "OPEN" : "CLOSED");
        payload.put("priceSource", priceSource);
        payload.put("currentEquity", riskEngine.snapshot().currentEquity());
        payload.put("realizedPnlInr", riskEngine.snapshot().realizedPnlInr());
        payload.put("unrealizedPnlInr", riskEngine.snapshot().unrealizedPnlInr());
        payload.put("rejectReasonCounts", shadowExecutionEngine.getTopRejectReasons());
        payload.put("operationalStatus", shadowExecutionEngine.isOperationallyBlocked() ? "OPERATIONALLY_BLOCKED" : "ACTIVE");
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    @GetMapping("/trade-history")
    public List<Map<String, Object>> tradeHistory(@RequestParam(defaultValue = "25") int limit) {
        int capped = Math.max(1, Math.min(limit, 200));

        try {
            List<TradeLog> logs = tradeLogRepository.findTop200ByGateDecisionOrderBySignalTimeDesc("ALLOW");
            return logs.stream()
                .limit(capped)
                .map(log -> TradeView.fromTradeLog(log).toMap())
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
