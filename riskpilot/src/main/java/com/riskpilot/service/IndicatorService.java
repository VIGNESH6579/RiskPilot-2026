package com.riskpilot.service;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IndicatorService {

    // ATR calculation: divides by the actual number of true ranges computed,
    // not a hardcoded period, so it works correctly with any data length.
    public double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes) {
        if (highs == null || highs.size() < 2) return 0.0;

        double totalTR = 0.0;
        int count = 0;

        for (int i = 1; i < highs.size(); i++) {
            double tr = Math.max(
                    highs.get(i) - lows.get(i),
                    Math.max(
                            Math.abs(highs.get(i) - closes.get(i - 1)),
                            Math.abs(lows.get(i) - closes.get(i - 1))
                    )
            );
            totalTR += tr;
            count++;
        }

        // BUG-FIX: was dividing by hardcoded 14 even when only N<14 candles were available,
        // producing an inflated ATR value. Divide by actual count instead.
        return count > 0 ? totalTR / count : 0.0;
    }
}
