package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class AngelTickStreamClient {
    private static final Logger log = LoggerFactory.getLogger(AngelTickStreamClient.class);

    private final CandleAggregator candleAggregator;
    private final HeartbeatMonitor heartbeatMonitor;
    private final OptionChainService optionChainService;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

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
        // Production-safe fallback feed:
        // until SmartAPI WS integration is completed, keep engine alive with polled live spot.
        poller.scheduleAtFixedRate(this::pollSpotAsTick, 0, 2, TimeUnit.SECONDS);
    }

    private void pollSpotAsTick() {
        try {
            OptionChainService.OptionChainSnapshot snap = optionChainService.fetchNiftyChain();
            if (snap == null || snap.spot() <= 0.0) {
                candleAggregator.markUnstable();
                return;
            }
            heartbeatMonitor.registerTick();
            candleAggregator.processTick(LocalDateTime.now(), snap.spot(), 1L);
        } catch (Exception e) {
            candleAggregator.markUnstable();
            log.debug("Tick polling failed: {}", e.getMessage());
        }
    }
}
