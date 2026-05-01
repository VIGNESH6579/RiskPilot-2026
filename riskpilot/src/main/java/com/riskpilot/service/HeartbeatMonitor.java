package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.MarketTick;
import com.riskpilot.model.TradingSessionSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
public class HeartbeatMonitor {
    private static final long STARTUP_GRACE_PERIOD_MS = 10_000L;

    private final SessionStateManager stateManager;
    private final CandleAggregator candleAggregator;
    private final MarketDataStateService marketDataStateService;
    private final MarketSessionService marketSessionService;
    private final MarketDataReconnectScheduler reconnectScheduler;
    private final RiskPilotProperties properties;
    private final Instant startupTime;

    private Instant lastFreshTickReceivedTime;

    public HeartbeatMonitor(
        SessionStateManager stateManager,
        CandleAggregator candleAggregator,
        MarketDataStateService marketDataStateService,
        MarketSessionService marketSessionService,
        MarketDataReconnectScheduler reconnectScheduler,
        RiskPilotProperties properties
    ) {
        this.stateManager = stateManager;
        this.candleAggregator = candleAggregator;
        this.marketDataStateService = marketDataStateService;
        this.marketSessionService = marketSessionService;
        this.reconnectScheduler = reconnectScheduler;
        this.properties = properties;
        this.startupTime = Instant.now();
        this.lastFreshTickReceivedTime = this.startupTime;
    }

    public synchronized void registerFreshTick(MarketTick tick) {
        lastFreshTickReceivedTime = tick.receivedAt();
    }

    public synchronized boolean isHealthy() {
        Instant now = Instant.now();
        long uptimeMs = Duration.between(startupTime, now).toMillis();
        if (uptimeMs < STARTUP_GRACE_PERIOD_MS) {
            return true;
        }
        if (!marketSessionService.isMarketOpen(now)) {
            return true;
        }
        if (lastFreshTickReceivedTime == null) {
            return false;
        }
        long silenceMs = Duration.between(lastFreshTickReceivedTime, now).toMillis();
        return silenceMs < properties.getInfra().getHeartbeat().getMaxSilenceMs();
    }

    public synchronized String getLastHeartbeatTime() {
        return lastFreshTickReceivedTime == null ? null : lastFreshTickReceivedTime.toString();
    }

    public synchronized void reset() {
        lastFreshTickReceivedTime = Instant.now();
    }

    @Scheduled(fixedRate = 5000)
    public void monitorHealth() {
        Instant now = Instant.now();
        long uptimeMs = Duration.between(startupTime, now).toMillis();
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

        if (!marketSessionService.isMarketOpen(now)) {
            stateManager.update(current -> new TradingSessionSnapshot(
                false,
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
                "AWAITING_MARKET_OPEN"
            ));
            return;
        }

        long silenceMs = lastFreshTickReceivedTime == null
            ? Long.MAX_VALUE
            : Duration.between(lastFreshTickReceivedTime, now).toMillis();
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
            TradingSessionSnapshot current = stateManager.getSnapshot();
            candleAggregator.markUnstable();
            marketDataStateService.markFeedFailure("HEARTBEAT_TIMEOUT", marketDataStateService.snapshot().transport());
            if (!"HEARTBEAT_PANIC".equals(current.lastRejectReason())) {
                log.warn("HEARTBEAT_PANIC - no tick for {}ms", silenceMs);
                reconnectScheduler.scheduleReconnect(Duration.ofSeconds(5));
            }
            stateManager.update(snapshot -> new TradingSessionSnapshot(
                snapshot.sessionActive(),
                snapshot.regime(),
                snapshot.volatilityQualified(),
                snapshot.timePhase(),
                snapshot.tradesTaken(),
                snapshot.tradeActive(),
                false,
                false,
                snapshot.orHigh(),
                snapshot.orLow(),
                snapshot.cumulativeDailyLossR(),
                snapshot.activeTradeReference(),
                "HEARTBEAT_PANIC"
            ));
        } else {
            TradingSessionSnapshot snapshotBeforeRecovery = stateManager.getSnapshot();
            if ("HEARTBEAT_PANIC".equals(snapshotBeforeRecovery.lastRejectReason()) || !snapshotBeforeRecovery.heartbeatAlive()) {
                log.info("Heartbeat recovered - resuming trading");
                stateManager.update(snapshot -> new TradingSessionSnapshot(
                    snapshot.sessionActive(),
                    snapshot.regime(),
                    snapshot.volatilityQualified(),
                    snapshot.timePhase(),
                    snapshot.tradesTaken(),
                    snapshot.tradeActive(),
                    true,
                    true,
                    snapshot.orHigh(),
                    snapshot.orLow(),
                    snapshot.cumulativeDailyLossR(),
                    snapshot.activeTradeReference(),
                    "HEARTBEAT_RECOVERED"
                ));
                return;
            }
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
