package com.riskpilot.model;

import java.time.Instant;

public record MarketTick(
    String symbol,
    double price,
    Instant exchangeTimestamp,
    Instant receivedAt,
    long sourceAgeMs,
    MarketDataTransport transport,
    long sequenceId,
    long rawExchangeTime
) {
    public MarketTick {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }

    public static MarketTick of(
        String symbol,
        double price,
        Instant exchangeTimestamp,
        Instant receivedAt,
        MarketDataTransport transport,
        long sequenceId
    ) {
        return of(symbol, price, exchangeTimestamp, receivedAt, transport, sequenceId, 0L);
    }

    public static MarketTick of(
        String symbol,
        double price,
        Instant exchangeTimestamp,
        Instant receivedAt,
        MarketDataTransport transport,
        long sequenceId,
        long rawExchangeTime
    ) {
        Instant effectiveReceivedAt = receivedAt == null ? Instant.now() : receivedAt;
        long ageMs = exchangeTimestamp == null
            ? Long.MAX_VALUE
            : Math.max(0L, effectiveReceivedAt.toEpochMilli() - exchangeTimestamp.toEpochMilli());
        return new MarketTick(symbol, price, exchangeTimestamp, effectiveReceivedAt, ageMs, transport, sequenceId, rawExchangeTime);
    }
}
