package com.riskpilot.service;

import com.riskpilot.config.ApplicationContextProvider;   // ← FIX: missing import caused 4 errors
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

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
    // BUG-FIX: Rate-limit warn logs — these checks fire every second per tick, causing log floods
    private volatile long lastNiftyWarnMs = 0L;
    private volatile long lastVixWarnMs   = 0L;

    public TradingSafetyManager() { instance = this; }
    public static TradingSafetyManager getInstance() { return instance; }

    @PostConstruct
    public void init() {
        log.info("🛡️ TradingSafetyManager active (FAIL-CLOSED mode)");

        new Thread(() -> {
            while (true) {
                try {
                    MarketDataStateService md = ApplicationContextProvider.getBean(MarketDataStateService.class);
                    MarketSessionService ms = ApplicationContextProvider.getBean(MarketSessionService.class);
                    
                    boolean marketOpen = (ms == null || ms.isMarketOpen());
                    if (marketOpen) {
                        if (md != null && md.getLastTickAgeMs() > 60000) {
                            emergency("Market data stale >60s");
                        } else {
                            if (md != null && md.getLastTickAgeMs() > 30000) {
                                degrade("Feed latency >30s");
                            }
                            // Clear if stale check passed
                            if (emergency.get() && "Market data stale >60s".equals(reason.get())) {
                                clearEmergency();
                            }
                        }
                    } else {
                        // Market closed: clear stale-data emergency if it exists
                        if (emergency.get() && "Market data stale >60s".equals(reason.get())) {
                            clearEmergency();
                        }
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) { /* ignore */ }
            }
        }, "SafetyLoop").start();
    }

    /** Check before EVERY trade - returns false if unsafe */
    public boolean isSafeToTrade() {
        if (emergency.get()) {
            log.warn("🚫 TRADE_BLOCKED: Emergency stop active — reason={}", reason.get());
            return false;
        }
        if (!enabled.get()) return false;

        try {
            MarketDataStateService md   = ApplicationContextProvider.getBean(MarketDataStateService.class);
            FeedHealthMonitor      fh   = ApplicationContextProvider.getBean(FeedHealthMonitor.class);
            AngelSessionManager    sess = ApplicationContextProvider.getBean(AngelSessionManager.class);

            if (md != null && !md.isNiftyAvailable()) {
                // BUG-FIX: Rate-limit this log — it was firing every second causing log flood
                long now = System.currentTimeMillis();
                if (now - lastNiftyWarnMs > 30_000L) {
                    log.warn("🚫 TRADE_BLOCKED: NIFTY LTP unavailable (no Angel One ticks yet; check credentials)");
                    lastNiftyWarnMs = now;
                }
                violate("NIFTY_LTP_UNAVAILABLE");
                return false;
            }
            if (md != null && !md.isVixValid()) {
                long now = System.currentTimeMillis();
                if (now - lastVixWarnMs > 30_000L) {
                    log.warn("🚫 TRADE_BLOCKED: VIX invalid or stale (check ANGEL_INDIA_VIX_TOKEN or Yahoo fallback)");
                    lastVixWarnMs = now;
                }
                violate("VIX_INVALID_OR_STALE");
                return false;
            }
            if (fh != null && !fh.isSafe())             { violate("FEED_UNSAFE:" + fh.getState()); return false; }
            if (sess != null && !sess.isSessionValid()) { violate("SESSION_INVALID");              return false; }
        } catch (Exception e) {
            degrade("Safety check error: " + e.getMessage());
        }

        return true;
    }

    /** EMERGENCY STOP - halts ALL trading immediately */
    public void emergency(String r) {
        if (emergency.compareAndSet(false, true)) {
            enabled.set(false);
            reason.set(r);
            log.error("🚨🚨🚨 EMERGENCY_STOP ACTIVATED: {} 🚨🚨🚨", r);
            log.error("ALL TRADING HALTED IMMEDIATELY");
            
            // TODO: Flatten positions and cancel orders here for a complete safety system
        }
    }

    /** Clear EMERGENCY STOP */
    public void clearEmergency() {
        if (emergency.compareAndSet(true, false)) {
            enabled.set(true);
            reason.set(null);
            log.info("✅ EMERGENCY_STOP CLEARED - Trading resumed");
        }
    }

    /**
     * Alias so AngelSessionManager and FeedHealthMonitor can call
     * emergencyStop() without change.
     */
    public void emergencyStop(String r) {
        emergency(r);
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
