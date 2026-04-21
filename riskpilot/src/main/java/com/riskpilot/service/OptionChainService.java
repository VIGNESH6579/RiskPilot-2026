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

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
public class OptionChainService {

    private static final Logger log = LoggerFactory.getLogger(OptionChainService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CACHE_FILE = "option_chain_cache.json";

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    private OptionChainSnapshot lastKnownSnapshot = new OptionChainSnapshot(0, 0, 0.0, "", 0.0, "STALE_CACHE");

    public OptionChainService() {
        this.restTemplate = new RestTemplate();
        this.mapper = new ObjectMapper();
    }

    public OptionChainSnapshot fetchNiftyChain() {

        String url = "https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY";
        boolean marketOpen = isMarketOpenNow();

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

            if (currentSpot > 0.0) {
                double previousClose = lastKnownSnapshot.previousClose() > 0.0
                    ? lastKnownSnapshot.previousClose()
                    : currentSpot;
                lastKnownSnapshot = new OptionChainSnapshot(
                    support,
                    resistance,
                    marketOpen ? currentSpot : previousClose,
                    nearestExpiry,
                    previousClose,
                    marketOpen ? "LIVE_MARKET" : "PREV_CLOSE_CACHE"
                );
                writeCache(lastKnownSnapshot);
            }
            return marketOpen ? lastKnownSnapshot : fromCacheAsPreviousClose();

        } catch (Exception e) {
            log.warn("NSE option-chain fetch failed: {}", e.getMessage());
            OptionChainSnapshot fallback = fromCacheAsPreviousClose();
            if (fallback.spot() > 0.0) {
                return fallback;
            }
            return fetchFromYahooFallback(marketOpen);
        }
    }

    private OptionChainSnapshot fromCacheAsPreviousClose() {
        OptionChainSnapshot cached = readCache();
        if (cached == null) {
            return lastKnownSnapshot;
        }
        if (isMarketOpenNow()) {
            return cached;
        }
        return new OptionChainSnapshot(
            cached.support(),
            cached.resistance(),
            cached.previousClose() > 0.0 ? cached.previousClose() : cached.spot(),
            cached.expiry(),
            cached.previousClose() > 0.0 ? cached.previousClose() : cached.spot(),
            "PREV_CLOSE_CACHE"
        );
    }

    private boolean isMarketOpenNow() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    private void writeCache(OptionChainSnapshot snapshot) {
        try {
            mapper.writeValue(new File(CACHE_FILE), snapshot);
        } catch (IOException e) {
            log.warn("Unable to persist option-chain cache: {}", e.getMessage());
        }
    }

    private OptionChainSnapshot readCache() {
        File file = new File(CACHE_FILE);
        if (!file.exists()) {
            return null;
        }
        try {
            return mapper.readValue(file, OptionChainSnapshot.class);
        } catch (IOException e) {
            log.warn("Unable to read option-chain cache: {}", e.getMessage());
            return null;
        }
    }

    private OptionChainSnapshot fetchFromYahooFallback(boolean marketOpen) {
        String url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=%5ENSEI";
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, String.class);
            if (response.getBody() == null) {
                return lastKnownSnapshot;
            }
            JsonNode root = mapper.readTree(response.getBody());
            JsonNode result = root.path("quoteResponse").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return lastKnownSnapshot;
            }
            JsonNode q = result.get(0);
            double live = q.path("regularMarketPrice").asDouble(0.0);
            double prevClose = q.path("regularMarketPreviousClose").asDouble(live);
            String expiry = computeNextThursdayExpiry();
            OptionChainSnapshot snap = new OptionChainSnapshot(
                0,
                0,
                marketOpen ? live : prevClose,
                expiry,
                prevClose,
                marketOpen ? "YAHOO_LIVE_FALLBACK" : "YAHOO_PREV_CLOSE_FALLBACK"
            );
            if (snap.spot() > 0.0) {
                lastKnownSnapshot = snap;
            }
            return snap;
        } catch (Exception ex) {
            log.warn("Yahoo fallback fetch failed: {}", ex.getMessage());
            return lastKnownSnapshot;
        }
    }

    private String computeNextThursdayExpiry() {
        LocalDate d = LocalDate.now(IST);
        while (d.getDayOfWeek() != DayOfWeek.THURSDAY) {
            d = d.plusDays(1);
        }
        if (d.equals(LocalDate.now(IST)) && ZonedDateTime.now(IST).toLocalTime().isAfter(LocalTime.of(15, 30))) {
            d = d.plusDays(7);
        }
        return d.toString();
    }

    public record OptionChainSnapshot(
        int support,
        int resistance,
        double spot,
        String expiry,
        double previousClose,
        String source
    ) {}
}
