package com.riskpilot.controller;

import com.riskpilot.service.AngelTickStreamClient;
import com.riskpilot.service.OptionChainService;
import com.riskpilot.service.RealTimeTickAggregator;
import com.riskpilot.service.VixService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes a single endpoint that summarises the health of the live data
 * pipeline: tick feed, candle aggregation, VIX, and option-chain layers.
 *
 * GET /api/v1/health/pipeline
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class PipelineHealthController {

    private final AngelTickStreamClient tickStreamClient;
    private final RealTimeTickAggregator realTimeTickAggregator;
    private final VixService vixService;
    private final OptionChainService optionChainService;

    @GetMapping("/pipeline")
    public ResponseEntity<Map<String, Object>> pipeline() {
        Map<String, Object> tickSection = new LinkedHashMap<>();
        boolean streamActive = tickStreamClient.isStreamActive();
        boolean feedStable  = realTimeTickAggregator.isFeedStable();
        Map<String, Object> aggStats = realTimeTickAggregator.getStats();

        tickSection.put("streamActive",   streamActive);
        tickSection.put("feedStable",     feedStable);
        tickSection.put("totalCandles",   aggStats.get("totalCandles"));
        tickSection.put("activeBuilders", aggStats.get("activeBuilders"));
        tickSection.put("lastTickTime",   aggStats.get("lastTickTime"));
        tickSection.put("lastCandleTime", aggStats.get("lastCandleTime"));

        Map<String, Object> vixSection = new LinkedHashMap<>();
        boolean vixLive = false;
        try {
            double vix = vixService.getIndiaVix();
            vixLive = true;
            vixSection.put("value",    vix);
            vixSection.put("category", vixCategory(vix));
        } catch (Exception e) {
            vixSection.put("value", null);
            vixSection.put("category", "UNAVAILABLE");
            vixSection.put("error", e.getMessage());
        }

        Map<String, Object> chainSection = new LinkedHashMap<>();
        OptionChainService.OptionChainSnapshot snap = optionChainService.fetchNiftyChain();
        long ageMs = optionChainService.getLastKnownSnapshotAgeMs();
        boolean marketOpen = optionChainService.isMarketOpen();
        boolean marketDataHealthy = !marketOpen || (snap.live() && snap.spot() > 0.0);
        chainSection.put("marketOpen",    marketOpen);
        chainSection.put("spot",          snap.spot());
        chainSection.put("source",        snap.source());
        chainSection.put("snapshotAgeMs", ageMs == Long.MAX_VALUE ? "never" : ageMs);
        chainSection.put("live",          snap.live());
        chainSection.put("expiry",        snap.expiry());
        chainSection.put("healthy",       marketDataHealthy);

        boolean healthy = !marketOpen || (streamActive && feedStable && marketDataHealthy && vixLive);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status",      healthy ? "healthy" : "degraded");
        payload.put("timestamp",   Instant.now().toString());
        payload.put("tickFeed",    tickSection);
        payload.put("vix",         vixSection);
        payload.put("optionChain", chainSection);

        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(payload);
    }

    private static String vixCategory(double vix) {
        if (vix < 15)  return "LOW";
        if (vix < 20)  return "MODERATE";
        if (vix < 25)  return "ELEVATED";
        return "HIGH";
    }
}
