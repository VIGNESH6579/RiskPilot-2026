package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
@RequiredArgsConstructor
public class MarketSessionService {

    private final RiskPilotProperties properties;

    public boolean isMarketOpen() {
        return isMarketOpen(now());
    }

    public boolean isMarketOpen(LocalDateTime timestamp) {
        LocalTime localTime = timestamp.toLocalTime();
        return !localTime.isBefore(marketOpen()) && !localTime.isAfter(marketClose());
    }

    public LocalDateTime nowIst() {
        return now();
    }

    public ZoneId zoneId() {
        return ZoneId.of(properties.getMarket().getZone());
    }

    private LocalDateTime now() {
        return ZonedDateTime.now(zoneId()).toLocalDateTime();
    }

    private LocalTime marketOpen() {
        return LocalTime.parse(properties.getMarket().getOpen());
    }

    private LocalTime marketClose() {
        return LocalTime.parse(properties.getMarket().getClose());
    }
}
