package com.riskpilot.model;

public class OptionData {

    private double strike;
    private double callOi;
    private double putOi;

    public OptionData(double strike, double callOi, double putOi) {
        this.strike = strike;
        this.callOi = callOi;
        this.putOi = putOi;
    }

    public double getStrike() { return strike; }
    public double getCallOi() { return callOi; }
    public double getPutOi() { return putOi; }
}