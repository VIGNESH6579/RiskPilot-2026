package com.riskpilot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

import java.time.LocalDate;

import java.time.ZoneId;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

@Service
public class OptionChainService {

    private static final Logger log = LoggerFactory.getLogger(OptionChainService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CACHE_FILE = "option_chain_cache.json";
    private static final long ANGEL_QUOTE_CACHE_MS = 3_000L;
    private static final long ANGEL_WARNING_INTERVAL_MS = 60_000L;
    private static final DateTimeFormatter NSE_EXPIRY_FORMAT =
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AngelOneMarketDataService angelOneMarketDataService;
    private final MarketSessionService marketSessionService;
    private final DayOfWeek defaultExpiryDay;
    private final String explicitExpiryOverride;

    private volatile OptionChainSnapshot lastKnownSnapshot =
        new OptionChainSnapshot(0, 0, 0.0, "", 0.0, "MARKET_CLOSED_NO_CACHE", 0L, false);
    private long lastAngelWarningEpochMs = 0L;

    public OptionChainService(
        AngelOneMarketDataService angelOneMarketDataService,
        MarketSessionService marketSessionService,
        // BUG-FIX: NIFTY weekly expiry is THURSDAY, not TUESDAY.
        // render.yaml sets NIFTY_WEEKLY_EXPIRY_DAY=THURSDAY; this default
        // ensures correctness even if the env var is absent.
        @Value("${NIFTY_WEEKLY_EXPIRY_DAY:THURSDAY}") String expiryDayConfig,
        @Value("${NIFTY_EXPIRY_OVERRIDE:}") String explicitExpiryOverride
    ) {
        this.marketSessionService = marketSessionService;
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.defaultExpiryDay = parseExpiryDay(expiryDayConfig);
        this.explicitExpiryOverride = explicitExpiryOverride == null ? "" : explicitExpiryOverride.trim();
    }

    public synchronized OptionChainSnapshot fetchNiftyChain() {
        boolean marketOpen = marketSessionService.isMarketOpen();
        OptionChainSnapshot recent = recentAngelSnapshot(marketOpen);
        if (recent != null) {
            return recent;
        }

        Optional<OptionChainSnapshot> quote = fetchFromAngelQuote(marketOpen);
        if (quote.isPresent()) {
            return quote.get();
        }

        if (marketOpen) {
            return unavailableLiveSnapshot();
        }

        OptionChainSnapshot cached = readCache();
        if (cached != null && cached.spot() > 0.0) {
            OptionChainSnapshot closed = new OptionChainSnapshot(
                cached.support(),
                cached.resistance(),
                cached.spot(),
                resolveFallbackExpiry(),
                cached.previousClose() > 0.0 ? cached.previousClose() : cached.spot(),
                "MARKET_CLOSED_CACHE",
                cached.updatedEpochMs(),
                false
            );
            lastKnownSnapshot = closed;
            return closed;
        }

        return closedMarketNoCacheSnapshot();
    }

    public OptionChainSnapshot getLastKnownSnapshot() {
        return lastKnownSnapshot;
    }

    public long getLastKnownSnapshotAgeMs() {
        long updated = lastKnownSnapshot.updatedEpochMs();
        return updated > 0L ? Math.max(0L, System.currentTimeMillis() - updated) : Long.MAX_VALUE;
    }

    public boolean isMarketOpen() {
        return marketSessionService.isMarketOpen();
    }

    private OptionChainSnapshot recentAngelSnapshot(boolean marketOpen) {
        OptionChainSnapshot current = lastKnownSnapshot;
        if (current.spot() <= 0.0) return null;
        if (current.live() != marketOpen) return null;
        if (!current.source().startsWith("ANGELONE_LTP")) return null;
        long ageMs = System.currentTimeMillis() - current.updatedEpochMs();
        return ageMs >= 0L && ageMs < ANGEL_QUOTE_CACHE_MS ? current : null;
    }

    private Optional<OptionChainSnapshot> fetchFromAngelQuote(boolean marketOpen) {
        try {
            Optional<Double> ltpOpt = angelOneMarketDataService.getNiftyLtp();
            if (ltpOpt.isEmpty() || ltpOpt.get() <= 0.0) {
                warnAngel("ANGELONE_LTP_UNAVAILABLE: no NIFTY price returned from Angel One");
                return Optional.empty();
            }

            double ltp = ltpOpt.get();
            double prevClose = resolvePreviousClose(ltp);
            int support = (int) (Math.floor(ltp / 50.0) * 50);
            int resistance = (int) (Math.ceil(ltp / 50.0) * 50);
            if (resistance == support) resistance += 50;

            OptionChainSnapshot snapshot = new OptionChainSnapshot(
                support, resistance, ltp,
                resolveFallbackExpiry(),
                prevClose,
                marketOpen ? "ANGELONE_LTP" : "ANGELONE_LTP_CLOSED",
                System.currentTimeMillis(),
                marketOpen
            );
            lastKnownSnapshot = snapshot;
            writeCache(snapshot);
            return Optional.of(snapshot);
        } catch (Exception e) {
            warnAngel("ANGELONE_LTP_FETCH_FAILED: " + e.getMessage());
            return Optional.empty();
        }
    }

    private void warnAngel(String message) {
        long now = System.currentTimeMillis();
        if (now - lastAngelWarningEpochMs >= ANGEL_WARNING_INTERVAL_MS) {
            log.warn(message);
            lastAngelWarningEpochMs = now;
        }
    }

    private OptionChainSnapshot unavailableLiveSnapshot() {
        double prevClose = resolvePreviousClose(0.0);
        OptionChainSnapshot unavailable = new OptionChainSnapshot(
            0, 0, 0.0, resolveFallbackExpiry(), prevClose, "ANGELONE_UNAVAILABLE", 0L, false
        );
        lastKnownSnapshot = unavailable;
        return unavailable;
    }

    private OptionChainSnapshot closedMarketNoCacheSnapshot() {
        double prevClose = resolvePreviousClose(0.0);
        OptionChainSnapshot closed = new OptionChainSnapshot(
            0, 0, 0.0, resolveFallbackExpiry(), prevClose, "MARKET_CLOSED_NO_CACHE", 0L, false
        );
        lastKnownSnapshot = closed;
        return closed;
    }

    private double resolvePreviousClose(double fallback) {
        OptionChainSnapshot current = lastKnownSnapshot;
        if (current.previousClose() > 0.0) return current.previousClose();
        OptionChainSnapshot cached = readCache();
        if (cached != null) {
            if (cached.previousClose() > 0.0) return cached.previousClose();
            if (cached.spot() > 0.0) return cached.spot();
        }
        return fallback;
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
        if (!file.exists()) return null;
        try {
            return mapper.readValue(file, OptionChainSnapshot.class);
        } catch (IOException e) {
            log.warn("Unable to read option-chain cache: {}", e.getMessage());
            return null;
        }
    }

    private String resolveFallbackExpiry() {
        String overridden = resolveExplicitExpiryOverride();
        if (!overridden.isBlank()) return overridden;

        LocalDate today = LocalDate.now(IST);
        OptionChainSnapshot current = lastKnownSnapshot;
        LocalDate snapshotDate = parseExpiryDate(current.expiry() == null ? "" : current.expiry().trim());
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

        return computeNextExpiry();
    }

    private String resolveExplicitExpiryOverride() {
        LocalDate parsed = parseExpiryDate(explicitExpiryOverride);
        return parsed == null ? "" : parsed.toString();
    }

    private LocalDate parseExpiryDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException ignored) {}
        try {
            return LocalDate.parse(raw, NSE_EXPIRY_FORMAT);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String computeNextExpiry() {
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
        if (raw == null || raw.isBlank()) {
            // BUG-FIX: NIFTY weekly expiry is THURSDAY
            return DayOfWeek.THURSDAY;
        }
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ignored) {
            return DayOfWeek.THURSDAY;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionChainSnapshot(
        int support,
        int resistance,
        double spot,
        String expiry,
        double previousClose,
        String source,
        long updatedEpochMs,
        boolean live
    ) {
        public OptionChainSnapshot(
            int support, int resistance, double spot, String expiry,
            double previousClose, String source
        ) {
            this(support, resistance, spot, expiry, previousClose, source, System.currentTimeMillis(), false);
        }
    }
}
