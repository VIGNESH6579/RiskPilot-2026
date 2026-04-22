package com.riskpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@Service
public class OptionChainService {

    private static final Logger log = LoggerFactory.getLogger(OptionChainService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CACHE_FILE = "option_chain_cache.json";
    private static final Duration NSE_PRIME_TTL = Duration.ofMinutes(10);
    private static final DateTimeFormatter NSE_EXPIRY_FORMAT =
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final CookieStore cookieStore;
    private volatile long lastNsePrimeEpochMs = 0L;
    private final AngelOneMarketDataService angelOneMarketDataService;
    private final DayOfWeek defaultExpiryDay;

    private OptionChainSnapshot lastKnownSnapshot = new OptionChainSnapshot(0, 0, 0.0, "", 0.0, "STALE_CACHE");

    public OptionChainService(
        AngelOneMarketDataService angelOneMarketDataService,
        @Value("${NIFTY_WEEKLY_EXPIRY_DAY:MONDAY}") String expiryDayConfig
    ) {
        this.cookieStore = new BasicCookieStore();
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.defaultExpiryDay = parseExpiryDay(expiryDayConfig);
        HttpClient httpClient = HttpClients.custom()
            .setDefaultCookieStore(cookieStore)
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(6))
                            .setSocketTimeout(Timeout.ofSeconds(8))
                            .build()
                    )
                    .build()
            )
            .evictExpiredConnections()
            .build();

        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);

        this.restTemplate = new RestTemplate(requestFactory);
        this.mapper = new ObjectMapper();
    }

    public OptionChainSnapshot fetchNiftyChain() {

        String url = "https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY";
        boolean marketOpen = isMarketOpenNow();

        try {
            primeNseCookiesIfNeeded();

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            headers.set("Accept", "application/json");
            headers.set("Referer", "https://www.nseindia.com/get-quotes/derivatives?symbol=NIFTY");
            headers.set("Accept-Language", "en-US,en;q=0.9");
            headers.set("Connection", "keep-alive");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getBody() == null) return lastKnownSnapshot;

            JsonNode json = mapper.readTree(response.getBody());
            JsonNode records = json.get("records");
            
            if (records == null || !records.has("data")) {
                return fetchFromAngelThenNseFallback(marketOpen);
            }

            JsonNode dataArray = records.get("data");
            String nearestExpiry = resolveNearestExpiry(records.get("expiryDates"));
            
            double currentSpot = 0.0;
            if (records.has("underlyingValue")) {
                currentSpot = records.get("underlyingValue").asDouble();
            }
            if (currentSpot <= 0.0) {
                return fetchFromAngelThenNseFallback(marketOpen);
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
                String resolvedExpiry = nearestExpiry;
                if (resolvedExpiry == null || resolvedExpiry.isBlank()) {
                    resolvedExpiry = resolveFallbackExpiry();
                }

                double resolvedSpot = currentSpot;
                String resolvedSource = marketOpen ? "LIVE_MARKET" : "PREV_CLOSE_CACHE";
                if (marketOpen) {
                    var angelLtp = angelOneMarketDataService.getNiftyLtp();
                    if (angelLtp.isPresent() && angelLtp.get() > 0.0) {
                        resolvedSpot = angelLtp.get();
                        resolvedSource = "ANGELONE_LTP";
                    }
                }

                double previousClose = lastKnownSnapshot.previousClose() > 0.0
                    ? lastKnownSnapshot.previousClose()
                    : currentSpot;
                lastKnownSnapshot = new OptionChainSnapshot(
                    support,
                    resistance,
                    marketOpen ? resolvedSpot : previousClose,
                    resolvedExpiry,
                    previousClose,
                    resolvedSource
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
            return fetchFromAngelThenNseFallback(marketOpen);
        }
    }

    private void primeNseCookiesIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastNsePrimeEpochMs < NSE_PRIME_TTL.toMillis()) {
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.set("Accept-Language", "en-US,en;q=0.9");
            headers.set("Referer", "https://www.nseindia.com/");
            headers.set("Connection", "keep-alive");

            restTemplate.exchange(
                "https://www.nseindia.com/",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );
            lastNsePrimeEpochMs = now;
        } catch (Exception e) {
            log.warn("NSE cookie prime failed: {}", e.getMessage());
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

    private OptionChainSnapshot fetchFromAngelThenNseFallback(boolean marketOpen) {
        try {
            OptionChainSnapshot angel = fetchFromAngelFallback(marketOpen);
            if (angel.spot() > 0.0) {
                return angel;
            }

            primeNseCookiesIfNeeded();

            OptionChainSnapshot fromEquity = fetchFromNseEquityStockIndices(marketOpen);
            if (fromEquity.spot() > 0.0) {
                lastKnownSnapshot = fromEquity;
                return fromEquity;
            }

            OptionChainSnapshot fromAll = fetchFromNseAllIndices(marketOpen);
            if (fromAll.spot() > 0.0) {
                lastKnownSnapshot = fromAll;
                return fromAll;
            }

            return lastKnownSnapshot;
        } catch (Exception ex) {
            log.warn("NSE index fallback fetch failed: {}", ex.getMessage());
            return lastKnownSnapshot;
        }
    }

    private OptionChainSnapshot fetchFromAngelFallback(boolean marketOpen) {
        try {
            var ltpOpt = angelOneMarketDataService.getNiftyLtp();
            if (ltpOpt.isEmpty() || ltpOpt.get() <= 0.0) {
                return lastKnownSnapshot;
            }
            double ltp = ltpOpt.get();
            double prevClose = lastKnownSnapshot.previousClose() > 0.0 ? lastKnownSnapshot.previousClose() : ltp;
            String expiry = resolveFallbackExpiry();
            OptionChainSnapshot snap = new OptionChainSnapshot(
                0,
                0,
                marketOpen ? ltp : prevClose,
                expiry,
                prevClose,
                marketOpen ? "ANGELONE_LTP" : "ANGELONE_PREV_CLOSE_CACHE"
            );
            lastKnownSnapshot = snap;
            writeCache(snap);
            return snap;
        } catch (Exception e) {
            log.warn("Angel fallback failed: {}", e.getMessage());
            return lastKnownSnapshot;
        }
    }

    private OptionChainSnapshot fetchFromNseEquityStockIndices(boolean marketOpen) {
        String url = "https://www.nseindia.com/api/equity-stockIndices?index=NIFTY%2050";
        try {
            ResponseEntity<String> response =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(defaultNseJsonHeaders()), String.class);
            if (response.getBody() == null) {
                return lastKnownSnapshot;
            }
            JsonNode root = mapper.readTree(response.getBody());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return lastKnownSnapshot;
            }
            JsonNode row = data.get(0);
            double last = row.path("last").asDouble(0.0);
            double prevClose = row.path("previousClose").asDouble(last);
            String expiry = resolveFallbackExpiry();
            return new OptionChainSnapshot(
                0,
                0,
                marketOpen ? last : prevClose,
                expiry,
                prevClose,
                marketOpen ? "NSE_INDEX_LIVE_FALLBACK" : "NSE_INDEX_PREV_CLOSE_FALLBACK"
            );
        } catch (Exception e) {
            log.warn("NSE equity-stockIndices fallback failed: {}", e.getMessage());
            return lastKnownSnapshot;
        }
    }

    private OptionChainSnapshot fetchFromNseAllIndices(boolean marketOpen) {
        String url = "https://www.nseindia.com/api/allIndices";
        try {
            ResponseEntity<String> response =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(defaultNseJsonHeaders()), String.class);
            if (response.getBody() == null) {
                return lastKnownSnapshot;
            }
            JsonNode root = mapper.readTree(response.getBody());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return lastKnownSnapshot;
            }
            for (JsonNode row : data) {
                String name = row.path("index").asText("");
                if ("NIFTY 50".equalsIgnoreCase(name) || "NIFTY".equalsIgnoreCase(name)) {
                    double last = row.path("last").asDouble(0.0);
                    double prevClose = row.path("previousClose").asDouble(last);
                    String expiry = resolveFallbackExpiry();
                    return new OptionChainSnapshot(
                        0,
                        0,
                        marketOpen ? last : prevClose,
                        expiry,
                        prevClose,
                        marketOpen ? "NSE_ALL_INDICES_LIVE_FALLBACK" : "NSE_ALL_INDICES_PREV_CLOSE_FALLBACK"
                    );
                }
            }
            return lastKnownSnapshot;
        } catch (Exception e) {
            log.warn("NSE allIndices fallback failed: {}", e.getMessage());
            return lastKnownSnapshot;
        }
    }

    private HttpHeaders defaultNseJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        headers.set("Accept", "application/json");
        headers.set("Accept-Language", "en-US,en;q=0.9");
        headers.set("Referer", "https://www.nseindia.com/");
        headers.set("Connection", "keep-alive");
        return headers;
    }

    private String resolveFallbackExpiry() {
        String fromSnapshot = lastKnownSnapshot.expiry();
        if (fromSnapshot != null && !fromSnapshot.isBlank()) {
            return fromSnapshot;
        }
        OptionChainSnapshot cached = readCache();
        if (cached != null && cached.expiry() != null && !cached.expiry().isBlank()) {
            return cached.expiry();
        }
        return computeNextThursdayExpiry();
    }

    private String resolveNearestExpiry(JsonNode expiryDates) {
        if (expiryDates == null || !expiryDates.isArray() || expiryDates.isEmpty()) {
            return "";
        }
        LocalDate today = LocalDate.now(IST);
        LocalDate best = null;
        for (JsonNode expiryNode : expiryDates) {
            String raw = expiryNode.asText("");
            if (raw == null || raw.isBlank()) continue;
            LocalDate parsed = parseExpiryDate(raw.trim());
            if (parsed == null) continue;
            if (parsed.isBefore(today)) continue;
            if (best == null || parsed.isBefore(best)) {
                best = parsed;
            }
        }
        return best == null ? "" : best.toString();
    }

    private LocalDate parseExpiryDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException ignored) {
            // try NSE style: 24-Apr-2026
        }
        try {
            return LocalDate.parse(raw, NSE_EXPIRY_FORMAT);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String computeNextThursdayExpiry() {
        LocalDate d = LocalDate.now(IST);
        while (d.getDayOfWeek() != defaultExpiryDay) {
            d = d.plusDays(1);
        }
        if (d.equals(LocalDate.now(IST)) && ZonedDateTime.now(IST).toLocalTime().isAfter(LocalTime.of(15, 30))) {
            d = d.plusDays(7);
        }
        return d.toString();
    }

    private DayOfWeek parseExpiryDay(String raw) {
        if (raw == null || raw.isBlank()) return DayOfWeek.MONDAY;
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ignored) {
            return DayOfWeek.MONDAY;
        }
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
