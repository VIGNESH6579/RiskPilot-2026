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
@Service
public class HeartbeatMonitor {

    private final SessionStateManager stateManager;
    private final CandleAggregator candleAggregator;
    
    private LocalDateTime lastTickReceivedTime = LocalDateTime.now();
    
    // BUG-030: Track previous state to detect changes
    private volatile boolean previousFeedStable = true;
    private volatile boolean previousHeartbeatAlive = true;
    private volatile String previousLastRejectReason = "INITIALIZED";

    public HeartbeatMonitor(SessionStateManager stateManager, CandleAggregator candleAggregator) {
        this.stateManager = stateManager;
        this.candleAggregator = candleAggregator;
    }

    public synchronized void registerTick() {
        lastTickReceivedTime = LocalDateTime.now();
    }

    /**
     * BUG-030: Monitor health with state change detection.
     * Only broadcasts WebSocket updates when state actually changes.
     */
    @Scheduled(fixedDelay = 2000)
    public void monitorHealth() {
        LocalDateTime now = LocalDateTime.now();
        long secondsSinceLastTick = java.time.Duration.between(lastTickReceivedTime, now).getSeconds();

        TradingSessionSnapshot currentState = stateManager.getSnapshot();
        boolean stateChanged = false;

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
                    current.activeTradeReference(),
                    newRejectReason
                ));
                stateChanged = true;
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
                    null,   // active trade reference cleared
                    newRejectReason
                ));
                stateChanged = true;
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
                    current.activeTradeReference(),
                    newRejectReason
                ));
                stateChanged = true;
            }
            
            // Update previous state tracking
            previousFeedStable = newFeedStable;
            previousHeartbeatAlive = newHeartbeatAlive;
            previousLastRejectReason = newRejectReason;
        }
    }
}
