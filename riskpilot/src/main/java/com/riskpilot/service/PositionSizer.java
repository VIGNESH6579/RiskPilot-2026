package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import org.springframework.stereotype.Service;

@Service
public class PositionSizer {

    private final RiskPilotProperties properties;

    public PositionSizer(RiskPilotProperties properties) {
        this.properties = properties;
    }

    public int sizePositionLots(double stopDistancePoints, double equityInr, int consecutiveLosses) {
        double sanitizedStopDistance = Math.max(0.01, stopDistancePoints);
        double riskFraction = properties.getAccount().getRiskPerTradePct() / 100.0;
        if (consecutiveLosses >= properties.getAccount().getSizeReductionAfterLosses()) {
            riskFraction *= properties.getAccount().getReducedRiskFactor();
        }

        double riskBudgetInr = Math.max(0.0, equityInr * riskFraction);
        double lotRiskInr = sanitizedStopDistance
            * properties.getInstrument().getLotSize()
            * properties.getInstrument().getPointValue();
        int lots = (int) Math.floor(riskBudgetInr / lotRiskInr);
        return Math.max(1, lots);
    }
}
