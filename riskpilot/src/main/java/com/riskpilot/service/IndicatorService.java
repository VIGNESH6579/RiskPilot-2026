package com.riskpilot.service;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IndicatorService {

    // Simplified ATR calculation
    public double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes) {

        int period = 14;
        double atr = 0;

        for (int i = 1; i < highs.size(); i++) {
            double tr = Math.max(
                    highs.get(i) - lows.get(i),
                    Math.max(
                            Math.abs(highs.get(i) - closes.get(i - 1)),
                            Math.abs(lows.get(i) - closes.get(i - 1))
                    )
            );
            atr += tr;
        }

        return atr / period;
    }
}
