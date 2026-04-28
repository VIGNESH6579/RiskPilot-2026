package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.MarketTick;
import com.riskpilot.model.TradingSessionSnapshot;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class HeartbeatMonitor {
    private static final long STARTUP_GRACE_PERIOD_MS = 10_000L;

    private final SessionStateManager stateManager;
    private final CandleAggregator candleAggregator;
    private final MarketDataStateService marketDataStateService;
    private final RiskPilotProperties properties;
    private final Instant startupTime;

    private Instant lastFreshTickReceivedTime;

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
        this.startupTime = Instant.now();
        this.lastFreshTickReceivedTime = this.startupTime;
    }

    public synchronized void registerFreshTick(MarketTick tick) {
        lastFreshTickReceivedTime = tick.receivedAt();
    }

    public synchronized boolean isHealthy() {
        long uptimeMs = java.time.Duration.between(startupTime, Instant.now()).toMillis();
        if (uptimeMs < STARTUP_GRACE_PERIOD_MS) {
            return true;
        }
        if (lastFreshTickReceivedTime == null) {
            return false;
        }
        long silenceMs = java.time.Duration.between(lastFreshTickReceivedTime, Instant.now()).toMillis();
        return silenceMs < properties.getInfra().getHeartbeat().getMaxSilenceMs();
    }

    public synchronized String getLastHeartbeatTime() {
        return lastFreshTickReceivedTime == null ? null : lastFreshTickReceivedTime.toString();
    }

    public synchronized void reset() {
        lastFreshTickReceivedTime = Instant.now();
    }

    @Scheduled(fixedDelay = 2000)
    public void monitorHealth() {
        Instant now = Instant.now();
        long uptimeMs = java.time.Duration.between(startupTime, now).toMillis();
        if (uptimeMs < STARTUP_GRACE_PERIOD_MS) {
            stateManager.update(current -> new TradingSessionSnapshot(
                current.sessionActive(),
                current.regime(),
                current.volatilityQualified(),
                current.timePhase(),
                current.tradesTaken(),
                current.tradeActive(),
                current.feedStable(),
                true,
                current.orHigh(),
                current.orLow(),
                current.cumulativeDailyLossR(),
                current.activeTradeReference(),
                current.lastRejectReason()
            ));
            return;
        }
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
