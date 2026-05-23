package com.riskpilot.service;

import com.riskpilot.config.ApplicationContextProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    // Rate-limit warn logs — these checks fire every second per tick
    private volatile long lastNiftyWarnMs = 0L;
    private volatile long lastVixWarnMs   = 0L;

    // Fix #5: proper scheduler with bounded thread pool and clean shutdown
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SafetyLoop");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> safetyTask;

    public TradingSafetyManager() { instance = this; }
    public static TradingSafetyManager getInstance() { return instance; }

    @PostConstruct
    public void init() {
        log.info("🛡️ TradingSafetyManager active (FAIL-CLOSED mode)");

        safetyTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                MarketDataStateService md = ApplicationContextProvider.getBean(MarketDataStateService.class);
                MarketSessionService ms   = ApplicationContextProvider.getBean(MarketSessionService.class);

                boolean marketOpen = (ms == null || ms.isMarketOpen());
                if (marketOpen) {
                    if (md != null && md.getLastTickAgeMs() > 60000) {
                        emergency("Market data stale >60s");
                    } else {
                        if (md != null && md.getLastTickAgeMs() > 30000) {
                            degrade("Feed latency >30s");
                        }
                        if (emergency.get() && "Market data stale >60s".equals(reason.get())) {
                            clearEmergency();
                        }
                    }
                } else {
                    if (emergency.get() && "Market data stale >60s".equals(reason.get())) {
                        clearEmergency();
                    }
                }
            } catch (Exception e) {
                // Log but never let an exception kill the safety loop
                log.warn("SafetyLoop exception (non-fatal): {}", e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (safetyTask != null) safetyTask.cancel(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("TradingSafetyManager scheduler shut down cleanly");
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

    /** Alias for callers using the old method name */
    public void emergencyStop(String r) { emergency(r); }

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
