package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Market Session Service for NSE trading hours and configured holidays.
 */
@Slf4j
@Service
public class MarketSessionService {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final LocalTime marketOpenTime;
    private final LocalTime marketCloseTime;
    private final LocalTime openingRangeEnd;
    private final Set<LocalDate> marketHolidays;

    public MarketSessionService(
        RiskPilotProperties properties,
        @Value("${NSE_TRADING_HOLIDAYS:}") String configuredHolidays
    ) {
        this.marketOpenTime = parseTime(properties.getSession().getStart(), LocalTime.of(9, 15));
        this.marketCloseTime = parseTime(properties.getSession().getEnd(), LocalTime.of(15, 30));
        this.openingRangeEnd = parseTime(properties.getSession().getOpeningRangeEnd(), LocalTime.of(9, 45));
        this.marketHolidays = parseHolidaySet(configuredHolidays);
        if (marketHolidays.isEmpty()) {
            log.warn("NSE_TRADING_HOLIDAYS is empty; only weekend closures will be applied");
        }
    }

    public boolean isMarketOpen() {
        ZonedDateTime now = nowIst();

        if (!isTradingDay(now.toLocalDate())) {
            return false;
        }

        LocalTime time = now.toLocalTime();
        return !time.isBefore(marketOpenTime) && time.isBefore(marketCloseTime);
    }

    public boolean isTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();

        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }

        return !marketHolidays.contains(date);
    }

    public ZonedDateTime nowIst() {
        return ZonedDateTime.now(IST_ZONE);
    }

    public ZoneId zoneId() {
        return IST_ZONE;
    }

    public String getMarketOpenTime() {
        return marketOpenTime.toString();
    }

    public String getMarketCloseTime() {
        return marketCloseTime.toString();
    }

    public LocalDate getNextTradingDay(LocalDate fromDate) {
        LocalDate nextDay = fromDate.plusDays(1);
        while (!isTradingDay(nextDay)) {
            nextDay = nextDay.plusDays(1);
        }
        return nextDay;
    }

    public boolean isOpeningRange(ZonedDateTime time) {
        if (!isTradingDay(time.toLocalDate())) {
            return false;
        }
        LocalTime localTime = time.toLocalTime();
        return !localTime.isBefore(marketOpenTime) && localTime.isBefore(openingRangeEnd);
    }

    private static LocalTime parseTime(String raw, LocalTime fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : LocalTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static Set<LocalDate> parseHolidaySet(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split("[,\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .map(LocalDate::parse)
            .collect(Collectors.toUnmodifiableSet());
    }
}
