package com.riskpilot.service;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Set;

@Service
public class MarketSessionService {
    // Replace hardcoded list with an API call in a @PostConstruct method
    public boolean isMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime time = now.toLocalTime();
        boolean isWeekend = now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY;
        
        return !isWeekend && !time.isBefore(LocalTime.of(9, 15)) && time.isBefore(LocalTime.of(15, 30));
    }
}
