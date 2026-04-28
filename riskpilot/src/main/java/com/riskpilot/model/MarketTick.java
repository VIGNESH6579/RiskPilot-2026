package com.riskpilot.model;

import java.time.Duration;
import java.time.LocalDateTime;

public record MarketTick(
    String symbol,
    double price,
    LocalDateTime exchangeTimestamp,
    LocalDateTime receivedAt,
    long sourceAgeMs,
    MarketDataTransport transport,
    long sequenceId,
    long rawExchangeTime
) {
    public MarketTick {
        if (receivedAt == null) {
            receivedAt = LocalDateTime.now();
        }
    }

    public static MarketTick of(
        String symbol,
        double price,
        LocalDateTime exchangeTimestamp,
        LocalDateTime receivedAt,
        MarketDataTransport transport,
        long sequenceId
    ) {
        return of(symbol, price, exchangeTimestamp, receivedAt, transport, sequenceId, 0L);
    }

    public static MarketTick of(
        String symbol,
        double price,
        LocalDateTime exchangeTimestamp,
        LocalDateTime receivedAt,
        MarketDataTransport transport,
        long sequenceId,
        long rawExchangeTime
    ) {
        LocalDateTime effectiveReceivedAt = receivedAt == null ? LocalDateTime.now() : receivedAt;
        long ageMs = exchangeTimestamp == null
            ? Long.MAX_VALUE
            : Math.max(0L, Duration.between(exchangeTimestamp, effectiveReceivedAt).toMillis());
        return new MarketTick(symbol, price, exchangeTimestamp, effectiveReceivedAt, ageMs, transport, sequenceId, rawExchangeTime);
    }
}
