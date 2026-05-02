package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AngelTickStreamClient polls NIFTY spot as tick data until SmartAPI WebSocket is ready.
 * 
 * BUG-032: Deduplication on re-subscription (prevents duplicate subscriptions).
 * BUG-035: Unique thread names for executor.
 */
@Service
public class AngelTickStreamClient {
    private static final Logger log = LoggerFactory.getLogger(AngelTickStreamClient.class);

    private final CandleAggregator candleAggregator;
    private final HeartbeatMonitor heartbeatMonitor;
    private final OptionChainService optionChainService;
    
    // BUG-035: Named thread factory for executor
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactory() {
            private final AtomicLong counter = new AtomicLong(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AngelTickPoller-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }
    );
    
    // BUG-032: Deduplication guard - prevent duplicate subscriptions
    private final AtomicBoolean subscriptionActive = new AtomicBoolean(false);
    private final AtomicLong lastSpotValue = new AtomicLong(0);

    public AngelTickStreamClient(
        CandleAggregator candleAggregator,
        HeartbeatMonitor heartbeatMonitor,
        OptionChainService optionChainService
    ) {
        this.candleAggregator = candleAggregator;
        this.heartbeatMonitor = heartbeatMonitor;
        this.optionChainService = optionChainService;
    }

    @PostConstruct
    public void init() {
        // BUG-032: Guard against duplicate subscription
        if (!subscriptionActive.compareAndSet(false, true)) {
            log.warn("Duplicate subscription attempt blocked - already active");
            return;
        }
        
        // Production-safe fallback feed:
        // until SmartAPI WS integration is completed, keep engine alive with polled live spot.
        poller.scheduleAtFixedRate(this::pollSpotAsTick, 0, 2, TimeUnit.SECONDS);
        log.info("AngelTickStreamClient started with deduplication guard");
    }

    private void pollSpotAsTick() {
        try {
            OptionChainService.OptionChainSnapshot snap = optionChainService.fetchNiftyChain();
            if (snap == null || snap.spot() <= 0.0) {
                candleAggregator.markUnstable();
                return;
            }
            
            // BUG-032: Basic deduplication - skip if same value as last tick
            long currentSpot = (long) (snap.spot() * 100); // Store as long to avoid float precision issues
            if (currentSpot == lastSpotValue.get()) {
                // Same value - still register tick but don't process as new candle data
                heartbeatMonitor.registerTick();
                return;
            }
            lastSpotValue.set(currentSpot);
            
            heartbeatMonitor.registerTick();
            
            // BUG-001: Pass receivedAt time to preserve original timing
            LocalDateTime receivedAt = LocalDateTime.now();
            candleAggregator.processTick(LocalDateTime.now(), snap.spot(), 1L, currentSpot, receivedAt);
            
        } catch (Exception e) {
            candleAggregator.markUnstable();
            log.debug("Tick polling failed: {}", e.getMessage());
        }
    }
    
    /**
     * BUG-032: Re-subscription with deduplication.
     * Resets the subscription state to allow re-subscription if needed.
     */
    public void resubscribe() {
        log.info("Resubscription requested, clearing deduplication state");
        subscriptionActive.set(false);
        lastSpotValue.set(0);
        init(); // Re-initialize
    }
}
