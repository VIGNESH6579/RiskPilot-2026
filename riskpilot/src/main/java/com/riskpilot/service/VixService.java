package com.riskpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class VixService {

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    
    // Cached fallback
    private double lastKnownVix = 15.0;

    public VixService() {
        this.restTemplate = new RestTemplate();
        this.mapper = new ObjectMapper();
    }

    public double getIndiaVix() {
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

            if (response.getBody() == null) return lastKnownVix;

            JsonNode json = mapper.readTree(response.getBody());
            JsonNode data = json.get("data");

            if (data != null && data.isArray()) {
                for (JsonNode obj : data) {
                    if (obj.has("index") && obj.get("index").asText().equalsIgnoreCase("INDIA VIX")) {
                        lastKnownVix = obj.get("last").asDouble();
                        return lastKnownVix;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ NSE VIX API Fetch Failed. Returning localized fallback cache. Err: " + e.getMessage());
        }

        return lastKnownVix;
    }
}
