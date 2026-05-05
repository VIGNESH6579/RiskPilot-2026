package com.riskpilot.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Candle {
    public final String date;
    public final String time;
    public final double open;
    public volatile double high;
    public volatile double low;
    public volatile double close;
    private long volume;

    public Candle(String date, String time, double open, double high, double low, double close) {
        this(date, time, open, high, low, close, 0);
    }

    public Candle(String date, String time, double open, double high, double low, double close, long volume) {
        this.date = date;
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public Candle copy() {
        return new Candle(date, time, open, high, low, close, volume);
    }

    public synchronized void applyTick(double price) {
        applyTick(price, 0L);
    }

    public synchronized void applyTick(double price, long tickVolume) {
        if (price > high) high = price;
        if (price < low) low = price;
        close = price;
        volume += Math.max(0L, tickVolume);
    }

    public LocalDateTime timestamp() {
        try {
            return LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time));
        } catch (Exception ignored) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(date + " " + time, formatter);
        }
    }

    public long volume() {
        return volume;
    }
}
