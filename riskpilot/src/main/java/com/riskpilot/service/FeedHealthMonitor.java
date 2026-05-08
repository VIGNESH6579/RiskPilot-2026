package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** State machine - logs ONLY on transitions (NO SPAM!) */
@Slf4j
@Service
public class FeedHealthMonitor {

    @Autowired private MarketDataStateService marketData;
    @Autowired private TradingSafetyManager safety;

    private final AtomicReference<String> state = new AtomicReference<>("CONNECTING");
    private final AtomicLong lastTick = new AtomicLong(0);
    private final AtomicInteger reconnects = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        log.info("📡 FeedHealthMonitor initialized (state machine)");
        new Thread(() -> {
            while (true) {
                try {
                    checkHealth();
                    Thread.sleep(5000);
                } catch (InterruptedException e) { break; }
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

    /**
     * KEY FIX: Only logs on ACTUAL state transitions!
     * This eliminates the FEED_RECOVERY spam
     */
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
            safety.emergencyStop("Reconnect failed " + n + " times"); 
            return; 
        }
        long delay = Math.min(1000L * (long)Math.pow(2, n-1), 30000L);
        log.info("🔄 Scheduling reconnect #{} in {}ms", n, delay);
        transitionTo("RECOVERING");
    }

    public String getState() { return state.get(); }
    public boolean isSafe() { 
        return state.get().equals("CONNECTED") && 
               (System.currentTimeMillis()-lastTick.get()) < 15000; 
    }
}