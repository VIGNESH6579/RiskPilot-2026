package com.riskpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealTimeMarketDataService {

    private final AngelAuthService authService;
    private final AngelTickStreamClient tickStreamClient;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    
    // Real-time data cache with TTL
    private final Map<String, MarketData> realTimeCache = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastUpdate = new AtomicReference<>(Instant.now());
    private static final long CACHE_TTL_SECONDS = 5; // 5-second cache for real-time data
    
    public record MarketData(
        String symbol,
        double ltp,
        double open,
        double high,
        double low,
        double close,
        long volume,
        Instant timestamp,
        boolean isRealTime
    ) {}

    /**
     * Get real-time NIFTY LTP - NO FALLBACKS, NO MOCKS
     */
    public Optional<Double> getNiftyLtp() {
        return getRealTimeQuote("NIFTY", "99926000")
                .map(MarketData::ltp);
    }

    /**
     * Get real-time BankNIFTY LTP - NO FALLBACKS, NO MOCKS
     */
    public Optional<Double> getBankNiftyLtp() {
        return getRealTimeQuote("BANKNIFTY", "99926009")
                .map(MarketData::ltp);
    }

    /**
     * Get comprehensive real-time market data
     */
    public Optional<MarketData> getRealTimeQuote(String symbol, String token) {
        // Check cache first
        MarketData cached = realTimeCache.get(symbol);
        if (cached != null && !isCacheExpired()) {
            log.debug("📊 Using cached real-time data for {}", symbol);
            return Optional.of(cached);
        }

        // Fetch fresh data
        return fetchRealTimeQuote(symbol, token);
    }

    private Optional<MarketData> fetchRealTimeQuote(String symbol, String token) {
        try {
            if (!ensureAuth()) {
                log.error("❌ Authentication failed for real-time data fetch");
                return Optional.empty();
            }

            String jwt = authService.getJwtToken();
            if (jwt == null || jwt.isBlank()) {
                log.error("❌ No JWT token available for real-time data");
                return Optional.empty();
            }

            // Build request for real-time quote
            String url = "https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + jwt);
            headers.set("X-UserType", "USER");
            headers.set("X-SourceID", "WEB");
            headers.set("X-ClientLocalIP", "CLIENT_LOCAL_IP");
            headers.set("X-ClientPublicIP", "CLIENT_PUBLIC_IP");
            headers.set("X-MACAddress", "MAC_ADDRESS");
            headers.set("X-PrivateKey", authService.getApiKey());

            HttpEntity<String> entity = new HttpEntity<>("{\"mode\":\"FULL\",\"exchangeTokens\":{\"NSE\":\"" + token + "\"}}", headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseRealTimeResponse(symbol, response.getBody());
            } else {
                log.error("❌ Real-time quote failed for {}: {}", symbol, response.getStatusCode());
                return Optional.empty();
            }

        } catch (Exception e) {
            log.error("❌ Real-time data fetch error for {}: {}", symbol, e.getMessage());
            // NO FALLBACKS - Return empty to signal real-time data failure
            return Optional.empty();
        }
    }

    private Optional<MarketData> parseRealTimeResponse(String symbol, String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode data = root.path("data").path("fetched").get(0);
            
            if (data.has("ltp")) {
                MarketData marketData = new MarketData(
                    symbol,
                    data.get("ltp").asDouble(),
                    data.get("open").asDouble(),
                    data.get("high").asDouble(),
                    data.get("low").asDouble(),
                    data.get("close").asDouble(),
                    data.get("volume").asLong(),
                    Instant.now(),
                    true
                );
                
                // Update cache
                realTimeCache.put(symbol, marketData);
                lastUpdate.set(Instant.now());
                
                log.debug("📊 Real-time data updated for {}: LTP={}", symbol, marketData.ltp());
                return Optional.of(marketData);
            }
        } catch (Exception e) {
            log.error("❌ Failed to parse real-time response for {}: {}", symbol, e.getMessage());
        }
        return Optional.empty();
    }

    private boolean ensureAuth() {
        if (!authService.isAuthenticated()) {
            return authService.authenticate();
        }
        return true;
    }

    private boolean isCacheExpired() {
        return lastUpdate.get().plusSeconds(CACHE_TTL_SECONDS).isBefore(Instant.now());
    }

    /**
     * Get real-time tick stream status
     */
    public boolean isTickStreamActive() {
        return tickStreamClient.isStreamActive();
    }

    /**
     * Force refresh of real-time data cache
     */
    public void refreshCache() {
        realTimeCache.clear();
        lastUpdate.set(Instant.now().minusSeconds(CACHE_TTL_SECONDS + 1));
        log.info("🔄 Real-time data cache cleared");
    }

    /**
     * Get cache statistics for monitoring
     */
    public Map<String, Object> getCacheStats() {
        return Map.of(
            "cacheSize", realTimeCache.size(),
            "lastUpdate", lastUpdate.get().toString(),
            "isExpired", isCacheExpired(),
            "streamActive", isTickStreamActive()
        );
    }
}
