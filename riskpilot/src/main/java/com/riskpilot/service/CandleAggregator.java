package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.MarketDataException;
import com.riskpilot.event.CandleClosedEvent;
import com.riskpilot.model.Candle;
import com.riskpilot.model.MarketTick;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CandleAggregator {
    private static final DateTimeFormatter CANDLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ApplicationEventPublisher publisher;
    private final RiskPilotProperties properties;
    private final MarketSessionService marketSessionService;

    private final List<Candle> historicalBuffer = new ArrayList<>();
    private Candle currentBuildingCandle = null;
    private ZonedDateTime lastTickTime = ZonedDateTime.now();
    private java.time.Instant lastArrivalTime = null;
    
    private boolean feedUnstable = false;

    public CandleAggregator(
        ApplicationEventPublisher publisher,
        RiskPilotProperties properties,
        MarketSessionService marketSessionService
    ) {
        this.publisher = publisher;
        this.properties = properties;
        this.marketSessionService = marketSessionService;
    }

    public synchronized void processTick(MarketTick tick) {
        if (tick.exchangeTimestamp() == null) {
            throw new MarketDataException("LIVE_TICK_TIMESTAMP_MISSING");
        }
        if (!tick.afterHours() && tick.sourceAgeMs() > properties.getInfra().getFeed().getMaxSourceAgeMs()) {
            feedUnstable = true;
            throw new MarketDataException(String.format(
                "LIVE_TICK_STALE: age=%dms max=%dms",
                tick.sourceAgeMs(),
                properties.getInfra().getFeed().getMaxSourceAgeMs()
            ));
        }

        ZonedDateTime tickTime = marketSessionService.toMarketTime(tick.exchangeTimestamp());
        double price = tick.price();
        java.time.Instant now = tick.receivedAt();
        long arrivalGapMs = lastArrivalTime == null ? 0L : java.time.Duration.between(lastArrivalTime, now).toMillis();
        feedUnstable = (lastArrivalTime != null && arrivalGapMs > 4500L);
        lastTickTime = tickTime;
        lastArrivalTime = now;
        log.debug(
            "CandleAggregator input seq={} price={} exchangeTs={} receivedAt={} ageMs={}",
                tick.sequenceId(),
                price,
                tickTime.toLocalDateTime(),
                marketSessionService.toMarketTime(now).toLocalDateTime(),
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
                price, price, price, price, 1L
            );
        } else {
            LocalTime currentCandleTime = LocalTime.parse(currentBuildingCandle.time);
            if (candleAlignedTime.isAfter(currentCandleTime)) {
                // Candle has cleanly closed via time rollover properly natively tracking
                finalizeCandle(currentBuildingCandle);
                
                currentBuildingCandle = new Candle(
                    tickTime.toLocalDate().toString(),
                    candleTime,
                    price, price, price, price, 1L
                );
            } else {
                currentBuildingCandle.applyTick(price);
                currentBuildingCandle = new Candle(
                    currentBuildingCandle.date,
                    currentBuildingCandle.time,
                    currentBuildingCandle.open,
                    currentBuildingCandle.high,
                    currentBuildingCandle.low,
                    currentBuildingCandle.close,
                    currentBuildingCandle.tickCount() + 1
                );
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
        lastTickTime = candle.timestamp().atZone(marketSessionService.zoneId());
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
        lastTickTime = ZonedDateTime.now(marketSessionService.zoneId());
        lastArrivalTime = null;
        feedUnstable = false;
    }
}
