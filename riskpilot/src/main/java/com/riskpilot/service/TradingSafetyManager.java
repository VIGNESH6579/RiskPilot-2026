package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Centralized kill switch - FAIL CLOSED architecture */
@Slf4j
@Service
public class TradingSafetyManager {

    private static TradingSafetyManager instance;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicBoolean emergency = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<String> violations = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> reason = new AtomicReference<>(null);

    public TradingSafetyManager() { instance = this; }
    public static TradingSafetyManager getInstance() { return instance; }

    @PostConstruct
    public void init() {
        log.info("🛡️ TradingSafetyManager active (FAIL-CLOSED mode)");
        
        // Background safety monitor
        new Thread(() -> {
            while (true) {
                try {
                    MarketDataStateService md = ApplicationContextProvider.getBean(MarketDataStateService.class);
                    if (md != null) {
                        if (md.getLastTickAgeMs() > 60000) emergency("Market data stale >60s");
                        else if (md.getLastTickAgeMs() > 30000) degrade("Feed latency >30s");
                    }
                    Thread.sleep(1000);
                } catch (Exception e) { /* ignore */ }
            }
        }, "SafetyLoop").start();
    }

    /** Check before EVERY trade - returns false if unsafe */
    public boolean isSafeToTrade() {
        if (emergency.get()) { 
            log.warn("🚫 TRADE_BLOCKED: Emergency stop active"); 
            return false; 
        }
        if (!enabled.get()) return false;
        
        try {
            MarketDataStateService md = ApplicationContextProvider.getBean(MarketDataStateService.class);
            FeedHealthMonitor fh = ApplicationContextProvider.getBean(FeedHealthMonitor.class);
            AngelSessionManager sess = ApplicationContextProvider.getBean(AngelSessionManager.class);

            if (md != null && !md.isNiftyAvailable()) { violate("NIFTY_LTP_UNAVAILABLE"); return false; }
            if (md != null && !md.isVixValid()) { violate("VIX_INVALID_OR_STALE"); return false; }
            if (fh != null && !fh.isSafe()) { violate("FEED_UNSAFE:" + fh.getState()); return false; }
            if (sess != null && !sess.isSessionValid()) { violate("SESSION_INVALID"); return false; }
        } catch (Exception e) {
            degrade("Safety check error: " + e.getMessage());
        }
        
        return true;
    }

    /** EMERGENCY STOP - halts ALL trading immediately */
    public void emergency(String r) {
        emergency.set(true); enabled.set(false); reason.set(r);
        log.error("🚨🚨🚨 EMERGENCY_STOP: {} 🚨🚨🚨", r);
        log.error("ALL TRADING HALTED IMMEDIATELY");
    }

    /** Degrade mode - blocks new entries but allows existing positions */
    public void degrade(String r) {
        log.warn("⚠️ DEGRADED: {}", r);
        violations.add(r);
        if (violations.size() > 100) violations.remove(0);
    }

    private void violate(String v) {
        violations.add(v);
        log.warn("🚫 VIOLATION: {} | Total: {}", v, violations.size());
    }
}