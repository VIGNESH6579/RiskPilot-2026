package com.riskpilot.model;

public class Candle {
    public final String date;
    public final String time;
    public final double open;
    public double high;
    public double low;
    public double close;

    public Candle(String date, String time, double open, double high, double low, double close) {
        this.date = date;
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }
}
