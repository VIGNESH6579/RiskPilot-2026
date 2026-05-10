package com.riskpilot.service;

import com.riskpilot.model.TradingSessionSnapshot;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * HeartbeatMonitor monitors tick stream health and manages feed stability state.
 * 
 * BUG-030: Only broadcasts WebSocket updates on actual state changes (not every heartbeat).
 */
@lombok.extern.slf4j.Slf4j
@Service
public class HeartbeatMonitor {

    private final SessionStateManager stateManager;
    private final CandleAggregator candleAggregator;
    private final MarketSessionService marketSessionService;
    
    private LocalDateTime lastTickReceivedTime = LocalDateTime.now();
    
    // BUG-030: Track previous state to detect changes
    private volatile boolean previousFeedStable = true;
    private volatile boolean previousHeartbeatAlive = true;
    private volatile String previousLastRejectReason = "INITIALIZED";

    public HeartbeatMonitor(
        SessionStateManager stateManager,
        CandleAggregator candleAggregator,
        MarketSessionService marketSessionService
    ) {
        this.stateManager = stateManager;
        this.candleAggregator = candleAggregator;
        this.marketSessionService = marketSessionService;
    }

    public synchronized void registerTick() {
        lastTickReceivedTime = LocalDateTime.now();
    }

    public synchronized String getLastHeartbeatTime() {
        return lastTickReceivedTime != null ? lastTickReceivedTime.toString() : null;
    }

    public synchronized boolean isHealthy() {
        return java.time.Duration.between(lastTickReceivedTime, LocalDateTime.now()).getSeconds() < 45;
    }

    /**
     * BUG-030: Monitor health with state change detection.
     * Only broadcasts WebSocket updates when state actually changes.
     */
    @Scheduled(fixedDelay = 2000)
    public void monitorHealth() {
        if (!marketSessionService.isMarketOpen()) {
            // BUG FIX: Suspend feed/heartbeat checks and stop candle aggregation when market is closed
            if (!"AWAITING_MARKET_OPEN".equals(previousLastRejectReason)) {
                // FIX: Only log and clear if we are transitioning to AWAITING_MARKET_OPEN
                log.info("Market closed - suspending feed/heartbeat monitoring");
                if (candleAggregator.isFeedUnstable()) {
                    log.info("Market closed - clearing feed instability");
                    candleAggregator.clearFeedInstability();
                }
                stateManager.update(current -> new TradingSessionSnapshot(
                    false, // sessionActive = false
                    current.regime(),
                    current.volatilityQualified(),
                    current.timePhase(),
                    current.tradesTaken(),
                    current.tradeActive(),
                    true,  // feedStable = true (suspended)
                    true,  // heartbeatAlive = true (suspended)
                    current.orHigh(),
                    current.orLow(),
                    current.cumulativeDailyLossR(),
                    current.consecutiveLosses(),
                    current.activeTradeReference(),
                    "AWAITING_MARKET_OPEN"
                ));
                previousFeedStable = true;
                previousHeartbeatAlive = true;
                previousLastRejectReason = "AWAITING_MARKET_OPEN";
            }
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long secondsSinceLastTick = java.time.Duration.between(lastTickReceivedTime, now).getSeconds();

        TradingSessionSnapshot currentState = stateManager.getSnapshot();
        if (secondsSinceLastTick >= 15 && secondsSinceLastTick < 45) {
            // Feed unstable state
            boolean newFeedStable = false;
            boolean newHeartbeatAlive = true;
            String newRejectReason = "FEED_UNSTABLE";
            
            // BUG-030: Only update if state changed
            if (newFeedStable != previousFeedStable || 
                newHeartbeatAlive != previousHeartbeatAlive ||
                !newRejectReason.equals(previousLastRejectReason)) {
                
                candleAggregator.markUnstable();
                stateManager.update(current -> new TradingSessionSnapshot(
                    current.sessionActive(),
                    current.regime(),
                    current.volatilityQualified(),
                    current.timePhase(),
                    current.tradesTaken(),
                    current.tradeActive(),
                    newFeedStable,
                    newHeartbeatAlive,
                    current.orHigh(),
                    current.orLow(),
                    current.cumulativeDailyLossR(),
                    current.consecutiveLosses(),
                    current.activeTradeReference(),
                    newRejectReason
                ));
            }
            
            // Update previous state tracking
            previousFeedStable = newFeedStable;
            previousHeartbeatAlive = newHeartbeatAlive;
            previousLastRejectReason = newRejectReason;
            
        } else if (secondsSinceLastTick >= 45) {
            // Heartbeat panic state
            boolean newFeedStable = false;
            boolean newHeartbeatAlive = false;
            String newRejectReason = "HEARTBEAT_PANIC";
            
            // BUG-030: Only update if state changed
            if (newFeedStable != previousFeedStable || 
                newHeartbeatAlive != previousHeartbeatAlive ||
                !newRejectReason.equals(previousLastRejectReason)) {
                
                candleAggregator.markUnstable();
                stateManager.update(current -> new TradingSessionSnapshot(
                    current.sessionActive(),
                    current.regime(),
                    current.volatilityQualified(),
                    current.timePhase(),
                    current.tradesTaken(),
                    false,  // trade active reset
                    newFeedStable,
                    newHeartbeatAlive,
                    current.orHigh(),
                    current.orLow(),
                    current.cumulativeDailyLossR(),
                    current.consecutiveLosses(),
                    null,   // active trade reference cleared
                    newRejectReason
                ));
            }
            
            // Update previous state tracking
            previousFeedStable = newFeedStable;
            previousHeartbeatAlive = newHeartbeatAlive;
            previousLastRejectReason = newRejectReason;
            
        } else {
            // Healthy state
            boolean newFeedStable = true;
            boolean newHeartbeatAlive = true;
            String newRejectReason = currentState.lastRejectReason();
            
            // BUG-030: Only update if state changed
            if (newFeedStable != previousFeedStable || 
                newHeartbeatAlive != previousHeartbeatAlive ||
                !newRejectReason.equals(previousLastRejectReason)) {
                
                stateManager.update(current -> new TradingSessionSnapshot(
                    current.sessionActive(),
                    current.regime(),
                    current.volatilityQualified(),
                    current.timePhase(),
                    current.tradesTaken(),
                    current.tradeActive(),
                    newFeedStable,
                    newHeartbeatAlive,
                    current.orHigh(),
                    current.orLow(),
                    current.cumulativeDailyLossR(),
                    current.consecutiveLosses(),
                    current.activeTradeReference(),
                    newRejectReason
                ));
            }
            
            // Update previous state tracking
            previousFeedStable = newFeedStable;
            previousHeartbeatAlive = newHeartbeatAlive;
            previousLastRejectReason = newRejectReason;
        }
    }
}
