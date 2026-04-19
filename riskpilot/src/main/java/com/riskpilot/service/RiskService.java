package com.riskpilot.service;

import com.riskpilot.config.RiskConfig;
import com.riskpilot.model.Trade;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RiskService {

    public int calculateQuantity(double entry, double stopLoss, double vix) {

        double riskPercent = getDynamicRiskPercent(vix);

        double riskAmount = (RiskConfig.CAPITAL * riskPercent) / 100;

        double riskPerUnit = Math.abs(entry - stopLoss);

        if (riskPerUnit == 0) {
            throw new RuntimeException("Invalid SL");
        }

        int qty = (int) (riskAmount / riskPerUnit);

        if (qty <= 0) {
            throw new RuntimeException("Calculated quantity is zero. Trade not allowed.");
        }

        return qty;
    }

    public boolean isTradingBlocked(double vix) {
        return getDynamicRiskPercent(vix) == 0.0;
    }

    public boolean isMaxLossReached(double todayLoss) {
        double maxLoss = (RiskConfig.CAPITAL * RiskConfig.MAX_DAILY_LOSS) / 100;
        return todayLoss <= -maxLoss;
    }

    public double calculateTodayLoss(List<Trade> trades) {
        return trades.stream()
                .filter(t -> "CLOSED".equalsIgnoreCase(t.getStatus()))
                .mapToDouble(t -> {
                    if (t.getPnl() == null) return 0;
                    return Math.min(t.getPnl(), 0);
                })
                .sum();
    }

    public double getDynamicRiskPercent(double vix) {

        if (vix < 12) return 1.5;
        else if (vix <= 18) return 1.0;
        else if (vix <= 25) return 0.5;
        else return 0.0;
    }
}