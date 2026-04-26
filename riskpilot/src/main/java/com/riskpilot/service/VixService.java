package com.riskpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

@Service
public class VixService {
    private static final Logger log = LoggerFactory.getLogger(VixService.class);
    private static final long VIX_CACHE_MS = 20_000L;
    private static final String YAHOO_VIX_URL =
        "https://query1.finance.yahoo.com/v8/finance/chart/%5EINDIAVIX?interval=1d&range=1d";

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

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            headers.set("Accept", "application/json");
            ResponseEntity<String> response = restTemplate.exchange(
                URI.create(YAHOO_VIX_URL),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            double vix = mapper.readTree(response.getBody())
                .path("chart")
                .path("result")
                .path(0)
                .path("meta")
                .path("regularMarketPrice")
                .asDouble(0.0);
            if (vix > 0.0) {
                lastKnownVix = vix;
                lastSuccessfulFetchEpochMs = now;
                return vix;
            }
        } catch (Exception e) {
            log.warn("Yahoo India VIX fetch failed: {}", e.getMessage());
        }

        return lastKnownVix != null ? lastKnownVix : Double.NaN;
    }
}
