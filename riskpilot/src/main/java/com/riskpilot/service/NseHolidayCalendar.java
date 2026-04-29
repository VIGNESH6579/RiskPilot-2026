package com.riskpilot.service;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * NSE trading-holiday calendar.
 *
 * Audit fix: previously {@link MarketSessionService#isMarketOpen()} only
 * checked the time window, so Saturdays, Sundays, and exchange holidays
 * (Republic Day, Diwali, etc.) were treated as regular trading days.
 *
 * The list below covers official NSE trading holidays for 2025 and 2026
 * (sourced from NSE's published trading-holiday circulars). Updates for
 * subsequent years should be added here. Out-of-range dates default to
 * "trading day" so the engine fails open on ordinary weekdays — but every
 * weekend is always blocked regardless of the list.
 */
@Component
public class NseHolidayCalendar {

    private final Set<LocalDate> holidays;
    private final Set<LocalDate> muhuratSessions;

    public NseHolidayCalendar() {
        Set<LocalDate> h = new HashSet<>();

        // NSE Trading Holidays 2025 (per official circular)
        h.add(LocalDate.of(2025, 2, 26));   // Mahashivratri
        h.add(LocalDate.of(2025, 3, 14));   // Holi
        h.add(LocalDate.of(2025, 3, 31));   // Id-Ul-Fitr (Ramzan Id)
        h.add(LocalDate.of(2025, 4, 10));   // Mahavir Jayanti
        h.add(LocalDate.of(2025, 4, 14));   // Dr. Baba Saheb Ambedkar Jayanti
        h.add(LocalDate.of(2025, 4, 18));   // Good Friday
        h.add(LocalDate.of(2025, 5, 1));    // Maharashtra Day
        h.add(LocalDate.of(2025, 8, 15));   // Independence Day
        h.add(LocalDate.of(2025, 8, 27));   // Ganesh Chaturthi
        h.add(LocalDate.of(2025, 10, 2));   // Mahatma Gandhi Jayanti / Dussehra
        h.add(LocalDate.of(2025, 10, 21));  // Diwali Laxmi Pujan (special: muhurat below)
        h.add(LocalDate.of(2025, 10, 22));  // Diwali Balipratipada
        h.add(LocalDate.of(2025, 11, 5));   // Prakash Gurpurb Sri Guru Nanak Dev
        h.add(LocalDate.of(2025, 12, 25));  // Christmas

        // NSE Trading Holidays 2026 (per official circular)
        h.add(LocalDate.of(2026, 1, 26));   // Republic Day
        h.add(LocalDate.of(2026, 2, 17));   // Mahashivratri
        h.add(LocalDate.of(2026, 3, 4));    // Holi
        h.add(LocalDate.of(2026, 3, 21));   // Id-Ul-Fitr (Ramzan Id)
        h.add(LocalDate.of(2026, 4, 3));    // Good Friday
        h.add(LocalDate.of(2026, 4, 14));   // Dr. Baba Saheb Ambedkar Jayanti
        h.add(LocalDate.of(2026, 5, 1));    // Maharashtra Day
        h.add(LocalDate.of(2026, 5, 27));   // Bakri Eid
        h.add(LocalDate.of(2026, 8, 17));   // Independence Day (observed)
        h.add(LocalDate.of(2026, 9, 14));   // Ganesh Chaturthi
        h.add(LocalDate.of(2026, 11, 9));   // Diwali Laxmi Pujan (special: muhurat below)
        h.add(LocalDate.of(2026, 11, 24));  // Prakash Gurpurb Sri Guru Nanak Dev
        h.add(LocalDate.of(2026, 12, 25));  // Christmas

        this.holidays = Collections.unmodifiableSet(h);

        // Muhurat sessions (special evening session on Diwali Laxmi Pujan).
        // These are intentionally NOT counted as normal trading days because
        // the regular hours window (09:15–15:30) is closed; they run for ~1
        // hour in the evening and require a different gate.
        Set<LocalDate> m = new HashSet<>();
        m.add(LocalDate.of(2025, 10, 21));
        m.add(LocalDate.of(2026, 11, 9));
        this.muhuratSessions = Collections.unmodifiableSet(m);
    }

    /** True when {@code date} is a Saturday or Sunday. */
    public boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /** True when {@code date} is on the published NSE holiday list. */
    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }

    /**
     * True when {@code date} is a regular full-day trading session (not a
     * weekend, not a holiday, and not a muhurat-only date).
     */
    public boolean isTradingDay(LocalDate date) {
        return !isWeekend(date) && !isHoliday(date) && !muhuratSessions.contains(date);
    }

    /** True when {@code date} is a Diwali muhurat (special evening) session. */
    public boolean isMuhuratSession(LocalDate date) {
        return muhuratSessions.contains(date);
    }
}
