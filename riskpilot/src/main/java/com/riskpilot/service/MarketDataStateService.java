package com.riskpilot.service;

import com.riskpilot.model.MarketDataTransport;
import com.riskpilot.model.MarketTick;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MarketDataStateService {

    private final AtomicReference<MarketDataSnapshot> snapshotRef =
        new AtomicReference<>(MarketDataSnapshot.initial());

    public MarketDataSnapshot snapshot() {
        return snapshotRef.get();
    }

    public synchronized void markConnected(MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        snapshotRef.set(current.withConnection(true, current.subscribed(), transport));
    }

    public synchronized void markSubscribed(MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        snapshotRef.set(current.withConnection(true, true, transport));
    }

    public synchronized void markReady(MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        snapshotRef.set(current.withReady(true, transport));
    }

    public synchronized void recordAcceptedTick(MarketTick tick) {
        MarketDataSnapshot current = snapshotRef.get();
        snapshotRef.set(new MarketDataSnapshot(
            true,
            true,
            false,
            null,
            false,
            0,
            current.parseFailureCount(),
            true,
            tick.transport(),
            tick,
            tick.receivedAt(),
            tick.sequenceId(),
            current.lastAcceptedAt() == null
                ? 0L
                : Math.max(0L, Duration.between(current.lastAcceptedAt(), tick.receivedAt()).toMillis())
        ));
    }

    public synchronized int markRejectedTick(String reason, MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        int rejected = shouldCountRejectedTick(reason)
            ? current.consecutiveRejectedTicks() + 1
            : current.consecutiveRejectedTicks();
        snapshotRef.set(new MarketDataSnapshot(
            current.connected(),
            current.subscribed(),
            true,
            reason,
            current.halted(),
            rejected,
            current.parseFailureCount(),
            current.ready(),
            transport != null ? transport : current.transport(),
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId(),
            current.lastInterArrivalMs()
        ));
        return rejected;
    }

    public synchronized void recordParseFailure(String reason, MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        snapshotRef.set(new MarketDataSnapshot(
            current.connected(),
            current.subscribed(),
            true,
            reason,
            current.halted(),
            current.consecutiveRejectedTicks(),
            current.parseFailureCount() + 1,
            current.ready(),
            transport != null ? transport : current.transport(),
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId(),
            current.lastInterArrivalMs()
        ));
    }

    public synchronized void markFeedFailure(String reason, MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        snapshotRef.set(new MarketDataSnapshot(
            current.connected(),
            current.subscribed(),
            true,
            reason,
            current.halted(),
            current.consecutiveRejectedTicks(),
            current.parseFailureCount(),
            current.ready(),
            transport != null ? transport : current.transport(),
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId(),
            current.lastInterArrivalMs()
        ));
    }

    public synchronized void markDisconnected(String reason, MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        snapshotRef.set(new MarketDataSnapshot(
            false,
            false,
            true,
            reason,
            current.halted(),
            current.consecutiveRejectedTicks(),
            current.parseFailureCount(),
            false,
            transport != null ? transport : current.transport(),
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId(),
            current.lastInterArrivalMs()
        ));
    }

    public synchronized void markHalted(String reason, MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        snapshotRef.set(new MarketDataSnapshot(
            false,
            current.subscribed(),
            true,
            reason,
            true,
            current.consecutiveRejectedTicks(),
            current.parseFailureCount(),
            false,
            transport != null ? transport : current.transport(),
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId(),
            current.lastInterArrivalMs()
        ));
    }

    public Optional<MarketTick> lastAcceptedTick() {
        return Optional.ofNullable(snapshotRef.get().lastTick());
    }

    public long silenceMs(Instant now) {
        MarketDataSnapshot current = snapshotRef.get();
        if (current.lastAcceptedAt() == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Duration.between(current.lastAcceptedAt(), now).toMillis());
    }

    public long lastTickAgeMs(Instant now) {
        MarketDataSnapshot current = snapshotRef.get();
        if (current.lastTick() == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Duration.between(current.lastTick().receivedAt(), now).toMillis());
    }

    public long lastInterArrivalMs() {
        return snapshotRef.get().lastInterArrivalMs();
    }

    public String resolvePriceSource(boolean marketOpen, long maxSilenceMs) {
        MarketDataSnapshot current = snapshotRef.get();
        if (current.lastTick() == null) {
            return "STALE";
        }
        if (!marketOpen) {
            return "MARKET_CLOSED";
        }
        long ageMs = lastTickAgeMs(Instant.now());
        if (current.feedBlocked() || current.halted() || ageMs > maxSilenceMs) {
            return "STALE";
        }
        return "LIVE";
    }

    private boolean shouldCountRejectedTick(String reason) {
        return reason == null || !"MARKET_CLOSED_TICK".equals(reason);
    }

    public record MarketDataSnapshot(
        boolean connected,
        boolean subscribed,
        boolean feedBlocked,
        String blockReason,
        boolean halted,
        int consecutiveRejectedTicks,
        int parseFailureCount,
        boolean ready,
        MarketDataTransport transport,
        MarketTick lastTick,
        Instant lastAcceptedAt,
        long lastSequenceId,
        long lastInterArrivalMs
    ) {
        public static MarketDataSnapshot initial() {
            return new MarketDataSnapshot(
                false,
                false,
                true,
                "LIVE_FEED_NOT_STARTED",
                false,
                0,
                0,
                false,
                null,
                null,
                null,
                -1L,
                0L
            );
        }

        private MarketDataSnapshot withConnection(boolean connected, boolean subscribed, MarketDataTransport transport) {
            return new MarketDataSnapshot(
                connected,
                subscribed,
                feedBlocked,
                blockReason,
                halted,
                consecutiveRejectedTicks,
                parseFailureCount,
                ready,
                transport != null ? transport : this.transport,
                lastTick,
                lastAcceptedAt,
                lastSequenceId,
                lastInterArrivalMs
            );
        }

        private MarketDataSnapshot withReady(boolean ready, MarketDataTransport transport) {
            return new MarketDataSnapshot(
                connected,
                subscribed,
                feedBlocked,
                blockReason,
                halted,
                consecutiveRejectedTicks,
                parseFailureCount,
                ready,
                transport != null ? transport : this.transport,
                lastTick,
                lastAcceptedAt,
                lastSequenceId,
                lastInterArrivalMs
            );
        }
    }
}
