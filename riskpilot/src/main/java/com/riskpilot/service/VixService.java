package com.riskpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class VixService {
    private static final Logger log = LoggerFactory.getLogger(VixService.class);
    private static final String YAHOO_VIX_URL =
        "https://query1.finance.yahoo.com/v8/finance/chart/%5EINDIAVIX?interval=1d&range=1d";
    private static final long VIX_CACHE_MS = 20_000L;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    private double lastKnownVix = 15.0;
    private long lastSuccessfulFetchEpochMs = 0L;

    public VixService() {
        this.restTemplate = buildRestTemplate();
        this.mapper = new ObjectMapper();
    }

    public synchronized double getIndiaVix() {
        long now = System.currentTimeMillis();
        if (now - lastSuccessfulFetchEpochMs < VIX_CACHE_MS && lastKnownVix > 0.0) {
            return lastKnownVix;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            headers.set("Accept", "application/json");

            ResponseEntity<String> response = restTemplate.exchange(
                YAHOO_VIX_URL,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            if (response.getBody() == null || response.getBody().isBlank()) {
                return lastKnownVix;
            }

            JsonNode meta = mapper.readTree(response.getBody())
                .path("chart")
                .path("result")
                .path(0)
                .path("meta");

            double fetched = meta.path("regularMarketPrice").asDouble(lastKnownVix);
            if (fetched > 0.0) {
                lastKnownVix = fetched;
                lastSuccessfulFetchEpochMs = now;
            }
        } catch (Exception e) {
            log.warn("Yahoo India VIX fetch failed, returning cached value: {}", e.getMessage());
        }

        return lastKnownVix;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }
}
