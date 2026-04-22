package com.riskpilot.model;

public class Candle {
    public final String date;
    public final String time;
    public final double open;
    public volatile double high;
    public volatile double low;
    public volatile double close;

    public Candle(String date, String time, double open, double high, double low, double close) {
        this.date = date;
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }

    public Candle copy() {
        return new Candle(date, time, open, high, low, close);
    }

    public synchronized void applyTick(double price) {
        if (price > high) high = price;
        if (price < low) low = price;
        close = price;
    }
}
