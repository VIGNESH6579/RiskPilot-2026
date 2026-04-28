package com.riskpilot.service;

import com.riskpilot.exception.MarketDataException;
import com.riskpilot.model.MarketDataTransport;
import com.riskpilot.model.MarketTick;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
public class AngelOneMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(AngelOneMarketDataService.class);
    private static final String QUOTE_URL = "https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/";
    private static final String NIFTY_INDEX_TOKEN = "99926000";

    private final AngelAuthService authService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter[] FEED_TIME_FORMATS = new DateTimeFormatter[] {
        DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss").withZone(IST),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(IST)
    };

    public AngelOneMarketDataService(AngelAuthService authService) {
        this.authService = authService;
    }

    public Optional<Double> getNiftyLtp() {
        return fetchFreshNiftyTick().map(MarketTick::price);
    }

    public Optional<MarketTick> fetchFreshNiftyTick() {
        try {
            if (!ensureAuth()) {
                return Optional.empty();
            }
            String jwt = authService.getJwtToken();
            if (jwt == null || jwt.isBlank()) return Optional.empty();

            Optional<MarketTick> firstTry = fetchTickWithJwt(jwt);
            if (firstTry.isPresent()) {
                return firstTry;
            }
            // Token can expire silently; force a refresh and retry once.
            authService.invalidateSession();
            if (!authService.authenticate()) {
                return Optional.empty();
            }
            String refreshedJwt = authService.getJwtToken();
            if (refreshedJwt == null || refreshedJwt.isBlank()) {
                return Optional.empty();
            }
            return fetchTickWithJwt(refreshedJwt);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                log.warn("Angel quote unauthorized: {}", e.getStatusCode());
            } else {
                log.warn("Angel quote HTTP error {}: {}", e.getStatusCode(), e.getMessage());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("AngelOne LTP fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean ensureAuth() {
        if (!authService.hasCredentials()) {
            log.warn("Angel credentials missing; skipping live LTP");
            return false;
        }
        // If tokens are missing (or expired), attempt a fresh login.
        String jwt = authService.getJwtToken();
        if (jwt == null || jwt.isBlank()) {
            return authService.authenticate();
        }
        return true;
    }

    private Optional<MarketTick> fetchTickWithJwt(String jwt) throws Exception {
        HttpHeaders headers = baseHeaders(jwt);
        Map<String, Object> payload = Map.of(
            "mode", "LTP",
            "exchangeTokens", Map.of("NSE", List.of(NIFTY_INDEX_TOKEN))
        );

        ResponseEntity<String> response =
            restTemplate.exchange(QUOTE_URL, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);

        if (response.getBody() == null || response.getBody().isBlank()) {
            return Optional.empty();
        }

        JsonNode root = mapper.readTree(response.getBody());
        JsonNode fetched = root.path("data").path("fetched");
        if (!fetched.isArray() || fetched.isEmpty()) return Optional.empty();
        JsonNode first = fetched.get(0);

        double ltp = first.path("ltp").asDouble(0.0);
        LocalDateTime exchangeTimestamp = resolveExchangeTimestamp(first);
        if (ltp <= 0.0 || exchangeTimestamp == null) {
            return Optional.empty();
        }

        return Optional.of(MarketTick.of(
            "NIFTY",
            ltp,
            exchangeTimestamp,
            LocalDateTime.now(),
            MarketDataTransport.WEBSOCKET,
            System.currentTimeMillis()
        ));
    }

    private HttpHeaders baseHeaders(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        headers.set("X-PrivateKey", authService.getApiKey());
        headers.set("Authorization", "Bearer " + jwt);
        return headers;
    }

    private LocalDateTime resolveExchangeTimestamp(JsonNode first) {
        if (first == null || first.isMissingNode()) {
            return null;
        }

        JsonNode epochNode = first.path("exchangeFeedTimeEpochMillis");
        if (epochNode.canConvertToLong() && epochNode.asLong() > 0L) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochNode.asLong()), IST);
        }

        JsonNode epochSecondsNode = first.path("exchangeFeedTime");
        if (epochSecondsNode.canConvertToLong() && epochSecondsNode.asLong() > 0L) {
            long raw = epochSecondsNode.asLong();
            long epochMillis = raw > 9_999_999_999L ? raw : raw * 1000L;
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), IST);
        }

        for (String field : List.of("exchFeedTime", "exchangeTime", "lastUpdateTime")) {
            String raw = first.path(field).asText("");
            LocalDateTime parsed = parseTimestamp(raw);
            if (parsed != null) {
                return parsed;
            }
        }

        throw new MarketDataException("ANGEL_QUOTE_TIMESTAMP_MISSING");
    }

    private LocalDateTime parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(raw), IST);
        } catch (DateTimeParseException ignored) {
        }
        for (DateTimeFormatter formatter : FEED_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(raw, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }
}

