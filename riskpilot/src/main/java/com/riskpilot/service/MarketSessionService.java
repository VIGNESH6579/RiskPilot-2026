package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Market Session Service for NSE (National Stock Exchange of India) trading calendar.
 * Handles trading hours, holidays, and weekend checks.
 */
@Slf4j
@Service
public class MarketSessionService {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String MARKET_OPEN_TIME = "09:15";
    private static final String MARKET_CLOSE_TIME = "15:30";

    /**
     * NSE Trading Holidays 2026
     * Source: https://www.nseindia.com/resources/exchange-communication-holidays
     */
    private static final Set<LocalDate> NSE_HOLIDAYS_2026 = Set.of(
        LocalDate.of(2026, 1, 26),   // Republic Day
        LocalDate.of(2026, 3, 3),    // Holi
        LocalDate.of(2026, 3, 26),   // Shri Ram Navami
        LocalDate.of(2026, 3, 31),   // Shri Mahavir Jayanti
        LocalDate.of(2026, 4, 3),    // Good Friday
        LocalDate.of(2026, 4, 14),   // Dr. Baba Saheb Ambedkar Jayanti
        LocalDate.of(2026, 5, 1),    // Maharashtra Day
        LocalDate.of(2026, 5, 28),   // Bakri Id
        LocalDate.of(2026, 6, 26),   // Muharram
        LocalDate.of(2026, 9, 14),   // Ganesh Chaturthi
        LocalDate.of(2026, 10, 2),   // Mahatma Gandhi Jayanti
        LocalDate.of(2026, 10, 20),  // Dussehra
        LocalDate.of(2026, 11, 10),  // Diwali-Balipratipada
        LocalDate.of(2026, 11, 24),  // Prakash Gurpurb Sri Guru Nanak Dev
        LocalDate.of(2026, 12, 25)   // Christmas
    );

    /**
     * Check if the market is open at the current time.
     */
    public boolean isMarketOpen() {
        ZonedDateTime now = nowIst();
        
        // Check if it's a trading day
        if (!isTradingDay(now.toLocalDate())) {
            return false;
        }
        
        // Check market hours (09:15 - 15:30 IST)
        int hour = now.getHour();
        int minute = now.getMinute();
        int timeValue = hour * 100 + minute;
        
        return timeValue >= 915 && timeValue < 1530;
    }

    /**
     * Check if the given date is a trading day (not weekend and not holiday).
     */
    public boolean isTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        
        // Weekends are closed
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        
        // Check NSE holidays
        if (NSE_HOLIDAYS_2026.contains(date)) {
            return false;
        }
        
        return true;
    }

    /**
     * Get current time in IST (Asia/Kolkata timezone).
     */
    public ZonedDateTime nowIst() {
        return ZonedDateTime.now(IST_ZONE);
    }

    /**
     * Get the IST timezone.
     */
    public ZoneId zoneId() {
        return IST_ZONE;
    }

    /**
     * Get market open time.
     */
    public String getMarketOpenTime() {
        return MARKET_OPEN_TIME;
    }

    /**
     * Get market close time.
     */
    public String getMarketCloseTime() {
        return MARKET_CLOSE_TIME;
    }

    /**
     * Get the next trading day from the given date.
     */
    public LocalDate getNextTradingDay(LocalDate fromDate) {
        LocalDate nextDay = fromDate.plusDays(1);
        while (!isTradingDay(nextDay)) {
            nextDay = nextDay.plusDays(1);
        }
        return nextDay;
    }

    /**
     * Check if the given date falls within the opening range (first 30 minutes).
     */
    public boolean isOpeningRange(ZonedDateTime time) {
        if (!isTradingDay(time.toLocalDate())) {
            return false;
        }
        int hour = time.getHour();
        int minute = time.getMinute();
        int timeValue = hour * 100 + minute;
        return timeValue >= 915 && timeValue <= 945;
    }
}
