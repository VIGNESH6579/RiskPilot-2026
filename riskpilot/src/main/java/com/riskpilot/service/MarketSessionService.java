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
        // Fixed holidays
        LocalDate.of(2026, 1, 26),   // Republic Day
        LocalDate.of(2026, 10, 2),   // Gandhi Jayanti
        LocalDate.of(2026, 12, 25),  // Christmas Day
        
        // Festival holidays (2026 dates - verify with official NSE notice)
        LocalDate.of(2026, 2, 26),   // Mahashivratri (approximate)
        LocalDate.of(2026, 3, 20),   // Holi (approximate)
        LocalDate.of(2026, 4, 3),    // Good Friday
        LocalDate.of(2026, 4, 14),   // Dr. Ambedkar Jayanti
        LocalDate.of(2026, 5, 1),    // Maharashtra Day
        LocalDate.of(2026, 8, 17),   // Parsi New Year (approximate)
        LocalDate.of(2026, 9, 16),   // Ganesh Chaturthi (approximate)
        LocalDate.of(2026, 10, 21),  // Diwali (Laxmi Pujan - approximate)
        LocalDate.of(2026, 11, 4),   // Gurunanak Jayanti (approximate)
        
        // Additional configurable holidays can be added via properties
        LocalDate.of(2026, 1, 1),    // New Year's Day
        LocalDate.of(2026, 4, 15),   // Ram Navami (approximate)
        LocalDate.of(2026, 6, 26),   // Eid-ul-Fitr (approximate)
        LocalDate.of(2026, 7, 5),    // Rath Yatra (approximate)
        LocalDate.of(2026, 9, 24),   // Dussehra (approximate)
        LocalDate.of(2026, 11, 9),   // Diwali Balipratipada (approximate)
        LocalDate.of(2026, 12, 31)   // New Year's Eve (optional)
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
        
        return timeValue >= 915 && timeValue <= 1530;
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
