package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AngelOneMarketDataService angelOneMarketDataService;
    private final CentralizedMarketDataService centralizedMarketDataService;
    private final RealTimeTickAggregator realTimeTickAggregator;
    
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
        MarketSessionService marketSessionService,
        AngelOneMarketDataService angelOneMarketDataService,
        CentralizedMarketDataService centralizedMarketDataService,
        RealTimeTickAggregator realTimeTickAggregator
    ) {
        this.candleAggregator = candleAggregator;
        this.heartbeatMonitor = heartbeatMonitor;
        this.optionChainService = optionChainService;
        this.shadowExecutionEngine = shadowExecutionEngine;
        this.marketSessionService = marketSessionService;
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.centralizedMarketDataService = centralizedMarketDataService;
        this.realTimeTickAggregator = realTimeTickAggregator;
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

        // Poll once per 1 second for better real-time data resolution.
        pollerFuture = poller.scheduleAtFixedRate(this::pollSpotAsTick, 0, 1, TimeUnit.SECONDS);
        log.info("AngelTickStreamClient started with deduplication guard");
    }

    @Autowired private MarketDataStateService marketDataStateService;

    private void pollSpotAsTick() {
        try {
            LocalDateTime receivedAt = marketSessionService.nowIst().toLocalDateTime();
            if (!marketSessionService.isMarketOpen()) {
                return;
            }

            // Automatic Socket Recycling: if no ticks for > 45s during market hours
            // Relaxed from 15s to 45s to reduce recycling frequency
            long age = marketDataStateService.getLastTickAgeMs();
            if (age > 45000) {
                log.warn("Feed stale ({}ms) - triggering automatic socket recycling", age);
                resubscribe();
                return;
            }

            // Use centralized market data instead of hitting API directly
            double spot = centralizedMarketDataService.getNiftyLtp();

            // Only process if centralized data is fresh
            if (!centralizedMarketDataService.isDataFresh()) {
                log.warn("Centralized market data is stale, skipping tick processing.");
                candleAggregator.markUnstable();
                return;
            }
            
            if (spot <= 0.0) {
                // Last resort fallback: hit Angel One LTP directly if centralized data is empty
                java.util.Optional<Double> directLtp = angelOneMarketDataService.getNiftyLtp();
                if (directLtp.isPresent() && directLtp.get() > 0.0) {
                    spot = directLtp.get();
                } else {
                    candleAggregator.markUnstable();
                    return;
                }
            }

            // CRITICAL: Update MarketDataStateService so TradingSafetyManager sees fresh data
            marketDataStateService.updateNiftyFromWebSocket(spot, 0, 0);

            lastSpotValue.set((long) (spot * 100));
            heartbeatMonitor.registerTick();

            int candleMinute = (receivedAt.getMinute() / 5) * 5;
            LocalDateTime currentSlot = receivedAt.withMinute(candleMinute).withSecond(0).withNano(0);

            long seq = sequenceCounter.incrementAndGet();
            candleAggregator.processTick(receivedAt, spot, 1L, seq, receivedAt);

            // Also feed the real-time aggregator so CandleEntity history is built
            realTimeTickAggregator.processAngelTick("NIFTY", spot, 1L);

            shadowExecutionEngine.evaluateTick(spot);
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
        log.info("Resubscription requested, resetting deduplication state");
        subscriptionActive.set(false);
        lastSpotValue.set(0);
        if (pollerFuture != null && !pollerFuture.isCancelled()) {
            pollerFuture.cancel(true);
            try {
                poller.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        init();
    }

    public boolean isStreamActive() {
        return subscriptionActive.get() && pollerFuture != null && !pollerFuture.isCancelled();
    }
}
