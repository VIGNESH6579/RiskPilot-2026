package com.riskpilot.dto;

public class MarketResponse {

    private double spotPrice;
    private double vix;

    public MarketResponse(double spotPrice, double vix) {
        this.spotPrice = spotPrice;
        this.vix = vix;
    }

    public double getSpotPrice() {
        return spotPrice;
    }

    public double getVix() {
        return vix;
    }
}