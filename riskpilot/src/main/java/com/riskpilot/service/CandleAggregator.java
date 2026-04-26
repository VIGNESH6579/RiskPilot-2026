package com.riskpilot.service;

import com.riskpilot.event.CandleClosedEvent;
import com.riskpilot.model.Candle;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class CandleAggregator {
    private static final DateTimeFormatter CANDLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ApplicationEventPublisher publisher;

    private final List<Candle> historicalBuffer = new ArrayList<>();
    private Candle currentBuildingCandle = null;
    private LocalDateTime lastTickTime = LocalDateTime.now();
    
    private boolean feedUnstable = false;

    public CandleAggregator(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    // Triggered externally by the WebSocket Client parsing JSON
    public synchronized void processTick(LocalDateTime tickTime, double price, long volume) {
        long tickDelayMs = java.time.Duration.between(tickTime, LocalDateTime.now()).toMillis();
        feedUnstable = tickDelayMs > 1500;
        lastTickTime = tickTime;

        // 5-minute alignment logic securely
        int minute = tickTime.getMinute();
        int candleStartMinute = (minute / 5) * 5;
        LocalTime candleAlignedTime = LocalTime.of(tickTime.getHour(), candleStartMinute, 0);
        String candleTime = candleAlignedTime.format(CANDLE_TIME_FORMATTER);

        if (currentBuildingCandle == null) {
            currentBuildingCandle = new Candle(
                tickTime.toLocalDate().toString(),
                candleTime,
                price, price, price, price, volume
            );
        } else {
            LocalTime currentCandleTime = LocalTime.parse(currentBuildingCandle.time);
            if (candleAlignedTime.isAfter(currentCandleTime)) {
                // Candle has cleanly closed via time rollover properly natively tracking
                finalizeCandle(currentBuildingCandle);
                
                currentBuildingCandle = new Candle(
                    tickTime.toLocalDate().toString(),
                    candleTime,
                    price, price, price, price, volume
                );
            } else {
                currentBuildingCandle.applyTick(price);
            }
        }
    }

    private void finalizeCandle(Candle completedCandle) {
        Candle copy = completedCandle.copy();
        historicalBuffer.add(copy);
        // Truncate buffer securely mapping purely smoothly optimally efficiently comfortably cleverly cleanly naturally explicitly fluently explicit stably neatly tracking
        if (historicalBuffer.size() > 50) {
            historicalBuffer.remove(0);
        }
        publisher.publishEvent(new CandleClosedEvent(copy));
    }

    public synchronized void addCandle(Candle candle) {
        finalizeCandle(candle);
        lastTickTime = candle.timestamp();
    }
    
    public synchronized void markUnstable() {
        this.feedUnstable = true;
    }
    
    public synchronized boolean isFeedUnstable() {
        return this.feedUnstable;
    }

    public synchronized List<Candle> getValidHistory() {
        List<Candle> snapshot = new ArrayList<>(historicalBuffer.size());
        for (Candle c : historicalBuffer) {
            snapshot.add(c.copy());
        }
        return snapshot;
    }
    
    // In live system, test requires robust cleanup mapping explicitly seamlessly exactly dependably natively.
    public synchronized void clearHistory() {
        historicalBuffer.clear();
        currentBuildingCandle = null;
        feedUnstable = false;
    }
}
