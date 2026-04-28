package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    public boolean isMarketOpen(Instant timestamp) {
        LocalTime localTime = toMarketTime(timestamp).toLocalTime();
        return !localTime.isBefore(marketOpen()) && !localTime.isAfter(marketClose());
    }

    public boolean isMarketOpen(LocalDateTime timestamp) {
        return isMarketOpen(timestamp.atZone(zoneId()).toInstant());
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

    private LocalTime marketOpen() {
        return LocalTime.parse(properties.getMarket().getOpen());
    }

    private LocalTime marketClose() {
        return LocalTime.parse(properties.getMarket().getClose());
    }
}
