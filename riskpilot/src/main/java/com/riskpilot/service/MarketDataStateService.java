package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;  // ← FIXED: Java 17 uses JAKARTA, not JAVAX!
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SINGLE SOURCE OF TRUTH for all market data
 * VIX FIX: NO MORE HARDCODED 15.0!
 * Compatible with Spring Boot 4.x / Java 17
 */
@Slf4j
@Service
public class MarketDataStateService {

    private final AtomicReference<NiftySpot> niftySpot = new AtomicReference<>();
    private final AtomicReference<VixData> vixData = new AtomicReference<>();
    private final ConcurrentHashMap<String, Object> optionChains = new ConcurrentHashMap<>();
    private final AtomicLong lastTickTime = new AtomicLong(0);

    // Staleness thresholds
    private static final long MAX_SPOT_STALE_MS = 5000;   // 5 seconds
    private static final long MAX_VIX_STALE_MS = 30000;    // 30 seconds

    @PostConstruct  // ← FIXED: Now uses jakarta.annotation.PostConstruct
    public void init() {
        log.info("📊 MarketDataStateService initialized (SINGLE source of truth)");
    }

    /** Update NIFTY from WebSocket tick (PRIMARY source) */
    public void updateNiftyFromWebSocket(double ltp, double chg, double chgPct) {
        niftySpot.set(new NiftySpot(ltp, chg, chgPct, Instant.now(), "WS"));
        lastTickTime.set(System.currentTimeMillis());
    }

    /** Update NIFTY from REST fallback (EMERGENCY only) */
    public void updateNiftyFromRest(double ltp) {
        NiftySpot current = niftySpot.get();
        if (current == null || isSpotStale()) {
            niftySpot.set(new NiftySpot(ltp, 0, 0, Instant.now(), "REST"));
            log.warn("⚠️ Using REST fallback for spot: {}", ltp);
        }
    }

    /** Get NIFTY spot with staleness check */
    public NiftySpot getNiftySpot() {
        NiftySpot s = niftySpot.get();
        if (s == null) {
            TradingSafetyManager.getInstance().degrade("LTP unavailable");
            return null;
        }
        if (isSpotStale()) {
            log.error("❌ SPOT STALE: {}ms old", getSpotAgeMs());
            TradingSafetyManager.getInstance().degrade("Spot stale");
        }
        return s;
    }

    public boolean isNiftyAvailable() {
        NiftySpot s = niftySpot.get();
        return s != null && !isSpotStale();
    }

    private boolean isSpotStale() { return getSpotAgeMs() > MAX_SPOT_STALE_MS; }
    
    private long getSpotAgeMs() {
        NiftySpot s = niftySpot.get();
        return s == null ? Long.MAX_VALUE : Instant.now().toEpochMilli() - s.timestamp.toEpochMilli();
    }

    // ==================== VIX SYSTEM - CRITICAL FIX ====================

    public void updateVix(double value, Instant ts) {
        vixData.set(new VixData(value, ts, true));
        log.debug("✅ VIX updated: {}", value);
    }

    /**
     * GET VIX - NEVER RETURNS FAKE 15.0!
     * Throws exception if unavailable/stale - strategy MUST stop trading
     */
    public double getVix() {
        VixData v = vixData.get();

        if (v == null || !v.valid) {
            log.error("🚨 VIX_UNAVAILABLE - OLD CODE would return fake 15.0!");
            TradingSafetyManager.getInstance().degrade("VIX unavailable");
            throw new IllegalStateException(
                "VIX not available - CANNOT trade safely. Strategy must catch exception and HALT.");
        }

        long age = Instant.now().toEpochMilli() - v.timestamp.toEpochMilli();
        if (age > MAX_VIX_STALE_MS) {
            log.error("🚨 VIX_STALE: {}ms old (max: {}ms)", age, MAX_VIX_STALE_MS);
            TradingSafetyManager.getInstance().degrade("VIX stale - blocking trades");
            throw new IllegalStateException(
                "VIX too stale (" + age + "ms) for safe trading. Max allowed: " + MAX_VIX_STALE_MS + "ms");
        }

        log.debug("✅ Returning real VIX: {} (age: {}ms)", v.value, age);
        return v.value;
    }

    public boolean isVixValid() {
        VixData v = vixData.get();
        return v != null && v.valid && 
               (Instant.now().toEpochMilli() - v.timestamp.toEpochMilli()) <= MAX_VIX_STALE_MS;
    }

    public long getLastTickAgeMs() { return System.currentTimeMillis() - lastTickTime.get(); }
    public boolean isFeedHealthy() { return getLastTickAgeMs() < 10000; }
    public void recordTick() { lastTickTime.set(System.currentTimeMillis()); }

    // Immutable DTOs
    static class NiftySpot {
        final double ltp, change, changePct;
        final Instant timestamp;
        final String source;
        NiftySpot(double l, double c, double cp, Instant t, String s) {
            ltp=l; change=c; changePct=cp; timestamp=t; source=s;
        }
    }

    static class VixData {
        final double value;
        final Instant timestamp;
        final boolean valid;
        VixData(double v, Instant t, boolean val) { value=v; timestamp=t; valid=val; }
    }
}