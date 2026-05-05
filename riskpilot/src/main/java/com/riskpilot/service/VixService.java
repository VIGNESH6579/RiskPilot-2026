package com.riskpilot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class VixService {

    @Value("${RISK_INDIA_VIX_TOKEN:}")
    private String indiaVixToken;

    @Value("${RISK_API_KEY}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private volatile Double lastKnownVix = null;
    private volatile long lastFetchTime = 0;
    private static final long CACHE_DURATION_MS = 60000; // 1 minute

    public VixService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public double getIndiaVix() {
        // Validate token is configured
        if (indiaVixToken == null || indiaVixToken.isBlank()) {
            throw new IllegalStateException(
                "India VIX token not configured. Set RISK_INDIA_VIX_TOKEN environment variable."
            );
        }

        // Return cached value if fresh
        long now = System.currentTimeMillis();
        if (lastKnownVix != null && (now - lastFetchTime) < CACHE_DURATION_MS) {
            return lastKnownVix;
        }

        try {
            // Fetch from Angel One API
            String url = String.format(
                "https://apiconnect.angelbroking.com/rest/secure/angelbroking/market/v1/quote/?symboltoken=%s&exchange=NSE",
                indiaVixToken
            );
            
            // Make API call with proper headers
            var headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");
            
            var entity = new org.springframework.http.HttpEntity<>(headers);
            var response = restTemplate.exchange(
                url, 
                org.springframework.http.HttpMethod.GET, 
                entity, 
                java.util.Map.class
            );
            
            Double vix = extractVixFromResponse(response.getBody());
            
            if (vix != null && vix > 0) {
                lastKnownVix = vix;
                lastFetchTime = now;
                log.info("India VIX fetched: {}", vix);
                return vix;
            } else {
                throw new RuntimeException("Invalid VIX value received: " + vix);
            }
            
        } catch (Exception e) {
            log.error("Failed to fetch India VIX from Angel One API", e);
            
            // If we have recent cached data (within 5 minutes), use it
            if (lastKnownVix != null && (now - lastFetchTime) < 300000) {
                log.warn("Using stale cached VIX: {}", lastKnownVix);
                return lastKnownVix;
            }
            
            // Otherwise, fail fast
            throw new RuntimeException("Cannot fetch India VIX and no valid cache available", e);
        }
    }

    private Double extractVixFromResponse(java.util.Map<String, Object> response) {
        try {
            var data = (java.util.Map<String, Object>) response.get("data");
            return Double.parseDouble(data.get("ltp").toString());
        } catch (Exception e) {
            log.error("Failed to parse VIX response", e);
            return null;
        }
    }
}
