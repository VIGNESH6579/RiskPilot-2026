package com.riskpilot.util;

import java.util.List;

public class RsiCalculator {

    public static double calculateRSI(List<Double> prices, int period) {

        if (prices.size() < period + 1) {
            throw new IllegalArgumentException("Not enough data for RSI");
        }

        double gain = 0;
        double loss = 0;

        for (int i = prices.size() - period; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);

            if (change > 0) {
                gain += change;
            } else {
                loss += Math.abs(change);
            }
        }

        double avgGain = gain / period;
        double avgLoss = loss / period;

        if (avgLoss == 0) return 100;

        double rs = avgGain / avgLoss;

        return 100 - (100 / (1 + rs));
    }
}