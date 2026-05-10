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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VixService {
    private static final Logger log = LoggerFactory.getLogger(VixService.class);
    private static final long VIX_CACHE_MS = 300_000L;
    private static final long VIX_RETRY_DELAY_MS = 60_000L;
    private static final long VIX_WARNING_INTERVAL_MS = 300_000L;

    private final AngelOneMarketDataService angelOneMarketDataService;
    private final CentralizedMarketDataService centralizedMarketDataService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final String indiaVixExchange;
    private final String indiaVixToken;
    private final double fallbackVix;

    private double lastKnownVix;
    private long lastSuccessfulFetchEpochMs = 0L;
    private long lastFetchAttemptEpochMs = 0L;
    private long lastUnavailableWarningEpochMs = 0L;
    private boolean missingTokenWarned = false;

    private static final String YAHOO_VIX_URL =
        "https://query1.finance.yahoo.com/v8/finance/chart/^INDIAVIX?interval=1m&range=1d";

    public VixService(
        AngelOneMarketDataService angelOneMarketDataService,
        CentralizedMarketDataService centralizedMarketDataService,
        @Value("${ANGEL_INDIA_VIX_EXCHANGE:NSE}") String indiaVixExchange,
        @Value("${ANGEL_INDIA_VIX_TOKEN:999920005}") String indiaVixToken,
        @Value("${RISK_VIX_FALLBACK:15.0}") double fallbackVix
    ) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.centralizedMarketDataService = centralizedMarketDataService;
        this.indiaVixExchange = indiaVixExchange == null ? "NSE" : indiaVixExchange.trim();
        this.indiaVixToken = indiaVixToken == null ? "" : indiaVixToken.trim();
        this.fallbackVix = fallbackVix > 0.0 ? fallbackVix : 15.0;
        this.lastKnownVix = this.fallbackVix;
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void refreshVix() {
        long now = System.currentTimeMillis();
        lastFetchAttemptEpochMs = now;

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
                return;
            }
        }

        Optional<Double> yahooVix = fetchVixFromYahoo();
        if (yahooVix.isPresent()) {
            lastKnownVix = yahooVix.get();
            lastSuccessfulFetchEpochMs = now;
            log.info("VIX fetched from Yahoo Finance: {}", lastKnownVix);
            return;
        }

        long nowWarn = System.currentTimeMillis();
        if (nowWarn - lastUnavailableWarningEpochMs >= VIX_WARNING_INTERVAL_MS) {
            log.warn("ANGEL_INDIA_VIX_UNAVAILABLE: returning lastKnownVix={}", lastKnownVix);
            lastUnavailableWarningEpochMs = nowWarn;
        }
    }

    public synchronized double getIndiaVix() {
        long now = System.currentTimeMillis();

        // If cache is stale and no fetch in progress, trigger one-off fetch or wait for scheduler
        if (now - lastSuccessfulFetchEpochMs >= VIX_CACHE_MS || lastKnownVix <= 0.0) {
            if (now - lastFetchAttemptEpochMs >= VIX_RETRY_DELAY_MS) {
                refreshVix();
            }
        }

        return lastKnownVix > 0.0 ? lastKnownVix : fallbackVix;
    }

    private Optional<Double> fetchVixFromYahoo() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response =
                restTemplate.exchange(YAHOO_VIX_URL, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Pattern pattern = Pattern.compile("\"regularMarketPrice\":\\s*([0-9.]+)");
                Matcher matcher = pattern.matcher(response.getBody());
                if (matcher.find()) {
                    return Optional.of(Double.parseDouble(matcher.group(1)));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch VIX from Yahoo Finance: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
