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
import java.net.URI;
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
    private final String explicitExpiryOverride;

    private OptionChainSnapshot lastKnownSnapshot = new OptionChainSnapshot(0, 0, 0.0, "", 0.0, "STALE_CACHE");

    // Yahoo Finance result cache — prevents hitting Yahoo every 2 s
    private volatile OptionChainSnapshot yahooCachedSnapshot = null;
    private volatile long yahooCacheEpochMs = 0L;
    private static final long YAHOO_CACHE_TTL_MS = 30_000L; // 30 seconds

    public OptionChainService(
        AngelOneMarketDataService angelOneMarketDataService,
        @Value("${NIFTY_WEEKLY_EXPIRY_DAY:TUESDAY}") String expiryDayConfig,
        @Value("${NIFTY_EXPIRY_OVERRIDE:}") String explicitExpiryOverride
    ) {
        this.cookieStore = new BasicCookieStore();
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.defaultExpiryDay = parseExpiryDay(expiryDayConfig);
        this.explicitExpiryOverride = explicitExpiryOverride == null ? "" : explicitExpiryOverride.trim();
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
        // NSE India permanently blocked from cloud IPs (Akamai WAF — 403/timeout).
        // Primary: Yahoo Finance ^NSEI  |  Fallback: Angel One LTP  |  Last: cache.
        boolean marketOpen = isMarketOpenNow();
        return fetchFromYahooFinance(marketOpen);
    }

    private OptionChainSnapshot fetchFromYahooFinance(boolean marketOpen) {
        // Return cached result if fresh (avoids hammering Yahoo Finance every 2 s)
        long now = System.currentTimeMillis();
        if (yahooCachedSnapshot != null && (now - yahooCacheEpochMs) < YAHOO_CACHE_TTL_MS) {
            return yahooCachedSnapshot;
        }

        // Use UriComponentsBuilder with build(true) to avoid double-encoding %5E -> %255E
        String rawUrl = "https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?interval=1d&range=5d";
        URI uri = org.springframework.web.util.UriComponentsBuilder
            .fromUriString(rawUrl).build(true).toUri();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "application/json");

            ResponseEntity<String> response =
                restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (response.getBody() == null) return fetchFromAngelThenNseFallback(marketOpen);

            JsonNode meta = mapper.readTree(response.getBody())
                .path("chart").path("result").path(0).path("meta");

            // regularMarketPrice = live price during hours, OR today's close after hours
            // chartPreviousClose = previous trading day's close (use only for prevClose field)
            double live      = meta.path("regularMarketPrice").asDouble(0.0);
            double prevClose = meta.path("chartPreviousClose").asDouble(live);

            if (live <= 0.0) return fetchFromAngelThenNseFallback(marketOpen);

            // Always use regularMarketPrice as spot (live or today's close, never yesterday)
            double spot   = live;
            String source = marketOpen ? "YAHOO_LIVE" : "YAHOO_TODAY_CLOSE";

            // During market hours, prefer Angel One LTP for tick-level accuracy
            if (marketOpen) {
                var angelLtp = angelOneMarketDataService.getNiftyLtp();
                if (angelLtp.isPresent() && angelLtp.get() > 0.0) {
                    spot   = angelLtp.get();
                    source = "ANGELONE_LTP";
                }
            }

            // S/R: nearest 50-point structural levels
            int support    = (int)(Math.floor(spot / 50.0) * 50);
            int resistance = (int)(Math.ceil (spot / 50.0) * 50);
            if (resistance == support) resistance += 50;

            String expiry = resolveFallbackExpiry();
            lastKnownSnapshot = new OptionChainSnapshot(support, resistance, spot, expiry, prevClose, source);
            writeCache(lastKnownSnapshot);

            // Update Yahoo cache
            yahooCachedSnapshot = lastKnownSnapshot;
            yahooCacheEpochMs   = now;

            log.info("Nifty spot via Yahoo: spot={} prevClose={} support={} resistance={} source={}",
                spot, prevClose, support, resistance, source);
            return lastKnownSnapshot;

        } catch (Exception e) {
            log.warn("Yahoo Finance fetch failed, trying Angel fallback: {}", e.getMessage());
            return fetchFromAngelThenNseFallback(marketOpen);
        }
    }

    // NSE cookie priming removed — NSE is blocked from cloud IPs.
    @SuppressWarnings("unused")
    private void primeNseCookiesIfNeeded() { /* no-op: NSE blocked */ }

    private OptionChainSnapshot fromCacheAsPreviousClose() {
        OptionChainSnapshot cached = readCache();
        if (cached == null) {
            return lastKnownSnapshot;
        }
        if (isMarketOpenNow()) {
            return cached;
        }
        // Use cached spot directly — NSE last-value call is blocked from cloud IPs
        double marketCloseSpot = cached.spot() > 0.0 ? cached.spot() : lastKnownSnapshot.spot();
        double prevClose = cached.previousClose() > 0.0 ? cached.previousClose() : marketCloseSpot;
        return new OptionChainSnapshot(
            cached.support(),
            cached.resistance(),
            marketCloseSpot,
            cached.expiry(),
            prevClose,
            "MARKET_CLOSED_CACHE"
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
            // All NSE endpoints blocked from cloud — return last known snapshot
            log.warn("All data sources exhausted. Using last known snapshot: spot={}", lastKnownSnapshot.spot());
            return lastKnownSnapshot;
        } catch (Exception ex) {
            log.warn("Angel fallback failed: {}", ex.getMessage());
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
            // Use cached prevClose — NSE allIndices is blocked from cloud IPs
            double prevClose = lastKnownSnapshot.previousClose() > 0.0 ? lastKnownSnapshot.previousClose() : ltp;
            String expiry = resolveFallbackExpiry();
            int support    = (int)(Math.floor(ltp / 50.0) * 50);
            int resistance = (int)(Math.ceil (ltp / 50.0) * 50);
            if (resistance == support) resistance += 50;
            OptionChainSnapshot snap = new OptionChainSnapshot(
                support, resistance,
                marketOpen ? ltp : prevClose,
                expiry, prevClose,
                marketOpen ? "ANGELONE_LTP" : "ANGELONE_PREV_CLOSE"
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

    // fetchNsePreviousClose: replaced with Yahoo Finance — NSE allIndices blocked from cloud
    private java.util.Optional<Double> fetchNsePreviousClose() {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?interval=1d&range=1d";
        try {
            HttpHeaders h = new HttpHeaders();
            h.set("User-Agent", "Mozilla/5.0"); h.set("Accept", "application/json");
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(h), String.class);
            if (resp.getBody() == null) return java.util.Optional.empty();
            double pc = mapper.readTree(resp.getBody())
                .path("chart").path("result").path(0).path("meta")
                .path("chartPreviousClose").asDouble(0.0);
            return pc > 0.0 ? java.util.Optional.of(pc) : java.util.Optional.empty();
        } catch (Exception e) {
            log.debug("Yahoo prevClose fetch failed: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    // fetchNseLastValue: replaced with Yahoo Finance — NSE allIndices blocked from cloud
    private java.util.Optional<Double> fetchNseLastValue() {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?interval=1d&range=1d";
        try {
            HttpHeaders h = new HttpHeaders();
            h.set("User-Agent", "Mozilla/5.0"); h.set("Accept", "application/json");
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(h), String.class);
            if (resp.getBody() == null) return java.util.Optional.empty();
            double lv = mapper.readTree(resp.getBody())
                .path("chart").path("result").path(0).path("meta")
                .path("regularMarketPrice").asDouble(0.0);
            return lv > 0.0 ? java.util.Optional.of(lv) : java.util.Optional.empty();
        } catch (Exception e) {
            log.debug("Yahoo lastValue fetch failed: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private String resolveFallbackExpiry() {
        String overridden = resolveExplicitExpiryOverride();
        if (!overridden.isBlank()) {
            return overridden;
        }
        String computed = computeNextThursdayExpiry();
        LocalDate today = LocalDate.now(IST);

        // Use cached/snapshot expiry only when it matches configured weekly-expiry weekday.
        String fromSnapshot = lastKnownSnapshot.expiry();
        LocalDate snapshotDate = parseExpiryDate(fromSnapshot == null ? "" : fromSnapshot.trim());
        if (snapshotDate != null && !snapshotDate.isBefore(today) && snapshotDate.getDayOfWeek() == defaultExpiryDay) {
            return snapshotDate.toString();
        }

        OptionChainSnapshot cached = readCache();
        if (cached != null && cached.expiry() != null && !cached.expiry().isBlank()) {
            LocalDate cachedDate = parseExpiryDate(cached.expiry().trim());
            if (cachedDate != null && !cachedDate.isBefore(today) && cachedDate.getDayOfWeek() == defaultExpiryDay) {
                return cachedDate.toString();
            }
        }

        return computed;
    }

    private String resolveNearestExpiry(JsonNode expiryDates) {
        String overridden = resolveExplicitExpiryOverride();
        if (!overridden.isBlank()) {
            return overridden;
        }
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

    private String resolveExplicitExpiryOverride() {
        LocalDate parsed = parseExpiryDate(explicitExpiryOverride);
        return parsed == null ? "" : parsed.toString();
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
        if (raw == null || raw.isBlank()) return DayOfWeek.TUESDAY;
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ignored) {
            return DayOfWeek.TUESDAY;
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
