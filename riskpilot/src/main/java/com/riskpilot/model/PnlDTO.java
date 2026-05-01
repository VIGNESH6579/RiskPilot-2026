package com.riskpilot.model;

public record PnlDTO(
    double realizedPnl,
    double unrealizedPnl,
    double totalPnl,
    int totalTrades,
    long rejectedTrades,
    String displayNote
) {}
