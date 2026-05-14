package com.riskpilot.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Candle {
    public final String date;
    public final String time;
    public final double open;
    public volatile double high;
    public volatile double low;
    public volatile double close;
    public final long volume;

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
        if (price > high) high = price;
        if (price < low) low = price;
        close = price;
    }

    public LocalDateTime timestamp() {
        // BUG-FIX: LocalTime.toString() omits seconds when they are zero (e.g. "09:15" not "09:15:00").
        // Support both "HH:mm" and "HH:mm:ss" formats so candle timestamps always resolve correctly.
        try {
            String timeStr = time;
            if (timeStr != null && timeStr.length() == 5) {
                // "HH:mm" → pad to "HH:mm:ss"
                timeStr = timeStr + ":00";
            }
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(date + " " + timeStr, formatter);
        } catch (Exception e) {
            // Fallback to current time if parsing fails
            return LocalDateTime.now();
        }
    }

    public long volume() {
        return volume;
    }
}
