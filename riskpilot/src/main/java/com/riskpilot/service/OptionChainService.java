package com.riskpilot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

@Service
public class OptionChainService {

    private static final Logger log = LoggerFactory.getLogger(OptionChainService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CACHE_FILE = "option_chain_cache.json";
    private static final DateTimeFormatter NSE_EXPIRY_FORMAT =
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AngelOneMarketDataService angelOneMarketDataService;
    private final DayOfWeek defaultExpiryDay;
    private final String explicitExpiryOverride;

    private volatile OptionChainSnapshot lastKnownSnapshot =
        new OptionChainSnapshot(0, 0, 0.0, "", 0.0, "ANGELONE_UNAVAILABLE", 0L, false);

    public OptionChainService(
        AngelOneMarketDataService angelOneMarketDataService,
        @Value("${NIFTY_WEEKLY_EXPIRY_DAY:TUESDAY}") String expiryDayConfig,
        @Value("${NIFTY_EXPIRY_OVERRIDE:}") String explicitExpiryOverride
    ) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.defaultExpiryDay = parseExpiryDay(expiryDayConfig);
        this.explicitExpiryOverride = explicitExpiryOverride == null ? "" : explicitExpiryOverride.trim();
    }

    public synchronized OptionChainSnapshot fetchNiftyChain() {
        if (isMarketOpenNow()) {
            Optional<OptionChainSnapshot> live = fetchFromAngelLive();
            if (live.isPresent()) {
                return live.get();
            }
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

        return unavailableLiveSnapshot();
    }

    public OptionChainSnapshot getLastKnownSnapshot() {
        return lastKnownSnapshot;
    }

    public long getLastKnownSnapshotAgeMs() {
        long updated = lastKnownSnapshot.updatedEpochMs();
        return updated > 0L ? Math.max(0L, System.currentTimeMillis() - updated) : Long.MAX_VALUE;
    }

    public boolean isMarketOpen() {
        return isMarketOpenNow();
    }

    private Optional<OptionChainSnapshot> fetchFromAngelLive() {
        try {
            Optional<Double> ltpOpt = angelOneMarketDataService.getNiftyLtp();
            if (ltpOpt.isEmpty() || ltpOpt.get() <= 0.0) {
                log.warn("ANGELONE_LTP_UNAVAILABLE: no live NIFTY price returned");
                return Optional.empty();
            }

            double ltp = ltpOpt.get();
            double prevClose = resolvePreviousClose(ltp);
            int support = (int) (Math.floor(ltp / 50.0) * 50);
            int resistance = (int) (Math.ceil(ltp / 50.0) * 50);
            if (resistance == support) {
                resistance += 50;
            }

            OptionChainSnapshot snapshot = new OptionChainSnapshot(
                support,
                resistance,
                ltp,
                resolveFallbackExpiry(),
                prevClose,
                "ANGELONE_LTP",
                System.currentTimeMillis(),
                true
            );
            lastKnownSnapshot = snapshot;
            writeCache(snapshot);
            return Optional.of(snapshot);
        } catch (Exception e) {
            log.warn("ANGELONE_LTP_FETCH_FAILED: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private OptionChainSnapshot unavailableLiveSnapshot() {
        double prevClose = resolvePreviousClose(0.0);
        return new OptionChainSnapshot(
            0,
            0,
            0.0,
            resolveFallbackExpiry(),
            prevClose,
            "ANGELONE_UNAVAILABLE",
            0L,
            false
        );
    }

    private double resolvePreviousClose(double fallback) {
        OptionChainSnapshot current = lastKnownSnapshot;
        if (current.previousClose() > 0.0) {
            return current.previousClose();
        }
        OptionChainSnapshot cached = readCache();
        if (cached != null) {
            if (cached.previousClose() > 0.0) {
                return cached.previousClose();
            }
            if (cached.spot() > 0.0) {
                return cached.spot();
            }
        }
        return fallback;
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

    private String resolveFallbackExpiry() {
        String overridden = resolveExplicitExpiryOverride();
        if (!overridden.isBlank()) {
            return overridden;
        }

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
        } catch (DateTimeParseException ignored) {
            // try NSE style: 24-Apr-2026
        }
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
            return DayOfWeek.TUESDAY;
        }
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ignored) {
            return DayOfWeek.TUESDAY;
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
            int support,
            int resistance,
            double spot,
            String expiry,
            double previousClose,
            String source
        ) {
            this(support, resistance, spot, expiry, previousClose, source, System.currentTimeMillis(), false);
        }
    }
}
