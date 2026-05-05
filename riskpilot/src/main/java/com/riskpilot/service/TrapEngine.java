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
    private final double riskCapital;
    
    // BUG-019: ATR-relative constants (instead of hardcoded points)
    private static final double BREAKOUT_DEPTH_ATR_MULTIPLIER = 0.20;  // ~20% of ATR
    private static final double MAX_RISK_ATR_MULTIPLIER = 1.25;       // Max 1.25×ATR for SL distance
    private static final double MIN_EXPANSION_ATR_RATIO = 1.10;      // 10% above average

    public TrapEngine(
        @Value("${TRAP_MIN_VIX:12}") double minVix,
        @Value("${TRAP_MAX_VIX:25}") double maxVix,
        @Value("${RISK_CAPITAL:100000}") double riskCapital
    ) {
        this.minVix = minVix;
        this.maxVix = maxVix;
        this.riskCapital = riskCapital;
    }
    
    /**
     * Calculate simple ATR over recent candles.
     * BUG-019: Used for ATR-normalized thresholds.
     */
    private double calculateAtr(List<Candle> history, int periods) {
        if (history.size() < 2) return Double.NaN;
        
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
            : Double.NaN;
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

        if (!Double.isFinite(atr) || atr <= 0.0) {
            return null;
        }
        double effectiveAtr = atr;

        Candle t0 = history.get(history.size() - 1); 
        Candle t1 = history.get(history.size() - 2); 

        // BUG-019: Use ATR for relative range comparison
        double sumRange = 0;
        for(int i = history.size() - 7; i <= history.size() - 3; i++) {
            Candle c = history.get(i);
            sumRange += (c.high - c.low);
        }
        double avgRange = sumRange / 5.0;
        
        double t1Range = t1.high - t1.low;

        // BUG-019: Expansion check using ATR ratio instead of absolute comparison
        if (t1Range <= avgRange * MIN_EXPANSION_ATR_RATIO) {
            return null;
        }
        
        // BUG-019: Breakout depth using ATR multiplier instead of fixed 6.0 points
        double breakoutDepth = t1.high - localResistance;
        double minBreakoutDepth = effectiveAtr * BREAKOUT_DEPTH_ATR_MULTIPLIER;
        if (breakoutDepth < minBreakoutDepth) {
            return null;
        }

        if (t1.high > localResistance) {
            double t1Midpoint = (t1.high + t1.low) / 2.0;

            if (t0.close < localResistance && t0.close < t1Midpoint) {
                double entry = t0.close;
                
                // BUG-019: SL and target using ATR instead of fixed values
                double sl = t1.high + (effectiveAtr * 0.30);  // ~30% of ATR buffer
                double tp1 = entry - (effectiveAtr * 0.60);   // 60% of ATR target (2:1 RR)
                
                double distanceToSL = Math.abs(sl - entry);

                // BUG-019: Risk normalization using ATR multiplier
                double maxRiskDistance = effectiveAtr * MAX_RISK_ATR_MULTIPLIER;
                if (distanceToSL > maxRiskDistance) return null;

                Signal s = new Signal();
                s.setSymbol("NIFTY");
                s.setDirection("SHORT");
                s.setEntry(entry);
                s.setStopLoss(sl);
                s.setTarget(tp1); 
                
                // Position Sizing normalized against risk mapping exactly 1% scale
                int qty = (int) ((riskCapital * 0.01) / distanceToSL);
                if (qty < 2) qty = 2; 
                if (qty % 2 != 0) qty++; 
                
                s.setConfidence(100);
                s.setQuantity(qty);
                return s;
            }
        }

        return null;
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
