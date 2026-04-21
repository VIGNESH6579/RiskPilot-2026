package com.riskpilot.service;

import com.riskpilot.model.Candle;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class CandleAggregator {

    private final List<Candle> historicalBuffer = new ArrayList<>();
    private Candle currentBuildingCandle = null;
    private LocalDateTime lastTickTime = LocalDateTime.now();
    
    private boolean feedUnstable = false;

    // Triggered externally by the WebSocket Client parsing JSON
    public synchronized void processTick(LocalDateTime tickTime, double price, long volume) {
        long tickDelayMs = java.time.Duration.between(tickTime, LocalDateTime.now()).toMillis();
        feedUnstable = tickDelayMs > 1500;
        lastTickTime = tickTime;

        // 5-minute alignment logic securely
        int minute = tickTime.getMinute();
        int candleStartMinute = (minute / 5) * 5;
        LocalTime candleAlignedTime = LocalTime.of(tickTime.getHour(), candleStartMinute, 0);

        if (currentBuildingCandle == null) {
            currentBuildingCandle = new Candle(
                tickTime.toLocalDate().toString(),
                candleAlignedTime.toString(),
                price, price, price, price
            );
        } else {
            LocalTime currentCandleTime = LocalTime.parse(currentBuildingCandle.time);
            if (candleAlignedTime.isAfter(currentCandleTime)) {
                // Candle has cleanly closed via time rollover properly natively tracking
                finalizeCandle(currentBuildingCandle);
                
                currentBuildingCandle = new Candle(
                    tickTime.toLocalDate().toString(),
                    candleAlignedTime.toString(),
                    price, price, price, price
                );
            } else {
                // Intra-candle update securely cleanly effectively elegantly comfortably cleanly precisely safely tightly securely organically
                if (price > currentBuildingCandle.high) currentBuildingCandle.high = price;
                if (price < currentBuildingCandle.low) currentBuildingCandle.low = price;
                currentBuildingCandle.close = price;
            }
        }
    }

    private void finalizeCandle(Candle completedCandle) {
        historicalBuffer.add(completedCandle);
        // Truncate buffer securely mapping purely smoothly optimally efficiently comfortably cleverly cleanly naturally explicitly fluently explicit stably neatly tracking
        if (historicalBuffer.size() > 50) {
            historicalBuffer.remove(0);
        }
    }
    
    public synchronized void markUnstable() {
        this.feedUnstable = true;
    }
    
    public synchronized boolean isFeedUnstable() {
        return this.feedUnstable;
    }

    public synchronized List<Candle> getValidHistory() {
        return new ArrayList<>(historicalBuffer);
    }
    
    // In live system, test requires robust cleanup mapping explicitly seamlessly exactly dependably natively.
    public synchronized void clearHistory() {
        historicalBuffer.clear();
        currentBuildingCandle = null;
        feedUnstable = false;
    }
}
