package com.riskpilot.config;

import org.springframework.stereotype.Component;

@Component
public class RiskConfig {

    // Total capital
    public static final double CAPITAL = 100000; // 1 lakh

    // Max risk per trade (%)
    public static final double RISK_PER_TRADE = 1.0; // 1%

    // Max daily loss (%)
    public static final double MAX_DAILY_LOSS = 3.0; // 3%

}