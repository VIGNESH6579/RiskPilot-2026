package com.riskpilot.controller;

import com.riskpilot.model.TradeLog;
import com.riskpilot.repository.TradeLogRepository;
import com.riskpilot.service.MarketDataStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
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

    @GetMapping("/health")
    public Map<String, Object> health() {
        MarketDataStateService.MarketDataSnapshot snapshot = marketDataStateService.snapshot();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", snapshot.transport() != null ? snapshot.transport().name() : null);
        payload.put("spot", snapshot.lastTick() != null && snapshot.lastTick().price() > 0.0 ? snapshot.lastTick().price() : null);
        payload.put("sourceAgeMs", snapshot.lastTick() != null ? snapshot.lastTick().sourceAgeMs() : null);
        payload.put("lastFreshTickAt", snapshot.lastAcceptedAt());
        payload.put("healthy", snapshot.connected() && snapshot.subscribed() && !snapshot.feedBlocked() && snapshot.lastTick() != null);
        payload.put("feedBlocked", snapshot.feedBlocked());
        payload.put("feedBlockReason", snapshot.blockReason());
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    @GetMapping("/trade-history")
    public List<Map<String, Object>> tradeHistory(@RequestParam(defaultValue = "25") int limit) {
        int capped = Math.max(1, Math.min(limit, 200));

        try {
            List<TradeLog> logs = tradeLogRepository.findTop200ByOrderBySignalTimeDesc();
            return logs.stream()
                .limit(capped)
                .map(this::toTradeMap)
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> toTradeMap(TradeLog log) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("signalTime", log.getSignalTime());
        row.put("executionTime", log.getExecutionTime());
        row.put("latencySec", log.getLatencySec());
        row.put("entryLatencyMs", log.getEntryLatencyMs());
        row.put("exitLatencyMs", log.getExitLatencyMs());
        row.put("expectedEntry", log.getExpectedEntry());
        row.put("actualEntry", log.getActualEntry());
        row.put("entrySlippage", log.getEntrySlippage());
        row.put("expectedExit", log.getExpectedExit());
        row.put("actualExit", log.getActualExit());
        row.put("exitSlippage", log.getExitSlippage());
        row.put("tp1Hit", log.getTp1Hit());
        row.put("runnerCaptured", log.getRunnerCaptured());
        row.put("mfe", log.getMfe());
        row.put("mae", log.getMae());
        row.put("realizedR", log.getRealizedR());
        row.put("gateDecision", log.getGateDecision());
        row.put("rejectReason", log.getRejectReason());
        row.put("regime", log.getRegime());
        row.put("timePhase", log.getTimePhase());
        row.put("feedStable", log.getFeedStable());
        row.put("exitReason", log.getExitReason());
        row.put("exitTime", log.getExitTime());
        return row;
    }
}
