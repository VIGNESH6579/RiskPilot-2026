package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.MarketDataException;
import com.riskpilot.event.CandleClosedEvent;
import com.riskpilot.model.Candle;
import com.riskpilot.model.MarketTick;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CandleAggregator {
    private static final DateTimeFormatter CANDLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ApplicationEventPublisher publisher;
    private final RiskPilotProperties properties;

    private final List<Candle> historicalBuffer = new ArrayList<>();
    private Candle currentBuildingCandle = null;
    private LocalDateTime lastTickTime = LocalDateTime.now();
    private LocalDateTime lastArrivalTime = null;
    
    private boolean feedUnstable = false;

    public CandleAggregator(ApplicationEventPublisher publisher, RiskPilotProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    public synchronized void processTick(MarketTick tick) {
        if (tick.exchangeTimestamp() == null) {
            throw new MarketDataException("LIVE_TICK_TIMESTAMP_MISSING");
        }
        if (tick.sourceAgeMs() > properties.getInfra().getFeed().getMaxSourceAgeMs()) {
            feedUnstable = true;
            throw new MarketDataException(String.format(
                "LIVE_TICK_STALE: age=%dms max=%dms",
                tick.sourceAgeMs(),
                properties.getInfra().getFeed().getMaxSourceAgeMs()
            ));
        }

        LocalDateTime tickTime = tick.exchangeTimestamp();
        double price = tick.price();
        long volume = 1L;
        LocalDateTime now = tick.receivedAt();
        long arrivalGapMs = lastArrivalTime == null ? 0L : java.time.Duration.between(lastArrivalTime, now).toMillis();
        feedUnstable = (lastArrivalTime != null && arrivalGapMs > 4500L);
        lastTickTime = tickTime;
        lastArrivalTime = now;
        log.debug(
            "CandleAggregator input seq={} price={} exchangeTs={} receivedAt={} ageMs={}",
            tick.sequenceId(),
            price,
            tickTime,
            now,
            tick.sourceAgeMs()
        );

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
        lastTickTime = LocalDateTime.now();
        lastArrivalTime = null;
        feedUnstable = false;
    }
}
