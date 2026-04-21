package com.riskpilot.model;

public record ActiveTrade(
    double entryPrice,
    double stopLoss,
    double tp1Level,
    boolean tp1Hit,
    boolean runnerActive,
    double positionSize,
    double remainingSize,
    double realizedPnL,
    double mfe,
    double mae,
    double trailingSL
) {}
