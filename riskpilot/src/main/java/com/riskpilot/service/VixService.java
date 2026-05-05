package com.riskpilot.service;

import com.riskpilot.exception.MarketDataException;
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

    private Double lastKnownVix;
    private long lastSuccessfulFetchEpochMs = 0L;
    private long lastUnavailableWarningEpochMs = 0L;
    private boolean missingTokenWarned = false;

    public VixService(
        AngelOneMarketDataService angelOneMarketDataService,
        @Value("${ANGEL_INDIA_VIX_EXCHANGE:NSE}") String indiaVixExchange,
        @Value("${ANGEL_INDIA_VIX_TOKEN:}") String indiaVixToken
    ) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.indiaVixExchange = indiaVixExchange == null ? "NSE" : indiaVixExchange.trim();
        this.indiaVixToken = indiaVixToken == null ? "" : indiaVixToken.trim();
    }

    public synchronized double getIndiaVix() {
        long now = System.currentTimeMillis();
        if (lastKnownVix != null && now - lastSuccessfulFetchEpochMs < VIX_CACHE_MS) {
            return lastKnownVix;
        }

        if (indiaVixToken.isBlank()) {
            if (!missingTokenWarned) {
                log.warn("ANGEL_INDIA_VIX_TOKEN_MISSING: live VIX unavailable");
                missingTokenWarned = true;
            }
            throw new MarketDataException("INDIA_VIX_TOKEN_REQUIRED: ANGEL_INDIA_VIX_TOKEN must be configured");
        }

        Optional<Double> fetched = angelOneMarketDataService.getLtp(indiaVixExchange, indiaVixToken);
        if (fetched.isPresent() && fetched.get() > 0.0) {
            lastKnownVix = fetched.get();
            lastSuccessfulFetchEpochMs = now;
            return lastKnownVix;
        }

        warnUnavailable();
        throw new MarketDataException("INDIA_VIX_UNAVAILABLE: Angel One did not return a live VIX quote");
    }

    private void warnUnavailable() {
        long now = System.currentTimeMillis();
        if (now - lastUnavailableWarningEpochMs >= VIX_WARNING_INTERVAL_MS) {
            log.warn("ANGEL_INDIA_VIX_UNAVAILABLE: blocking VIX-dependent decisions");
            lastUnavailableWarningEpochMs = now;
        }
    }
}
