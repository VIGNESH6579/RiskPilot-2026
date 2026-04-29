package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
@RequiredArgsConstructor
public class MarketSessionService {

    private final RiskPilotProperties properties;
    private final NseHolidayCalendar holidayCalendar;

    public boolean isMarketOpen() {
        return isMarketOpen(now());
    }

    /**
     * Audit fix: previously this only checked the time-of-day window, so
     * Saturdays, Sundays, Republic Day, Diwali, etc. all returned {@code true}
     * during 09:15–15:30. Now also gates on weekend + NSE holiday calendar.
     */
    public boolean isMarketOpen(Instant timestamp) {
        ZonedDateTime zdt = toMarketTime(timestamp);
        if (!isTradingDay(zdt.toLocalDate())) {
            return false;
        }
        LocalTime localTime = zdt.toLocalTime();
        return !localTime.isBefore(marketOpen()) && localTime.isBefore(marketClose());
    }

    public boolean isMarketOpen(LocalDateTime timestamp) {
        return isMarketOpen(timestamp.atZone(zoneId()).toInstant());
    }

    /** True when the given IST date is a regular full-day trading session. */
    public boolean isTradingDay(LocalDate date) {
        return holidayCalendar.isTradingDay(date);
    }

    /** True when today (IST) is a regular full-day trading session. */
    public boolean isTradingDay() {
        return isTradingDay(nowIst().toLocalDate());
    }

    /** True when the given IST date is on the published NSE holiday list. */
    public boolean isNseHoliday(LocalDate date) {
        return holidayCalendar.isHoliday(date);
    }

    /** True when the given IST date is a Saturday or Sunday. */
    public boolean isWeekend(LocalDate date) {
        return holidayCalendar.isWeekend(date);
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
        LocalTime localTime = toMarketTime(timestamp).toLocalTime();
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
        LocalTime localTime = toMarketTime(timestamp).toLocalTime();
        return !localTime.isBefore(marketOpen()) && localTime.isBefore(sessionEnd());
    }

    public LocalDate sessionDate(Instant timestamp) {
        return toMarketTime(timestamp).toLocalDate();
    }

    public Instant sessionStartInstant(LocalDate sessionDate) {
        return sessionDate.atTime(marketOpen()).atZone(zoneId()).toInstant();
    }

    public String marketStatus() {
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
