package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private final ShadowExecutionEngine shadowExecutionEngine;
    private final MarketSessionService marketSessionService;
    
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
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private ScheduledFuture<?> pollerFuture;
    private LocalDateTime lastCandleSlot = null;

    public AngelTickStreamClient(
        CandleAggregator candleAggregator,
        HeartbeatMonitor heartbeatMonitor,
        OptionChainService optionChainService,
        ShadowExecutionEngine shadowExecutionEngine,
        MarketSessionService marketSessionService
    ) {
        this.candleAggregator = candleAggregator;
        this.heartbeatMonitor = heartbeatMonitor;
        this.optionChainService = optionChainService;
        this.shadowExecutionEngine = shadowExecutionEngine;
        this.marketSessionService = marketSessionService;
    }

    @PostConstruct
    public void init() {
        // BUG-032: Guard against duplicate subscription
        if (!subscriptionActive.compareAndSet(false, true)) {
            log.warn("Duplicate subscription attempt blocked - already active");
            return;
        }
        
        if (pollerFuture != null && !pollerFuture.isCancelled()) {
            pollerFuture.cancel(false);
        }

        // Poll once per second so the dashboard and candle feed reflect Angel One LTP freshness.
        pollerFuture = poller.scheduleAtFixedRate(this::pollSpotAsTick, 0, 1, TimeUnit.SECONDS);
        log.info("AngelTickStreamClient started with deduplication guard");
    }

    private void pollSpotAsTick() {
        try {
            LocalDateTime receivedAt = marketSessionService.nowIst().toLocalDateTime();
            if (!marketSessionService.isMarketOpen()) {
                return;
            }

            OptionChainService.OptionChainSnapshot snap = optionChainService.fetchNiftyChain();
            if (snap == null || snap.spot() <= 0.0 || !snap.live()) {
                candleAggregator.markUnstable();
                return;
            }
            
            long currentSpot = (long) (snap.spot() * 100); // Store as long to avoid float precision issues
            lastSpotValue.set(currentSpot);
            
            heartbeatMonitor.registerTick();
            
            int candleMinute = (receivedAt.getMinute() / 5) * 5;
            LocalDateTime currentSlot = receivedAt.withMinute(candleMinute).withSecond(0).withNano(0);

            candleAggregator.processTick(
                receivedAt,
                snap.spot(),
                1L,
                sequenceCounter.incrementAndGet(),
                receivedAt
            );

            shadowExecutionEngine.evaluateTick(snap.spot());
            if (lastCandleSlot != null && currentSlot.isAfter(lastCandleSlot)) {
                shadowExecutionEngine.evaluateCandleClose();
            }
            lastCandleSlot = currentSlot;
            
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

    public boolean isStreamActive() {
        return subscriptionActive.get() && pollerFuture != null && !pollerFuture.isCancelled();
    }
}
