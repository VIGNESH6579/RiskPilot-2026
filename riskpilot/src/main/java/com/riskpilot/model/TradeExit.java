package com.riskpilot.model;

public record TradeExit(
    boolean triggered,
    double pnlPoints,
    String reason,
    double exitPrice
) {
    public TradeExit(boolean triggered, double pnlPoints, String reason) {
        this(triggered, pnlPoints, reason, Double.NaN);
    }

    public static TradeExit noExit() {
        return new TradeExit(false, 0.0, "NONE", Double.NaN);
    }

    public double pnl() {
        return pnlPoints;
    }

    public String exitReason() {
        return reason;
    }
}
