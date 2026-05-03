package com.riskpilot.service;

import com.riskpilot.model.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CandleAggregator {

    private final List<Candle> historicalBuffer = new ArrayList<>();
    private Candle currentBuildingCandle = null;
    private LocalDateTime currentCandleStart = null;  // BUG-003: Using LocalDateTime (date+time)
    private LocalDateTime lastTickTime = LocalDateTime.now();
    
    // BUG-004: feedUnstable with proper OR logic and recovery timer
    private volatile boolean feedUnstable = false;
    private LocalDateTime stableSinceTime = null;
    private static final long FEED_INSTABILITY_GAP_MS = 1500;
    private static final long FEED_STABLE_RECOVERY_MS = 5000;
    
    // BUG-002: Sequence tracking for out-of-order tick detection
    private long lastAcceptedSequenceId = -1;
    private long outOfOrderTickCount = 0;
    
    // BUG-045: After-hours buffer with overflow tracking
    private final List<MarketTick> afterHoursBuffer = new ArrayList<>();
    private long afterHoursDropCount = 0;
    private static final int AFTER_HOURS_BUFFER_CAP = 250;

    /**
     * MarketTick record for capturing tick metadata.
     */
    public record MarketTick(
        LocalDateTime timestamp,
        double price,
        long volume,
        long sequenceId,
        LocalDateTime receivedAt
    ) {}

    /**
     * Process incoming tick with proper validation and sequencing.
     * BUG-001: Preserves original receivedAt time.
     * BUG-002: Drops out-of-order ticks (doesn't apply to wrong candle).
     * BUG-003: Uses LocalDateTime for date-aware comparison.
     * BUG-004: Uses OR logic for feedUnstable flag (never plain assignment).
     */
    public synchronized void processTick(LocalDateTime tickTime, double price, long volume, long sequenceId, LocalDateTime receivedAt) {
        LocalDateTime now = LocalDateTime.now();
        
        // BUG-002: Check for duplicate/out-of-order sequence IDs
        if (sequenceId > 0 && sequenceId <= lastAcceptedSequenceId) {
            log.debug("DUPLICATE_TICK_DROPPED seq={}", sequenceId);
            return;
        }
        lastAcceptedSequenceId = sequenceId;
        
        // BUG-001: Use provided receivedAt instead of calculating now
        long tickDelayMs = java.time.Duration.between(receivedAt, now).toMillis();
        
        // BUG-004: OR logic for feedUnstable - only SET true, never clear inline
        if (tickDelayMs > FEED_INSTABILITY_GAP_MS) {
            if (!feedUnstable) {
                log.warn("FEED_UNSTABLE tickDelayMs={}", tickDelayMs);
            }
            feedUnstable = true;  // SET true - OR-like behavior
            stableSinceTime = null;
        } else {
            // BUG-004: Only clear via recovery timer in else branch
            if (stableSinceTime == null) {
                stableSinceTime = now;
            } else if (feedUnstable) {
                long stableMs = java.time.Duration.between(stableSinceTime, now).toMillis();
                if (stableMs >= FEED_STABLE_RECOVERY_MS) {
                    feedUnstable = false;
                    log.info("FEED_STABLE_RECOVERED after {}ms", stableMs);
                }
            }
        }
        
        lastTickTime = tickTime;

        // BUG-003: 5-minute alignment using LocalDateTime (date+time safe)
        int minute = tickTime.getMinute();
        int candleStartMinute = (minute / 5) * 5;
        LocalDateTime candleStart = LocalDateTime.of(
            tickTime.toLocalDate(),
            LocalTime.of(tickTime.getHour(), candleStartMinute, 0)
        );

        if (currentBuildingCandle == null || currentCandleStart == null) {
            currentCandleStart = candleStart;
            currentBuildingCandle = new Candle(
                tickTime.toLocalDate().toString(),
                candleStart.toLocalTime().toString(),
                price, price, price, price
            );
        } else {
            // BUG-003: Date-safe comparison using LocalDateTime
            if (candleStart.isAfter(currentCandleStart)) {
                // Candle has cleanly closed via time rollover
                finalizeCandle(currentBuildingCandle);
                
                currentCandleStart = candleStart;
                currentBuildingCandle = new Candle(
                    tickTime.toLocalDate().toString(),
                    candleStart.toLocalTime().toString(),
                    price, price, price, price
                );
            } else if (candleStart.isEqual(currentCandleStart)) {
                // Same candle - apply tick
                currentBuildingCandle.applyTick(price);
            } else {
                // BUG-002: Out-of-order tick (candleStart < currentCandleStart)
                outOfOrderTickCount++;
                log.warn("DROPPED_LATE_TICK seq={} candleStart={} currentCandleStart={} price={}",
                    sequenceId, candleStart, currentCandleStart, price);
                // Do NOT apply to current candle - discard
            }
        }
    }

    /**
     * Legacy method for backward compatibility (without sequence tracking).
     * @deprecated Use processTick with sequenceId and receivedAt for proper validation.
     */
    @Deprecated
    public synchronized void processTick(LocalDateTime tickTime, double price, long volume) {
        processTick(tickTime, price, volume, -1, LocalDateTime.now());
    }

    private void finalizeCandle(Candle completedCandle) {
        historicalBuffer.add(completedCandle.copy());
        // Truncate buffer to maintain memory efficiency (keep last 50 candles).
        if (historicalBuffer.size() > 50) {
            historicalBuffer.remove(0);
        }
    }
    
    public synchronized void markUnstable() {
        this.feedUnstable = true;
    }
    
    public synchronized boolean isFeedUnstable() {
        return this.feedUnstable;
    }

    /**
     * Add a candle directly (for restoring from persistence).
     */
    public synchronized void addCandle(Candle candle) {
        historicalBuffer.add(candle.copy());
        if (historicalBuffer.size() > 50) {
            historicalBuffer.remove(0);
        }
    }

    public synchronized List<Candle> getValidHistory() {
        List<Candle> snapshot = new ArrayList<>(historicalBuffer.size());
        for (Candle c : historicalBuffer) {
            snapshot.add(c.copy());
        }
        return snapshot;
    }
    
    /**
     * BUG-045: Track after-hours tick with overflow logging.
     */
    public synchronized void trackAfterHoursTick(MarketTick tick) {
        afterHoursBuffer.add(tick);
        if (afterHoursBuffer.size() > AFTER_HOURS_BUFFER_CAP) {
            afterHoursBuffer.remove(0);
            afterHoursDropCount++;
            if (afterHoursDropCount % 100 == 0) {
                log.info("AFTER_HOURS_BUFFER_OVERFLOW totalDropped={}", afterHoursDropCount);
            }
        }
    }
    
    /**
     * Clear all history and reset state.
     */
    public synchronized void clearHistory() {
        historicalBuffer.clear();
        currentBuildingCandle = null;
        currentCandleStart = null;
        feedUnstable = false;
        stableSinceTime = null;
        lastAcceptedSequenceId = -1;
        outOfOrderTickCount = 0;
        afterHoursBuffer.clear();
    }
    
    /**
     * Get out-of-order tick statistics (for monitoring).
     */
    public synchronized long getOutOfOrderTickCount() {
        return outOfOrderTickCount;
    }
}
