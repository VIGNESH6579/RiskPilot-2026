package com.riskpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class VixService {
    private static final Logger log = LoggerFactory.getLogger(VixService.class);
    private static final long VIX_CACHE_MS = 20_000L;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    private Double lastKnownVix;
    private long lastSuccessfulFetchEpochMs = 0L;

    public VixService() {
        this.restTemplate = new RestTemplate();
        this.mapper = new ObjectMapper();
    }

    public synchronized double getIndiaVix() {
        long now = System.currentTimeMillis();
        if (lastKnownVix != null && now - lastSuccessfulFetchEpochMs < VIX_CACHE_MS) {
            return lastKnownVix;
        }

        String url = "https://www.nseindia.com/api/allIndices";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            headers.set("Accept", "application/json");
            headers.set("Referer", "https://www.nseindia.com/");
            headers.set("Connection", "keep-alive");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getBody() == null) return lastKnownVix != null ? lastKnownVix : Double.NaN;

            JsonNode json = mapper.readTree(response.getBody());
            JsonNode data = json.get("data");

            if (data != null && data.isArray()) {
                for (JsonNode obj : data) {
                    if (obj.has("index") && obj.get("index").asText().equalsIgnoreCase("INDIA VIX")) {
                        lastKnownVix = obj.get("last").asDouble();
                        lastSuccessfulFetchEpochMs = now;
                        return lastKnownVix;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("NSE VIX fetch failed, returning last live value if available: {}", e.getMessage());
        }

        return lastKnownVix != null ? lastKnownVix : Double.NaN;
    }
}
