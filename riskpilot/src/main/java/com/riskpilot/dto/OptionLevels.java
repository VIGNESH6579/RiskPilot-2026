package com.riskpilot.dto;

public class OptionLevels {

    private double resistance; // highest CE OI
    private double support;    // highest PE OI
    private double pcr;

    public double getResistance() { return resistance; }
    public void setResistance(double resistance) { this.resistance = resistance; }

    public double getSupport() { return support; }
    public void setSupport(double support) { this.support = support; }

    public double getPcr() { return pcr; }
    public void setPcr(double pcr) { this.pcr = pcr; }
}