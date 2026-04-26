package com.riskpilot.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final ShadowExecutionEngine shadowExecutionEngine;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    public AngelTickStreamClient(
        CandleAggregator candleAggregator,
        HeartbeatMonitor heartbeatMonitor,
        OptionChainService optionChainService,
        ShadowExecutionEngine shadowExecutionEngine
    ) {
        this.candleAggregator = candleAggregator;
        this.heartbeatMonitor = heartbeatMonitor;
        this.optionChainService = optionChainService;
        this.shadowExecutionEngine = shadowExecutionEngine;
    }

    @PostConstruct
    public void init() {
        poller.scheduleAtFixedRate(this::pollSpotAsTick, 0, 2, TimeUnit.SECONDS);
    }

    private void pollSpotAsTick() {
        try {
            OptionChainService.OptionChainSnapshot snapshot = optionChainService.fetchNiftyChain();
            if (snapshot == null || snapshot.spot() <= 0.0) {
                candleAggregator.markUnstable();
                return;
            }

            heartbeatMonitor.registerTick();
            candleAggregator.processTick(LocalDateTime.now(), snapshot.spot(), 1L);
            shadowExecutionEngine.evaluateTick(snapshot.spot());
        } catch (Exception e) {
            candleAggregator.markUnstable();
            log.debug("Tick polling failed: {}", e.getMessage());
        }
    }
}
