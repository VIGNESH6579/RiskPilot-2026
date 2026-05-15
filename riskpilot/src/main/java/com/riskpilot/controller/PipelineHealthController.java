package com.riskpilot.controller;

import com.riskpilot.model.Candle;
import com.riskpilot.service.AngelTickStreamClient;
import com.riskpilot.service.CandleAggregator;
import com.riskpilot.service.OptionChainService;
import com.riskpilot.service.VixService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final CandleAggregator candleAggregator;
    private final VixService vixService;
    private final OptionChainService optionChainService;

    @GetMapping("/pipeline")
    public ResponseEntity<Map<String, Object>> pipeline() {
        Map<String, Object> tickSection = new LinkedHashMap<>();
        boolean streamActive = tickStreamClient.isStreamActive();
        boolean feedStable  = !candleAggregator.isFeedUnstable();
        List<Candle> history = candleAggregator.getValidHistory();

        tickSection.put("streamActive",   streamActive);
        tickSection.put("feedStable",     feedStable);
        tickSection.put("totalCandles",   history.size());
        tickSection.put("activeBuilders", 1);
        tickSection.put("lastTickTime",   LocalDateTime.now().toString());
        tickSection.put("lastCandleTime", history.isEmpty() ? "none" : history.get(history.size()-1).timestamp().toString());

        Map<String, Object> vixSection = new LinkedHashMap<>();
        double vix = vixService.getIndiaVix();
        vixSection.put("value",    vix);
        vixSection.put("category", vixCategory(vix));

        Map<String, Object> chainSection = new LinkedHashMap<>();
        OptionChainService.OptionChainSnapshot snap = optionChainService.getLastKnownSnapshot();
        long ageMs = optionChainService.getLastKnownSnapshotAgeMs();
        chainSection.put("marketOpen",    optionChainService.isMarketOpen());
        chainSection.put("spot",          snap.spot());
        chainSection.put("source",        snap.source());
        chainSection.put("snapshotAgeMs", ageMs == Long.MAX_VALUE ? "never" : ageMs);
        chainSection.put("live",          snap.live());
        chainSection.put("expiry",        snap.expiry());

        boolean healthy = streamActive && feedStable && snap.spot() > 0.0;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status",      healthy ? "healthy" : "degraded");
        payload.put("timestamp",   Instant.now().toString());
        payload.put("tickFeed",    tickSection);
        payload.put("vix",         vixSection);
        payload.put("optionChain", chainSection);

        return ResponseEntity.ok(payload);
    }

    private static String vixCategory(double vix) {
        if (vix < 15)  return "LOW";
        if (vix < 20)  return "MODERATE";
        if (vix < 25)  return "ELEVATED";
        return "HIGH";
    }
}
