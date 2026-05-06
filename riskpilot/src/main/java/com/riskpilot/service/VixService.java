package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.riskpilot.exception.MarketDataException;
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


    private double lastKnownVix;
    private long lastSuccessfulFetchEpochMs = 0L;
    private long lastUnavailableWarningEpochMs = 0L;
    private boolean missingTokenWarned = false;

    public VixService(
        AngelOneMarketDataService angelOneMarketDataService,
        @Value("${ANGEL_INDIA_VIX_EXCHANGE:NSE}") String indiaVixExchange,
        @Value("${ANGEL_INDIA_VIX_TOKEN:999920005}") String indiaVixToken
    ) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.indiaVixExchange = indiaVixExchange == null ? "NSE" : indiaVixExchange.trim();
        this.indiaVixToken = indiaVixToken == null ? "" : indiaVixToken.trim();
        this.lastKnownVix = 0.0; // Initialize to 0, will be updated by real data
    }

    public synchronized double getIndiaVix() {
        long now = System.currentTimeMillis();
        if (now - lastSuccessfulFetchEpochMs < VIX_CACHE_MS && lastKnownVix > 0.0) {
            return lastKnownVix;
        }

        if (indiaVixToken.isBlank()) {
            throw new MarketDataException("ANGEL_INDIA_VIX_TOKEN_MISSING: Cannot fetch VIX without a token.");
        }

        Optional<Double> fetched = angelOneMarketDataService.getLtp(indiaVixExchange, indiaVixToken);
        if (fetched.isPresent() && fetched.get() > 0.0) {
            lastKnownVix = fetched.get();
            lastSuccessfulFetchEpochMs = now;
            return lastKnownVix;
        }

        throw new MarketDataException("ANGEL_INDIA_VIX_UNAVAILABLE: Failed to fetch real-time VIX data.");
    }

    private void warnUnavailable() {
        long now = System.currentTimeMillis();
        if (now - lastUnavailableWarningEpochMs >= VIX_WARNING_INTERVAL_MS) {
            log.warn("ANGEL_INDIA_VIX_UNAVAILABLE: returning last known VIX={}", lastKnownVix);
            lastUnavailableWarningEpochMs = now;
        }
    }
}
