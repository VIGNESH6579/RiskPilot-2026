package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class VixService {
    private static final Logger log = LoggerFactory.getLogger(VixService.class);
    private static final long VIX_CACHE_MS = 20_000L;
    private static final long VIX_WARNING_INTERVAL_MS = 60_000L;

    private final AngelOneMarketDataService angelOneMarketDataService;
    private final String indiaVixExchange;
    private final String indiaVixToken;
    private final double fallbackVix;

    private double lastKnownVix;
    private long lastSuccessfulFetchEpochMs = 0L;
    private long lastUnavailableWarningEpochMs = 0L;
    private boolean missingTokenWarned = false;

    public VixService(
        AngelOneMarketDataService angelOneMarketDataService,
        @Value("${ANGEL_INDIA_VIX_EXCHANGE:NSE}") String indiaVixExchange,
        @Value("${ANGEL_INDIA_VIX_TOKEN:999920005}") String indiaVixToken,
        @Value("${RISK_VIX_FALLBACK:15.0}") double fallbackVix
    ) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.indiaVixExchange = indiaVixExchange == null ? "NSE" : indiaVixExchange.trim();
        this.indiaVixToken = indiaVixToken == null ? "" : indiaVixToken.trim();
        this.fallbackVix = fallbackVix > 0.0 ? fallbackVix : 15.0;
        this.lastKnownVix = this.fallbackVix;
    }

    public synchronized double getIndiaVix() {
        long now = System.currentTimeMillis();
        if (now - lastSuccessfulFetchEpochMs < VIX_CACHE_MS && lastKnownVix > 0.0) {
            return lastKnownVix;
        }

        if (indiaVixToken.isBlank()) {
            if (!missingTokenWarned) {
                log.warn("ANGEL_INDIA_VIX_TOKEN not set: using RISK_VIX_FALLBACK={}", fallbackVix);
                missingTokenWarned = true;
            }
            return fallbackVix;
        }

        Optional<Double> fetched = angelOneMarketDataService.getLtp(indiaVixExchange, indiaVixToken);
        if (fetched.isPresent() && fetched.get() > 0.0) {
            lastKnownVix = fetched.get();
            lastSuccessfulFetchEpochMs = now;
            return lastKnownVix;
        }

        // Return last known value (or fallback) — do NOT throw, the engine must keep running
        long nowWarn = System.currentTimeMillis();
        if (nowWarn - lastUnavailableWarningEpochMs >= VIX_WARNING_INTERVAL_MS) {
            log.warn("ANGEL_INDIA_VIX_UNAVAILABLE: returning lastKnownVix={}", lastKnownVix);
            lastUnavailableWarningEpochMs = nowWarn;
        }
        return lastKnownVix > 0.0 ? lastKnownVix : fallbackVix;
    }
}
