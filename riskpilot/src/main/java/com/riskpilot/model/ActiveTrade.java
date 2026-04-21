package com.riskpilot.model;

public record ActiveTrade(
    double entryPrice,
    double stopLoss,
    double tp1Level,
    double initialRisk,
    boolean tp1Hit,
    boolean runnerActive,
    boolean stage2Active,
    boolean tailHalfLocked,
    double positionSize,
    double remainingSize,
    double realizedPnL,
    double mfe,
    double mae,
    double peakFavorableR,
    double trailingSL
) {}
