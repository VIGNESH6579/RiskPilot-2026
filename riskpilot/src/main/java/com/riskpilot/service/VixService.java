package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpEntity;       // ← FIX: was missing
import org.springframework.http.HttpHeaders;      // ← FIX: caused compile error on line 104
import org.springframework.http.HttpMethod;       // ← FIX: was missing
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

@Service
public class VixService {
    private static final Logger log = LoggerFactory.getLogger(VixService.class);
    private static final long VIX_CACHE_MS = 300_000L;
    private static final long VIX_RETRY_DELAY_MS = 60_000L;
    private static final long VIX_WARNING_INTERVAL_MS = 300_000L;

    private final AngelOneMarketDataService angelOneMarketDataService;
    private final CentralizedMarketDataService centralizedMarketDataService;
    private final MarketDataStateService marketDataStateService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String indiaVixExchange;
    private final String indiaVixToken;
    private final double fallbackVix;

    private double lastKnownVix;
    private long lastSuccessfulFetchEpochMs = 0L;
    private long lastFetchAttemptEpochMs = 0L;
    private long lastUnavailableWarningEpochMs = 0L;
    private boolean missingTokenWarned = false;

    /**
     * Fix #6 – Circuit breaker for VIX.
     * If BOTH Angel One and Yahoo fail on the same scheduled attempt,
     * this flips to true and TradingSafetyManager sees VIX as invalid,
     * blocking new trades until the next successful fetch.
     * Using the last-known stale value silently is worse than an explicit block.
     */
    private volatile boolean bothSourcesFailed = false;

    private static final String YAHOO_VIX_URL =
        "https://query1.finance.yahoo.com/v8/finance/chart/^INDIAVIX?interval=1m&range=1d";

    public VixService(
        AngelOneMarketDataService angelOneMarketDataService,
        CentralizedMarketDataService centralizedMarketDataService,
        MarketDataStateService marketDataStateService,
        @Value("${ANGEL_INDIA_VIX_EXCHANGE:NSE}") String indiaVixExchange,
        @Value("${ANGEL_INDIA_VIX_TOKEN:999920005}") String indiaVixToken,
        @Value("${RISK_VIX_FALLBACK:15.0}") double fallbackVix
    ) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.centralizedMarketDataService = centralizedMarketDataService;
        this.marketDataStateService = marketDataStateService;
        this.indiaVixExchange = indiaVixExchange == null ? "NSE" : indiaVixExchange.trim();
        this.indiaVixToken = indiaVixToken == null ? "" : indiaVixToken.trim();
        this.fallbackVix = fallbackVix > 0.0 ? fallbackVix : 15.0;
        this.lastKnownVix = this.fallbackVix;
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void refreshVix() {
        long now = System.currentTimeMillis();
        lastFetchAttemptEpochMs = now;
        boolean angelSuccess = false;

        if (indiaVixToken.isBlank()) {
            if (!missingTokenWarned) {
                log.warn("ANGEL_INDIA_VIX_TOKEN not set: using RISK_VIX_FALLBACK={}", fallbackVix);
                missingTokenWarned = true;
            }
        } else {
            Optional<Double> fetched = angelOneMarketDataService.getLtp(indiaVixExchange, indiaVixToken);
            if (fetched.isPresent() && fetched.get() > 0.0) {
                lastKnownVix = fetched.get();
                lastSuccessfulFetchEpochMs = now;
                bothSourcesFailed = false;
                marketDataStateService.updateVix(lastKnownVix, java.time.Instant.now());
                return;
            }
            // Angel One failed — try Yahoo
        }

        Optional<Double> yahooVix = fetchVixFromYahoo();
        if (yahooVix.isPresent()) {
            lastKnownVix = yahooVix.get();
            lastSuccessfulFetchEpochMs = now;
            bothSourcesFailed = false;
            marketDataStateService.updateVix(lastKnownVix, java.time.Instant.now());
            log.info("VIX fetched from Yahoo Finance: {}", lastKnownVix);
            return;
        }

        // Fix #6: both sources failed — activate circuit breaker
        bothSourcesFailed = true;
        long nowWarn = System.currentTimeMillis();
        if (nowWarn - lastUnavailableWarningEpochMs >= VIX_WARNING_INTERVAL_MS) {
            log.error("🚨 VIX_CIRCUIT_BREAKER: Both Angel One and Yahoo Finance failed. "
                + "Trading will be blocked until VIX is fetched successfully. "
                + "lastKnownVix={} (stale — NOT being used for new trades)", lastKnownVix);
            lastUnavailableWarningEpochMs = nowWarn;
        }
    }

    public synchronized double getIndiaVix() {
        long now = System.currentTimeMillis();

        if (now - lastSuccessfulFetchEpochMs >= VIX_CACHE_MS || lastKnownVix <= 0.0) {
            if (now - lastFetchAttemptEpochMs >= VIX_RETRY_DELAY_MS) {
                refreshVix();
            }
        }

        // Circuit breaker: don't return a stale/fallback value — surface the failure
        if (bothSourcesFailed) {
            return -1.0; // sentinel: callers (MarketDataStateService.isVixValid) treat <=0 as invalid
        }

        return lastKnownVix > 0.0 ? lastKnownVix : fallbackVix;
    }

    /** True when both Angel One and Yahoo have failed and VIX is unknown. */
    public boolean isBothSourcesFailed() {
        return bothSourcesFailed;
    }

    private Optional<Double> fetchVixFromYahoo() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response =
                restTemplate.exchange(YAHOO_VIX_URL, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // Parse JSON properly instead of using a fragile regex.
                // Yahoo Finance v8 structure:
                //   chart.result[0].meta.regularMarketPrice
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode price = root
                    .path("chart")
                    .path("result")
                    .path(0)
                    .path("meta")
                    .path("regularMarketPrice");
                if (!price.isMissingNode() && price.isNumber()) {
                    double vix = price.asDouble();
                    if (vix > 0.0) {
                        return Optional.of(vix);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch VIX from Yahoo Finance: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
