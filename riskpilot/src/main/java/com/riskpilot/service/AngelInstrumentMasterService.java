package com.riskpilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class AngelInstrumentMasterService {

    private static final Logger log = LoggerFactory.getLogger(AngelInstrumentMasterService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Officially referenced by SmartAPI community; used widely for token discovery.
    private static final String MASTER_URL =
        "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json";

    private static final Duration CACHE_TTL = Duration.ofHours(12);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<MasterRow> cached;
    private volatile long cachedAtEpochMs = 0L;

    public Optional<String> resolveNiftyIndexToken() {
        // Many integrations use 99926000 for NIFTY index on NSE; we still try master lookup first.
        return loadMaster()
            .flatMap(rows -> rows.stream()
                .filter(r -> "NSE".equalsIgnoreCase(r.exch_seg))
                .filter(r -> r.symboltoken != null && !r.symboltoken.isBlank())
                .filter(r -> {
                    String s = safeUpper(r.symbol);
                    String n = safeUpper(r.name);
                    return s.contains("NIFTY") || n.contains("NIFTY");
                })
                .filter(r -> {
                    String symbol = safeUpper(r.symbol);
                    return symbol.contains("NIFTY") && symbol.contains("INDEX");
                })
                .map(r -> r.symboltoken)
                .findFirst()
            )
            .or(() -> Optional.of("99926000"));
    }

    public Optional<LocalDate> resolveNearestNiftyWeeklyExpiry() {
        LocalDate today = LocalDate.now(IST);
        return loadMaster()
            .flatMap(rows -> rows.stream()
                .filter(r -> "NFO".equalsIgnoreCase(r.exch_seg))
                .filter(r -> "OPTIDX".equalsIgnoreCase(r.instrumenttype))
                .filter(r -> "NIFTY".equalsIgnoreCase(r.name) || safeUpper(r.symbol).startsWith("NIFTY"))
                .map(r -> parseExpiryDate(r.expiry))
                .filter(Objects::nonNull)
                .filter(d -> !d.isBefore(today))
                .min(Comparator.naturalOrder())
            );
    }

    private Optional<List<MasterRow>> loadMaster() {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAtEpochMs) < CACHE_TTL.toMillis()) {
            return Optional.of(cached);
        }
        try {
            String json = restTemplate.getForObject(MASTER_URL, String.class);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            List<MasterRow> rows = mapper.readValue(json, new TypeReference<>() {});
            cached = rows;
            cachedAtEpochMs = now;
            return Optional.of(rows);
        } catch (Exception e) {
            log.warn("Unable to load Angel instrument master: {}", e.getMessage());
            return Optional.ofNullable(cached);
        }
    }

    private static String safeUpper(String s) {
        return s == null ? "" : s.toUpperCase();
    }

    private static LocalDate parseExpiryDate(String expiry) {
        if (expiry == null || expiry.isBlank()) return null;
        // Master usually uses "27APR2026" format.
        String e = expiry.trim().toUpperCase();
        if (e.length() != 9) return null;
        try {
            int day = Integer.parseInt(e.substring(0, 2));
            String mon = e.substring(2, 5);
            int year = Integer.parseInt(e.substring(5, 9));
            int month = switch (mon) {
                case "JAN" -> 1;
                case "FEB" -> 2;
                case "MAR" -> 3;
                case "APR" -> 4;
                case "MAY" -> 5;
                case "JUN" -> 6;
                case "JUL" -> 7;
                case "AUG" -> 8;
                case "SEP" -> 9;
                case "OCT" -> 10;
                case "NOV" -> 11;
                case "DEC" -> 12;
                default -> 0;
            };
            if (month == 0) return null;
            return LocalDate.of(year, month, day);
        } catch (Exception ignored) {
            return null;
        }
    }

    // Minimal fields used by our resolver (master file has many more).
    public static class MasterRow {
        public String exch_seg;
        public String instrumenttype;
        public String name;
        public String symbol;
        public String symboltoken;
        public String expiry;
    }
}

