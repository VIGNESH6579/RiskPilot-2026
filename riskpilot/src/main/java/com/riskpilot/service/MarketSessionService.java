package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSessionService {

    private final RiskPilotProperties properties;

    /**
     * Static NSE trading-holiday calendar. Compiled from official NSE
     * "Trading Holidays" notices for the relevant year(s). Operators MUST
     * keep this list current — festival dates that vary year-to-year
     * (Diwali, Eid, Holi, Ram Navami, etc.) cannot be inferred.
     *
     * For dates not yet known at compile time, append additions via
     * {@code riskpilot.market.additional-holidays} env / config.
     *
     * Sources:
     *  - NSE 2025 Trading Holidays (official notice).
     *  - Republic-of-India fixed-date holidays observed by NSE in any year
     *    (Republic Day Jan 26, Independence Day Aug 15, Gandhi Jayanti
     *    Oct 2, Christmas Dec 25 — observed when not on a weekend).
     */
    private static final Set<LocalDate> NSE_HOLIDAYS = new HashSet<>();
    static {
        // ── 2025 (NSE published) ───────────────────────────────────────────
        NSE_HOLIDAYS.add(LocalDate.of(2025, 2, 26));   // Mahashivratri
        NSE_HOLIDAYS.add(LocalDate.of(2025, 3, 14));   // Holi
        NSE_HOLIDAYS.add(LocalDate.of(2025, 3, 31));   // Eid-Ul-Fitr (Ramzan Id)
        NSE_HOLIDAYS.add(LocalDate.of(2025, 4, 10));   // Mahavir Jayanti
        NSE_HOLIDAYS.add(LocalDate.of(2025, 4, 14));   // Dr. Ambedkar Jayanti
        NSE_HOLIDAYS.add(LocalDate.of(2025, 4, 18));   // Good Friday
        NSE_HOLIDAYS.add(LocalDate.of(2025, 5, 1));    // Maharashtra Day
        NSE_HOLIDAYS.add(LocalDate.of(2025, 8, 15));   // Independence Day
        NSE_HOLIDAYS.add(LocalDate.of(2025, 8, 27));   // Ganesh Chaturthi
        NSE_HOLIDAYS.add(LocalDate.of(2025, 10, 2));   // Mahatma Gandhi Jayanti
        NSE_HOLIDAYS.add(LocalDate.of(2025, 10, 21));  // Diwali Laxmi Pujan (special muhurat — full-day holiday for regular session)
        NSE_HOLIDAYS.add(LocalDate.of(2025, 10, 22));  // Diwali Balipratipada
        NSE_HOLIDAYS.add(LocalDate.of(2025, 11, 5));   // Prakash Gurpurb of Guru Nanak Dev
        NSE_HOLIDAYS.add(LocalDate.of(2025, 12, 25));  // Christmas

        // ── 2026 (fixed Republic-of-India dates only — verify festival
        //         dates against NSE's official 2026 notice and add them
        //         via riskpilot.market.additional-holidays) ────────────────
        NSE_HOLIDAYS.add(LocalDate.of(2026, 1, 26));   // Republic Day (Mon)
        // Aug 15, 2026 is a Saturday → already weekend-blocked.
        NSE_HOLIDAYS.add(LocalDate.of(2026, 10, 2));   // Gandhi Jayanti (Fri)
        NSE_HOLIDAYS.add(LocalDate.of(2026, 12, 25));  // Christmas (Fri)
    }

    private final Set<LocalDate> additionalHolidays = new HashSet<>();

    @PostConstruct
    void loadAdditionalHolidays() {
        for (String raw : properties.getMarket().getAdditionalHolidays()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                additionalHolidays.add(LocalDate.parse(raw.trim()));
            } catch (DateTimeParseException ex) {
                log.warn("Ignoring un-parseable additional holiday '{}': {}", raw, ex.getMessage());
            }
        }
        if (!additionalHolidays.isEmpty()) {
            log.info("Loaded {} additional NSE holidays from config", additionalHolidays.size());
        }
    }

    public boolean isMarketOpen() {
        return isMarketOpen(now());
    }

    public boolean isMarketOpen(Instant timestamp) {
        ZonedDateTime ist = toMarketTime(timestamp);
        if (!isTradingDay(ist.toLocalDate())) {
            return false;
        }
        LocalTime localTime = ist.toLocalTime();
        return !localTime.isBefore(marketOpen()) && localTime.isBefore(marketClose());
    }

    public boolean isMarketOpen(LocalDateTime timestamp) {
        return isMarketOpen(timestamp.atZone(zoneId()).toInstant());
    }

    /**
     * True if the given calendar date is a trading day on NSE: not a
     * weekend (when {@code weekendsClosed=true}) and not in either the
     * static or operator-supplied holiday set.
     */
    public boolean isTradingDay(LocalDate date) {
        if (properties.getMarket().isWeekendsClosed()) {
            DayOfWeek dow = date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                return false;
            }
        }
        if (NSE_HOLIDAYS.contains(date) || additionalHolidays.contains(date)) {
            return false;
        }
        return true;
    }

    public boolean isHoliday(LocalDate date) {
        return !isTradingDay(date);
    }

    public Instant now() {
        return Instant.now();
    }

    public ZonedDateTime nowIst() {
        return toMarketTime(now());
    }

    public ZoneId zoneId() {
        return ZoneId.of(properties.getMarket().getZone());
    }

    public ZonedDateTime toMarketTime(Instant timestamp) {
        return ZonedDateTime.ofInstant(timestamp, zoneId());
    }

    public boolean canEnterNewTrade() {
        return canEnterNewTrade(now());
    }

    public boolean canEnterNewTrade(Instant timestamp) {
        ZonedDateTime ist = toMarketTime(timestamp);
        if (!isTradingDay(ist.toLocalDate())) {
            return false;
        }
        LocalTime localTime = ist.toLocalTime();
        return isMarketOpen(timestamp) && !localTime.isBefore(entryStart()) && localTime.isBefore(lastEntryCutoff());
    }

    public boolean shouldForceExit() {
        return shouldForceExit(now());
    }

    public boolean shouldForceExit(Instant timestamp) {
        LocalTime localTime = toMarketTime(timestamp).toLocalTime();
        return !localTime.isBefore(forceExitTime());
    }

    public boolean isTradingSessionActive(Instant timestamp) {
        ZonedDateTime ist = toMarketTime(timestamp);
        if (!isTradingDay(ist.toLocalDate())) {
            return false;
        }
        LocalTime localTime = ist.toLocalTime();
        return !localTime.isBefore(marketOpen()) && localTime.isBefore(sessionEnd());
    }

    public LocalDate sessionDate(Instant timestamp) {
        return toMarketTime(timestamp).toLocalDate();
    }

    public Instant sessionStartInstant(LocalDate sessionDate) {
        return sessionDate.atTime(marketOpen()).atZone(zoneId()).toInstant();
    }

    public String marketStatus() {
        if (!isTradingDay(nowIst().toLocalDate())) {
            return "HOLIDAY";
        }
        return isMarketOpen() ? "OPEN" : "CLOSED";
    }

    private LocalTime marketOpen() {
        return LocalTime.parse(properties.getMarket().getOpen());
    }

    private LocalTime marketClose() {
        return LocalTime.parse(properties.getMarket().getClose());
    }

    private LocalTime entryStart() {
        return LocalTime.parse(properties.getSession().getEntryStart());
    }

    private LocalTime lastEntryCutoff() {
        return LocalTime.parse(properties.getSession().getLastEntryCutoff());
    }

    private LocalTime forceExitTime() {
        return LocalTime.parse(properties.getSession().getForceExit());
    }

    private LocalTime sessionEnd() {
        return LocalTime.parse(properties.getSession().getEnd());
    }
}
