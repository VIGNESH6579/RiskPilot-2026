package com.riskpilot.controller;

import com.riskpilot.model.TradingSessionSnapshot;
import com.riskpilot.service.SessionStateManager;
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

    private final SessionStateManager sessionStateManager;

    @GetMapping("/state")
    public Map<String, Object> state() {
        TradingSessionSnapshot snapshot = sessionStateManager.getSnapshot();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionActive", snapshot.sessionActive());
        payload.put("regime", snapshot.regime().name());
        payload.put("volatilityQualified", snapshot.volatilityQualified());
        payload.put("timePhase", snapshot.timePhase().name());
        payload.put("tradeActive", snapshot.tradeActive());
        payload.put("tradesTaken", snapshot.tradesTaken());
        payload.put("maxTradesPerDay", 2);
        payload.put("feedStable", snapshot.feedStable());
        payload.put("heartbeatAlive", snapshot.heartbeatAlive());
        payload.put("dailyLossR", snapshot.cumulativeDailyLossR());
        payload.put("lastRejectReason", snapshot.lastRejectReason());
        payload.put("orHigh", snapshot.orHigh());
        payload.put("orLow", snapshot.orLow());
        return payload;
    }
}
