package com.riskpilot.service;

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

@Service
public class AngelOneMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(AngelOneMarketDataService.class);
    private static final String QUOTE_URL = "https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/";
    private static final String NIFTY_INDEX_TOKEN = "99926000";

    private final AngelAuthService authService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public AngelOneMarketDataService(AngelAuthService authService) {
        this.authService = authService;
    }

    public Optional<Double> getNiftyLtp() {
        try {
            if (!ensureAuth()) {
                return Optional.empty();
            }
            String jwt = authService.getJwtToken();
            if (jwt == null || jwt.isBlank()) return Optional.empty();

            Optional<Double> firstTry = fetchLtpWithJwt(jwt);
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
            return fetchLtpWithJwt(refreshedJwt);
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

    private Optional<Double> fetchLtpWithJwt(String jwt) throws Exception {
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
        return ltp > 0.0 ? Optional.of(ltp) : Optional.empty();
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
}

