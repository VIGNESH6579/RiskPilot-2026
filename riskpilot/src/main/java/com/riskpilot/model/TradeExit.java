package com.riskpilot.model;

public record TradeExit(
    boolean triggered,
    double pnlInr,
    String reason,
    double exitPrice,
    String exitType
) {
    public static TradeExit noExit() {
        return new TradeExit(false, 0.0, "NONE", Double.NaN, "NONE");
    }
}
