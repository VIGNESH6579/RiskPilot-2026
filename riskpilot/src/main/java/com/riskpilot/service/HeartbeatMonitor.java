package com.riskpilot.service;

import com.riskpilot.model.TradingSessionSnapshot;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class HeartbeatMonitor {

    private final SessionStateManager stateManager;
    private final CandleAggregator candleAggregator;
    
    private LocalDateTime lastTickReceivedTime = LocalDateTime.now();

    public HeartbeatMonitor(SessionStateManager stateManager, CandleAggregator candleAggregator) {
        this.stateManager = stateManager;
        this.candleAggregator = candleAggregator;
    }

    public synchronized void registerTick() {
        lastTickReceivedTime = LocalDateTime.now();
    }

    public synchronized boolean isHealthy() {
        long secondsSinceLastTick = java.time.Duration.between(lastTickReceivedTime, LocalDateTime.now()).getSeconds();
        return secondsSinceLastTick < 45;
    }

    public synchronized String getLastHeartbeatTime() {
        return lastTickReceivedTime.toString();
    }

    @Scheduled(fixedDelay = 2000)
    public void monitorHealth() {
        LocalDateTime now = LocalDateTime.now();
        long secondsSinceLastTick = java.time.Duration.between(lastTickReceivedTime, now).getSeconds();

        if (secondsSinceLastTick >= 15 && secondsSinceLastTick < 45) {
            candleAggregator.markUnstable();
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
        } else if (secondsSinceLastTick >= 45) {
            candleAggregator.markUnstable();
            TradingSessionSnapshot state = stateManager.getSnapshot();
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
