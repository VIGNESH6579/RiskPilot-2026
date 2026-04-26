package com.riskpilot.model;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Candle {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FALLBACK_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        try {
            return LocalDateTime.of(LocalDate.parse(date, DATE_FORMATTER), LocalTime.parse(time));
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(date + " " + time, FALLBACK_FORMATTER);
            } catch (Exception ignored) {
                return LocalDateTime.now();
            }
        }
    }

    public long volume() {
        return volume;
    }
}
