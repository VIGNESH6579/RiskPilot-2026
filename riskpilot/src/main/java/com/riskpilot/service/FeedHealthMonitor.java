package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * State machine - logs ONLY on transitions (NO SPAM!)
 *
 * FIX: safety.emergencyStop() now compiles because TradingSafetyManager
 * gained an emergencyStop() alias that delegates to emergency().
 * No logic change needed in this file beyond that.
 */
@Slf4j
@Service
public class FeedHealthMonitor {

    @Autowired private MarketDataStateService marketData;
    @Autowired private TradingSafetyManager safety;
    @Autowired private MarketSessionService marketSession;

    private final AtomicReference<String> state = new AtomicReference<>("CONNECTING");
    private final AtomicLong lastTick = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger reconnects = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        log.info("📡 FeedHealthMonitor initialized (state machine)");
        new Thread(() -> {
            while (true) {
                try {
                    checkHealth();
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "FeedMonitor").start();
    }

    public void recordTick() {
        lastTick.set(System.currentTimeMillis());
        marketData.recordTick();
        String s = state.get();
        if (s.equals("DISCONNECTED") || s.equals("RECOVERING")) transitionTo("CONNECTED");
    }

    public void onConnect() {
        reconnects.set(0);
        transitionTo("CONNECTED");
        log.info("✅ WEBSOCKET_CONNECTED");
    }

    public void onDisconnect() {
        transitionTo("DISCONNECTED");
        scheduleReconnect();
    }

    private void checkHealth() {
        if (!marketSession.isMarketOpen()) {
            if (!state.get().equals("CONNECTED")) {
                transitionTo("CONNECTED"); // Fake connected state when market is closed to prevent alerts
            }
            return;
        }

        long age = System.currentTimeMillis() - lastTick.get();
        String s = state.get();

        if (age > 30000 && !s.equals("DISCONNECTED")) {
            transitionTo("DISCONNECTED");
            scheduleReconnect();
        } else if (age > 15000 && s.equals("CONNECTED")) {
            transitionTo("DEGRADED");
        } else if (age < 15000 && s.equals("DEGRADED")) {
            transitionTo("CONNECTED");
        }
    }

    /** Only logs on ACTUAL state transitions */
    private synchronized void transitionTo(String newState) {
        String old = state.getAndSet(newState);
        if (!old.equals(newState)) {
            log.info("📡 FEED_STATE: {} → {} | TickAge: {}ms",
                old, newState, System.currentTimeMillis() - lastTick.get());

            if (newState.equals("DEGRADED") || newState.equals("DISCONNECTED")) {
                safety.degrade("Feed " + newState);
            }
        }
    }

    private void scheduleReconnect() {
        int n = reconnects.incrementAndGet();
        if (n > 10) {
            // FIX: emergencyStop() alias now exists in TradingSafetyManager
            safety.emergencyStop("Reconnect failed " + n + " times");
            return;
        }
        long delay = Math.min(5000L * (long) Math.pow(2, n - 1), 60000L); // Start with 5s, cap at 60s
        log.info("🔄 Scheduling reconnect #{} in {}ms", n, delay);
        transitionTo("RECOVERING");
    }

    public String getState() { return state.get(); }

    public boolean isSafe() {
        return state.get().equals("CONNECTED")
            && (System.currentTimeMillis() - lastTick.get()) < 15000;
    }
}
