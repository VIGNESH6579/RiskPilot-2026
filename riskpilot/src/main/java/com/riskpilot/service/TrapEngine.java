package com.riskpilot.service;

import com.riskpilot.model.Candle;
import com.riskpilot.model.Signal;

import java.util.List;

public class TrapEngine {

    public Signal detectTrap(List<Candle> history, double localSupport, double localResistance, double vix) {
        if (history.size() < 7) return null; 

        if (vix < 15 || vix > 18) {
            return null;
        }

        Candle t0 = history.get(history.size() - 1); 
        Candle t1 = history.get(history.size() - 2); 

        double sumRange = 0;
        for(int i = history.size() - 7; i <= history.size() - 3; i++) {
            Candle c = history.get(i);
            sumRange += (c.high - c.low);
        }
        double avgRange = sumRange / 5.0;
        
        double t1Range = t1.high - t1.low;

        if (t1Range <= avgRange) {
            return null;
        }
        
        double breakoutDepth = t1.high - localResistance;
        if (breakoutDepth < 6.0) {
            return null;
        }

        if (t1.high > localResistance) {
            double t1Midpoint = (t1.high + t1.low) / 2.0;

            if (t0.close < localResistance && t0.close < t1Midpoint) {
                double entry = t0.close;
                
                double sl = t1.high + 10.0;
                double tp1 = entry - 20.0; 
                
                double distanceToSL = Math.abs(sl - entry);

                // RISK NORMALIZATION: Cap maximum structural risk
                if (distanceToSL > 120.0) return null;

                Signal s = new Signal();
                s.setSymbol("BANKNIFTY");
                s.setDirection("SHORT");
                s.setEntry(entry);
                s.setStopLoss(sl);
                s.setTarget(tp1); 
                
                double riskCapital = 100000 * 0.01;
                // Position Sizing normalized against risk mapping exactly 1% scale
                int qty = (int) (riskCapital / distanceToSL);
                if (qty < 2) qty = 2; 
                if (qty % 2 != 0) qty++; 
                
                s.setConfidence(qty);
                return s;
            }
        }

        return null;
    }
}
