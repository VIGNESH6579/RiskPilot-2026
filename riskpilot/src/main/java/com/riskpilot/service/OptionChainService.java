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
public class OptionChainService {

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    private OptionChainSnapshot lastKnownSnapshot = new OptionChainSnapshot(0, 0, 0.0, "");

    public OptionChainService() {
        this.restTemplate = new RestTemplate();
        this.mapper = new ObjectMapper();
    }

    public OptionChainSnapshot fetchNiftyChain() {

        String url = "https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            headers.set("Accept", "application/json");
            headers.set("Referer", "https://www.nseindia.com/get-quotes/derivatives?symbol=NIFTY");
            headers.set("Connection", "keep-alive");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getBody() == null) return lastKnownSnapshot;

            JsonNode json = mapper.readTree(response.getBody());
            JsonNode records = json.get("records");
            
            if (records == null || !records.has("data")) return lastKnownSnapshot;

            JsonNode dataArray = records.get("data");
            String nearestExpiry = "";
            JsonNode expiryDates = records.get("expiryDates");
            if (expiryDates != null && expiryDates.isArray() && !expiryDates.isEmpty()) {
                nearestExpiry = expiryDates.get(0).asText("");
            }
            
            double currentSpot = 0.0;
            if (records.has("underlyingValue")) {
                currentSpot = records.get("underlyingValue").asDouble();
            }

            double maxCE = 0, maxPE = 0;
            int resistance = 0, support = 0;

            for (JsonNode strike : dataArray) {
                int strikePrice = strike.get("strikePrice").asInt();

                if (strike.has("CE")) {
                    double ceOI = strike.get("CE").has("openInterest") ? strike.get("CE").get("openInterest").asDouble() : 0;
                    if (ceOI > maxCE) {
                        maxCE = ceOI;
                        resistance = strikePrice;
                    }
                }

                if (strike.has("PE")) {
                    double peOI = strike.get("PE").has("openInterest") ? strike.get("PE").get("openInterest").asDouble() : 0;
                    if (peOI > maxPE) {
                        maxPE = peOI;
                        support = strikePrice;
                    }
                }
            }

            lastKnownSnapshot = new OptionChainSnapshot(support, resistance, currentSpot, nearestExpiry);
            return lastKnownSnapshot;

        } catch (Exception e) {
            System.err.println("⚠️ NSE Options API Fetch Failed. Err: " + e.getMessage());
            return lastKnownSnapshot;
        }
    }

    public record OptionChainSnapshot(int support, int resistance, double spot, String expiry) {}
}
