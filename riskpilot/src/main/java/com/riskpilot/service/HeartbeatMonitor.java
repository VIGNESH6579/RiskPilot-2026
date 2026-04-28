package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.MarketTick;
import com.riskpilot.model.TradingSessionSnapshot;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class HeartbeatMonitor {

    private final SessionStateManager stateManager;
    private final CandleAggregator candleAggregator;
    private final MarketDataStateService marketDataStateService;
    private final RiskPilotProperties properties;
    
    private LocalDateTime lastFreshTickReceivedTime;

    public HeartbeatMonitor(
        SessionStateManager stateManager,
        CandleAggregator candleAggregator,
        MarketDataStateService marketDataStateService,
        RiskPilotProperties properties
    ) {
        this.stateManager = stateManager;
        this.candleAggregator = candleAggregator;
        this.marketDataStateService = marketDataStateService;
        this.properties = properties;
    }

    public synchronized void registerFreshTick(MarketTick tick) {
        lastFreshTickReceivedTime = tick.receivedAt();
    }

    public synchronized boolean isHealthy() {
        if (lastFreshTickReceivedTime == null) {
            return false;
        }
        long silenceMs = java.time.Duration.between(lastFreshTickReceivedTime, LocalDateTime.now()).toMillis();
        return silenceMs < properties.getInfra().getHeartbeat().getMaxSilenceMs();
    }

    public synchronized String getLastHeartbeatTime() {
        return lastFreshTickReceivedTime == null ? null : lastFreshTickReceivedTime.toString();
    }

    public synchronized void reset() {
        lastFreshTickReceivedTime = null;
    }

    @Scheduled(fixedDelay = 2000)
    public void monitorHealth() {
        LocalDateTime now = LocalDateTime.now();
        long silenceMs = lastFreshTickReceivedTime == null
            ? Long.MAX_VALUE
            : java.time.Duration.between(lastFreshTickReceivedTime, now).toMillis();
        long unstableMs = properties.getInfra().getFeed().getInstabilityTimeoutSec() * 1000L;
        long heartbeatMs = properties.getInfra().getHeartbeat().getMaxSilenceMs();

        if (silenceMs >= unstableMs && silenceMs < heartbeatMs) {
            candleAggregator.markUnstable();
            marketDataStateService.markFeedFailure("FEED_UNSTABLE", marketDataStateService.snapshot().transport());
            stateManager.update(current -> new TradingSessionSnapshot(
                current.sessionActive(),
                current.regime(),
                current.volatilityQualified(),
                current.timePhase(),
                current.tradesTaken(),
                current.tradeActive(),
                false,
                true,
                current.orHigh(),
                current.orLow(),
                current.cumulativeDailyLossR(),
                current.activeTradeReference(),
                "FEED_UNSTABLE"
            ));
        } else if (silenceMs >= heartbeatMs) {
            candleAggregator.markUnstable();
            marketDataStateService.markHalted("HEARTBEAT_TIMEOUT", marketDataStateService.snapshot().transport());
            stateManager.update(current -> new TradingSessionSnapshot(
                current.sessionActive(),
                current.regime(),
                current.volatilityQualified(),
                current.timePhase(),
                current.tradesTaken(),
                false,
                false,
                false,
                current.orHigh(),
                current.orLow(),
                current.cumulativeDailyLossR(),
                null,
                "HEARTBEAT_PANIC"
            ));
        } else {
            stateManager.update(current -> new TradingSessionSnapshot(
                current.sessionActive(),
                current.regime(),
                current.volatilityQualified(),
                current.timePhase(),
                current.tradesTaken(),
                current.tradeActive(),
                true,
                true,
                current.orHigh(),
                current.orLow(),
                current.cumulativeDailyLossR(),
                current.activeTradeReference(),
                current.lastRejectReason()
            ));
        }
    }
}
