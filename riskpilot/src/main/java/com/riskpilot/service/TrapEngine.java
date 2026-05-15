package com.riskpilot.service;

import com.riskpilot.model.Candle;
import com.riskpilot.model.Signal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TrapEngine detects trap signals based on VIX range, consolidation patterns, and breakout criteria.
 * 
 * BUG-019: ATR-normalized constants to avoid hardcoded absolute values.
 * Instead of fixed point thresholds, thresholds are now ATR-relative.
 */
@Service
public class TrapEngine {
    private final double minVix;
    private final double maxVix;
    private final double accountCapital;
    private final double riskPerTradePct;

    // BUG-019: ATR-relative constants (instead of hardcoded points)
    private static final double BREAKOUT_DEPTH_ATR_MULTIPLIER = 0.20;  // ~20% of ATR
    private static final double MAX_RISK_ATR_MULTIPLIER = 1.25;       // Max 1.25×ATR for SL distance
    private static final double MIN_EXPANSION_ATR_RATIO = 1.10;      // 10% above average

    public TrapEngine(
        @Value("${TRAP_MIN_VIX:12}") double minVix,
        @Value("${TRAP_MAX_VIX:25}") double maxVix,
        @Value("${TRAP_ACCOUNT_CAPITAL:100000}") double accountCapital,
        @Value("${TRAP_RISK_PCT:0.01}") double riskPerTradePct
    ) {
        this.minVix = minVix;
        this.maxVix = maxVix;
        this.accountCapital = accountCapital > 0 ? accountCapital : 100000;
        this.riskPerTradePct = (riskPerTradePct > 0 && riskPerTradePct <= 0.05) ? riskPerTradePct : 0.01;
    }
    
    /**
     * Calculate simple ATR over recent candles.
     * BUG-019: Used for ATR-normalized thresholds.
     */
    private double calculateAtr(List<Candle> history, int periods) {
        if (history.size() < 2) return 50.0; // Default ATR for NIFTY
        
        int start = Math.max(0, history.size() - periods);
        double totalRange = 0.0;
        
        for (int i = start + 1; i < history.size(); i++) {
            Candle current = history.get(i);
            Candle previous = history.get(i - 1);
            
            double highLow = current.high - current.low;
            double highClose = Math.abs(current.high - previous.close);
            double lowClose = Math.abs(current.low - previous.close);
            
            double trueRange = Math.max(highLow, Math.max(highClose, lowClose));
            totalRange += trueRange;
        }
        
        return (history.size() - start - 1) > 0 
            ? totalRange / (history.size() - start - 1) 
            : 50.0;
    }

    /**
     * BUG-019: Detect trap signal with ATR-normalized thresholds.
     * 
     * @param history Recent candle history
     * @param localSupport Local support level
     * @param localResistance Local resistance level  
     * @param vix Current VIX value
     * @param atr Current ATR value (for normalization)
     * @return Signal if trap detected, null otherwise
     */
    public Signal detectTrap(List<Candle> history, double localSupport, double localResistance, double vix, double atr) {
        if (history.size() < 7) return null; 

        if (vix < minVix || vix > maxVix) {
            return null;
        }

        double effectiveAtr = Double.isFinite(atr) && atr > 0.0 ? atr : 25.0;

        Candle t0 = history.get(history.size() - 1); 
        Candle t1 = history.get(history.size() - 2); 

        double sumRange = 0;
        for(int i = history.size() - 7; i <= history.size() - 3; i++) {
            Candle c = history.get(i);
            sumRange += (c.high - c.low);
        }
        double avgRange = sumRange / 5.0;
        double t1Range = t1.high - t1.low;

        // 1. Range Expansion Check (Requires breakout candle to be relatively large)
        if (t1Range <= avgRange * MIN_EXPANSION_ATR_RATIO) {
            return null;
        }

        // 2. SHORT TRAP DETECTION (Bearish Reversal from Resistance)
        if (t1.high > localResistance) {
            double breakoutDepth = t1.high - localResistance;
            double minBreakoutDepth = effectiveAtr * BREAKOUT_DEPTH_ATR_MULTIPLIER;
            
            if (breakoutDepth >= minBreakoutDepth) {
                double t1Midpoint = (t1.high + t1.low) / 2.0;
                if (t0.close < localResistance && t0.close < t1Midpoint) {
                    double sl = t1.high + (effectiveAtr * 0.30);
                    double slDistance = Math.abs(sl - t0.close);
                    double target = t0.close - (slDistance * 1.5); // FIX: Guarantee 1.5:1 R:R
                    return createSignal("SHORT", t0.close, sl, target, effectiveAtr);
                }
            }
        }

        // 3. LONG TRAP DETECTION (Bullish Reversal from Support)
        if (t1.low < localSupport) {
            double breakoutDepth = localSupport - t1.low;
            double minBreakoutDepth = effectiveAtr * BREAKOUT_DEPTH_ATR_MULTIPLIER;

            if (breakoutDepth >= minBreakoutDepth) {
                double t1Midpoint = (t1.high + t1.low) / 2.0;
                if (t0.close > localSupport && t0.close > t1Midpoint) {
                    double sl = t1.low - (effectiveAtr * 0.30);
                    double slDistance = Math.abs(sl - t0.close);
                    double target = t0.close + (slDistance * 1.5); // FIX: Guarantee 1.5:1 R:R
                    return createSignal("LONG", t0.close, sl, target, effectiveAtr);
                }
            }
        }

        return null;
    }

    private Signal createSignal(String direction, double entry, double sl, double target, double effectiveAtr) {
        double distanceToSL = Math.abs(sl - entry);
        double maxRiskDistance = effectiveAtr * MAX_RISK_ATR_MULTIPLIER;
        
        if (distanceToSL > maxRiskDistance || distanceToSL < (effectiveAtr * 0.1)) {
            return null;
        }

        // Risk-Reward Guard (Minimum 1.5:1)
        double potentialReward = Math.abs(target - entry);
        if (potentialReward / distanceToSL < 1.5) {
            return null;
        }

        Signal s = new Signal();
        s.setSymbol("NIFTY");
        s.setDirection(direction);
        s.setEntry(entry);
        s.setStopLoss(sl);
        s.setTarget(target);
        
        double riskCapital = accountCapital * riskPerTradePct; // Configurable: default 1% of ₹1L
        int qty = (int) (riskCapital / distanceToSL);
        if (qty < 2) qty = 2;
        if (qty % 2 != 0) qty++; // Round to even lot-like sizing
        
        s.setConfidence(100);
        s.setQuantity(qty);
        return s;
    }
    
    /**
     * Legacy method without ATR parameter - calculates ATR internally.
     * @deprecated Use detectTrap with ATR parameter for consistent normalization.
     */
    @Deprecated
    public Signal detectTrap(List<Candle> history, double localSupport, double localResistance, double vix) {
        double atr = calculateAtr(history, 14);
        return detectTrap(history, localSupport, localResistance, vix, atr);
    }
}
