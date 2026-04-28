package com.riskpilot.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
public class MarketSessionService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    public boolean isMarketOpen() {
        return isMarketOpen(nowIst());
    }

    public boolean isMarketOpen(LocalDateTime timestamp) {
        LocalTime localTime = timestamp.toLocalTime();
        return !localTime.isBefore(MARKET_OPEN) && !localTime.isAfter(MARKET_CLOSE);
    }

    public LocalDateTime nowIst() {
        return ZonedDateTime.now(IST).toLocalDateTime();
    }
}
