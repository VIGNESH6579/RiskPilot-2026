package com.riskpilot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import java.util.Map;

@Slf4j
@Service
public class VixService {

    @Value("${RISK_INDIA_VIX_TOKEN:}")
    private String indiaVixToken;

    @Value("${RISK_API_KEY}")
    private String apiKey;

    private final RestTemplate restTemplate;
    
    // Cache management
    private volatile Double lastKnownVix = null;
    private volatile long lastFetchTime = 0;
    private static final long CACHE_DURATION_MS = 60000; // 1 minute
    private static final long STALE_CACHE_MAX_MS = 300000; // 5 minutes max stale
    
    // Circuit breaker
    private volatile int consecutiveFailures = 0;
    private static final int MAX_FAILURES_BEFORE_ALERT = 3;

    public VixService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (indiaVixToken == null || indiaVixToken.isBlank()) {
            log.error("❌❌❌ CRITICAL: India VIX token NOT configured!");
            log.error("Set RISK_INDIA_VIX_TOKEN environment variable");
            log.error("System will FAIL on first VIX fetch attempt");
            // Don't throw here - let it fail on first fetch for better error message
        } else {
            log.info("✅ VIX Service configured - Token: {} **** ", 
                indiaVixToken.substring(0, Math.min(4, indiaVixToken.length())));
        }
    }

    public double getIndiaVix() {
        // STRICT: No fallback values allowed
        if (indiaVixToken == null || indiaVixToken.isBlank()) {
            throw new IllegalStateException(
                "❌ CRITICAL ERROR: India VIX token NOT configured.\n" +
                "Set environment variable: RISK_INDIA_VIX_TOKEN\n" +
                "NO FALLBACK VALUES PROVIDED - System requires real-time data."
            );
        }

        long now = System.currentTimeMillis();
        
        // Return fresh cached value
        if (lastKnownVix != null && (now - lastFetchTime) < CACHE_DURATION_MS) {
            log.debug("Using cached VIX: {} (age: {}s)", 
                lastKnownVix, (now - lastFetchTime) / 1000);
            return lastKnownVix;
        }

        try {
            // Fetch from Angel One
            double vix = fetchVixFromAngelOne();
            
            // Validate
            if (vix <= 0 || vix > 100) {
                throw new RuntimeException("Invalid VIX value: " + vix);
            }
            
            // Update cache
            lastKnownVix = vix;
            lastFetchTime = now;
            consecutiveFailures = 0;
            
            log.info("✅ India VIX fetched: {} (fresh)", vix);
            return vix;
            
        } catch (Exception e) {
            consecutiveFailures++;
            log.error("❌ VIX fetch failed (attempt {}): {}", 
                consecutiveFailures, e.getMessage());
            
            // Use stale cache only if reasonably recent
            if (lastKnownVix != null && (now - lastFetchTime) < STALE_CACHE_MAX_MS) {
                long ageSeconds = (now - lastFetchTime) / 1000;
                log.warn("⚠️ Using STALE VIX cache (age: {}s): {}", 
                    ageSeconds, lastKnownVix);
                
                if (consecutiveFailures >= MAX_FAILURES_BEFORE_ALERT) {
                    log.error("🚨 VIX API has failed {} times consecutively!", 
                        consecutiveFailures);
                }
                
                return lastKnownVix;
            }
            
            // FAIL FAST - No hardcoded fallbacks
            throw new RuntimeException(
                "❌ Cannot fetch India VIX and no valid cache available.\n" +
                "Consecutive failures: " + consecutiveFailures + "\n" +
                "System cannot proceed without real-time VIX data.", e
            );
        }
    }

    private double fetchVixFromAngelOne() {
        String url = String.format(
            "https://apiconnect.angelbroking.com/rest/secure/angelbroking/market/v1/quote/?symboltoken=%s&exchange=NSE",
            indiaVixToken
        );
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        
        HttpEntity<String> entity = new HttpEntity<>(headers);
        
        ResponseEntity<Map> response = restTemplate.exchange(
            url, 
            HttpMethod.GET, 
            entity, 
            Map.class
        );
        
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("API returned: " + response.getStatusCode());
        }
        
        return extractVixFromResponse(response.getBody());
    }

    @SuppressWarnings("unchecked")
    private double extractVixFromResponse(Map<String, Object> response) {
        try {
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            Object ltpObj = data.get("ltp");
            
            if (ltpObj instanceof Number) {
                return ((Number) ltpObj).doubleValue();
            } else if (ltpObj instanceof String) {
                return Double.parseDouble((String) ltpObj);
            } else {
                throw new RuntimeException("Unexpected LTP type: " + 
                    (ltpObj != null ? ltpObj.getClass() : "null"));
            }
        } catch (Exception e) {
            log.error("Failed to parse VIX response: {}", response);
            throw new RuntimeException("VIX parsing failed", e);
        }
    }

    // Health check methods
    public boolean isVixDataFresh() {
        return lastKnownVix != null && 
               (System.currentTimeMillis() - lastFetchTime) < CACHE_DURATION_MS;
    }

    public Long getLastFetchAgeSeconds() {
        if (lastFetchTime == 0) return null;
        return (System.currentTimeMillis() - lastFetchTime) / 1000;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Double getLastKnownVix() {
        return lastKnownVix;
    }
}
