package com.riskpilot.service;

import com.riskpilot.model.Candle;
import com.riskpilot.model.Signal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TrapEngine {
    private final double riskCapital;
    private final int lotSize;

    public TrapEngine(
        @Value("${TRAP_RISK_CAPITAL:1000}") double riskCapital,
        @Value("${NIFTY_LOT_SIZE:75}") int lotSize
    ) {
        this.riskCapital = riskCapital;
        this.lotSize = Math.max(1, lotSize);
    }

    public Signal detectTrap(List<Candle> history, double localSupport, double localResistance, double vix) {
        if (history.size() < 7) {
            return null;
        }

        Candle t0 = history.get(history.size() - 1);
        Candle t1 = history.get(history.size() - 2);

        double sumRange = 0.0;
        for (int i = history.size() - 7; i <= history.size() - 3; i++) {
            Candle candle = history.get(i);
            sumRange += candle.high - candle.low;
        }
        double avgRange = sumRange / 5.0;
        double t1Range = t1.high - t1.low;
        if (t1Range <= avgRange) {
            return null;
        }

        int vixConfidence = vixConfidence(vix);
        if (vixConfidence == 0) {
            return null;
        }

        if (t1.high > localResistance) {
            double breakoutDepth = t1.high - localResistance;
            if (breakoutDepth >= 6.0) {
                double t1Midpoint = (t1.high + t1.low) / 2.0;
                if (t0.close < localResistance && t0.close < t1Midpoint) {
                    double entry = t0.close;
                    double stopLoss = t1.high + 10.0;
                    double distanceToStop = Math.abs(stopLoss - entry);
                    if (distanceToStop <= 120.0) {
                        return buildSignal("SHORT", entry, stopLoss, vixConfidence, distanceToStop);
                    }
                }
            }
        }

        if (t1.low < localSupport) {
            double breakdownDepth = localSupport - t1.low;
            if (breakdownDepth >= 6.0) {
                double t1Midpoint = (t1.high + t1.low) / 2.0;
                if (t0.close > localSupport && t0.close > t1Midpoint) {
                    double entry = t0.close;
                    double stopLoss = t1.low - 10.0;
                    double distanceToStop = Math.abs(entry - stopLoss);
                    if (distanceToStop <= 120.0) {
                        return buildSignal("LONG", entry, stopLoss, vixConfidence, distanceToStop);
                    }
                }
            }
        }

        return null;
    }

    private Signal buildSignal(String direction, double entry, double stopLoss, int vixConfidence, double distanceToStop) {
        Signal signal = new Signal();
        signal.setSymbol("NIFTY");
        signal.setDirection(direction);
        signal.setEntry(entry);
        signal.setStopLoss(stopLoss);
        signal.setTarget(0.0);
        signal.setConfidence(vixConfidence);
        signal.setQuantity(calculateQuantity(distanceToStop));
        return signal;
    }

    private int calculateQuantity(double distanceToStop) {
        if (distanceToStop <= 0.0) {
            return lotSize;
        }

        int rawUnits = Math.max(lotSize, (int) Math.floor(riskCapital / distanceToStop));
        int lots = (int) Math.ceil(rawUnits / (double) lotSize);
        return Math.max(lotSize, lots * lotSize);
    }

    private int vixConfidence(double vix) {
        if (Double.isNaN(vix)) {
            return 50;
        }
        if (vix < 11.0 || vix > 28.0) {
            return 0;
        }
        if (vix < 13.0 || vix > 22.0) {
            return 30;
        }
        if (vix < 15.0 || vix > 20.0) {
            return 70;
        }
        return 100;
    }
}
