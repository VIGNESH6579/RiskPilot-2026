package com.riskpilot.service;

import com.riskpilot.model.MarketDataTransport;
import com.riskpilot.model.MarketTick;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
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
        snapshotRef.set(new MarketDataSnapshot(
            true,
            current.subscribed(),
            current.feedBlocked(),
            current.blockReason(),
            current.halted(),
            current.consecutiveRejectedTicks(),
            transport,
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId()
        ));
    }

    public synchronized void markSubscribed(MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        snapshotRef.set(new MarketDataSnapshot(
            true,
            true,
            current.feedBlocked(),
            current.blockReason(),
            current.halted(),
            current.consecutiveRejectedTicks(),
            transport,
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId()
        ));
    }

    public synchronized void recordAcceptedTick(MarketTick tick) {
        snapshotRef.set(new MarketDataSnapshot(
            true,
            true,
            false,
            null,
            false,
            0,
            tick.transport(),
            tick,
            tick.receivedAt(),
            tick.sequenceId()
        ));
    }

    public synchronized int markRejectedTick(String reason, MarketDataTransport transport) {
        MarketDataSnapshot current = snapshotRef.get();
        int rejected = current.consecutiveRejectedTicks() + 1;
        snapshotRef.set(new MarketDataSnapshot(
            current.connected(),
            current.subscribed(),
            true,
            reason,
            current.halted(),
            rejected,
            transport != null ? transport : current.transport(),
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId()
        ));
        return rejected;
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
            transport != null ? transport : current.transport(),
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId()
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
            transport != null ? transport : current.transport(),
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId()
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
            transport != null ? transport : current.transport(),
            current.lastTick(),
            current.lastAcceptedAt(),
            current.lastSequenceId()
        ));
    }

    public Optional<MarketTick> lastAcceptedTick() {
        return Optional.ofNullable(snapshotRef.get().lastTick());
    }

    public long silenceMs(LocalDateTime now) {
        MarketDataSnapshot current = snapshotRef.get();
        if (current.lastAcceptedAt() == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Duration.between(current.lastAcceptedAt(), now).toMillis());
    }

    public record MarketDataSnapshot(
        boolean connected,
        boolean subscribed,
        boolean feedBlocked,
        String blockReason,
        boolean halted,
        int consecutiveRejectedTicks,
        MarketDataTransport transport,
        MarketTick lastTick,
        LocalDateTime lastAcceptedAt,
        long lastSequenceId
    ) {
        public static MarketDataSnapshot initial() {
            return new MarketDataSnapshot(
                false,
                false,
                true,
                "LIVE_FEED_NOT_STARTED",
                false,
                0,
                null,
                null,
                null,
                -1L
            );
        }
    }
}
