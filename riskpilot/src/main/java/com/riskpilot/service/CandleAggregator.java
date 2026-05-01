package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.MarketDataException;
import com.riskpilot.event.CandleClosedEvent;
import com.riskpilot.model.Candle;
import com.riskpilot.model.CandleRecord;
import com.riskpilot.model.MarketTick;
import com.riskpilot.repository.CandleRecordRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CandleAggregator {
    private static final DateTimeFormatter CANDLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long FEED_STABLE_RECOVERY_MS = 30_000L;
    private static final long FEED_INSTABILITY_GAP_MS = 4500L;

    private final ApplicationEventPublisher publisher;
    private final RiskPilotProperties properties;
    private final MarketSessionService marketSessionService;
    private final CandleRecordRepository candleRecordRepository;

    private final List<Candle> sessionBuffer = new ArrayList<>();
    private final List<MarketTick> afterHoursBuffer = new ArrayList<>();
    private Candle currentBuildingCandle = null;
    private LocalDateTime currentCandleStart = null;
    private long lastAcceptedSequenceId = Long.MIN_VALUE;
    private long outOfOrderTickCount = 0L;
    private ZonedDateTime lastTickTime = ZonedDateTime.now();
    private Instant lastArrivalTime = null;
    private Instant stableSinceTime = null;
    private boolean feedUnstable = false;
    private Candle pendingPublishCandle = null;

    public CandleAggregator(
        ApplicationEventPublisher publisher,
        RiskPilotProperties properties,
        MarketSessionService marketSessionService,
        CandleRecordRepository candleRecordRepository
    ) {
        this.publisher = publisher;
        this.properties = properties;
        this.marketSessionService = marketSessionService;
        this.candleRecordRepository = candleRecordRepository;
    }

    @PostConstruct
    public void restoreSessionCandles() {
        LocalDate sessionDate = marketSessionService.nowIst().toLocalDate();
        List<CandleRecord> persisted = candleRecordRepository.findTop50BySymbolAndTradeDateOrderByCandleTimeAsc(
            properties.getInstrument().getSymbol(),
            sessionDate
        );
        sessionBuffer.clear();
        persisted.stream()
            .map(CandleRecord::toCandle)
            .forEach(sessionBuffer::add);
    }

    public void processTick(MarketTick tick) {
        Candle candleToPublish;
        synchronized (this) {
            pendingPublishCandle = null;
            if (tick.exchangeTimestamp() == null) {
                throw new MarketDataException("LIVE_TICK_TIMESTAMP_MISSING");
            }
            if (tick.afterHours()) {
                trackAfterHoursTick(tick);
                return;
            }
            if (!tick.afterHours() && tick.sourceAgeMs() > properties.getInfra().getFeed().getMaxSourceAgeMs()) {
                feedUnstable = true;
                stableSinceTime = null;
                throw new MarketDataException("LIVE_TICK_STALE");
            }

            ZonedDateTime tickTime = marketSessionService.toMarketTime(tick.exchangeTimestamp());
            double price = tick.price();
            Instant now = tick.receivedAt();
            long arrivalGapMs = lastArrivalTime == null ? 0L : Duration.between(lastArrivalTime, now).toMillis();
            if (lastArrivalTime != null && arrivalGapMs > FEED_INSTABILITY_GAP_MS) {
                feedUnstable = true;
                stableSinceTime = null;
            } else {
                if (stableSinceTime == null) {
                    stableSinceTime = now;
                } else if (feedUnstable && Duration.between(stableSinceTime, now).toMillis() >= FEED_STABLE_RECOVERY_MS) {
                    feedUnstable = false;
                }
            }
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

            if (lastAcceptedSequenceId != Long.MIN_VALUE
                && tick.sequenceId() > 0L
                && tick.sequenceId() < lastAcceptedSequenceId) {
                long drop = lastAcceptedSequenceId - tick.sequenceId();
                if (drop > 10000L) {
                    log.warn(
                        "SEQUENCE_RESET_DETECTED old={} new={} accepting as reconnect",
                        lastAcceptedSequenceId,
                        tick.sequenceId()
                    );
                    lastAcceptedSequenceId = tick.sequenceId();
                } else {
                    outOfOrderTickCount++;
                    log.warn(
                        "DROPPED_OUT_OF_ORDER_TICK seq={} lastSeq={} price={} exchangeTs={}",
                        tick.sequenceId(), lastAcceptedSequenceId, price, tickTime.toLocalDateTime()
                    );
                    return;
                }
            }
            if (tick.sequenceId() > 0L) {
                lastAcceptedSequenceId = tick.sequenceId();
            }

            int minute = tickTime.getMinute();
            int candleStartMinute = (minute / 5) * 5;
            LocalDateTime candleStart = LocalDateTime.of(
                tickTime.toLocalDate(),
                LocalTime.of(tickTime.getHour(), candleStartMinute, 0)
            );
            String candleTime = candleStart.toLocalTime().format(CANDLE_TIME_FORMATTER);

            if (currentBuildingCandle == null) {
                currentBuildingCandle = new Candle(
                    tickTime.toLocalDate().toString(),
                    candleTime,
                    price, price, price, price, 1L
                );
                currentCandleStart = candleStart;
            } else if (candleStart.isAfter(currentCandleStart)) {
                pendingPublishCandle = finalizeCandle(currentBuildingCandle);
                currentBuildingCandle = new Candle(
                    tickTime.toLocalDate().toString(),
                    candleTime,
                    price, price, price, price, 1L
                );
                currentCandleStart = candleStart;
            } else if (candleStart.isEqual(currentCandleStart)) {
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
            } else {
                outOfOrderTickCount++;
                log.warn(
                    "DROPPED_LATE_TICK seq={} candleStart={} currentCandleStart={} price={}",
                    tick.sequenceId(), candleStart, currentCandleStart, price
                );
            }
            candleToPublish = pendingPublishCandle;
            pendingPublishCandle = null;
        }

        if (candleToPublish != null) {
            publisher.publishEvent(new CandleClosedEvent(candleToPublish));
        }
    }

    public synchronized long getOutOfOrderTickCount() {
        return outOfOrderTickCount;
    }

    private Candle finalizeCandle(Candle completedCandle) {
        Candle copy = completedCandle.copy();
        sessionBuffer.add(copy);
        if (sessionBuffer.size() > 50) {
            sessionBuffer.remove(0);
        }
        candleRecordRepository.save(CandleRecord.fromCandle(properties.getInstrument().getSymbol(), copy));
        return copy;
    }

    public void addCandle(Candle candle) {
        Candle candleToPublish;
        synchronized (this) {
            pendingPublishCandle = finalizeCandle(candle);
            candleToPublish = pendingPublishCandle;
            pendingPublishCandle = null;
            lastTickTime = candle.timestamp().atZone(marketSessionService.zoneId());
            currentCandleStart = candle.timestamp();
        }

        if (candleToPublish != null) {
            publisher.publishEvent(new CandleClosedEvent(candleToPublish));
        }
    }

    public synchronized void markUnstable() {
        this.feedUnstable = true;
        this.stableSinceTime = null;
    }

    public synchronized boolean isFeedUnstable() {
        return this.feedUnstable;
    }

    public synchronized List<Candle> getValidHistory() {
        List<Candle> snapshot = new ArrayList<>(sessionBuffer.size());
        for (Candle c : sessionBuffer) {
            snapshot.add(c.copy());
        }
        return snapshot;
    }

    public synchronized List<MarketTick> getAfterHoursTicks() {
        return new ArrayList<>(afterHoursBuffer);
    }

    public synchronized void clearHistory() {
        sessionBuffer.clear();
        afterHoursBuffer.clear();
        currentBuildingCandle = null;
        currentCandleStart = null;
        lastAcceptedSequenceId = Long.MIN_VALUE;
        outOfOrderTickCount = 0L;
        lastTickTime = ZonedDateTime.now(marketSessionService.zoneId());
        lastArrivalTime = null;
        stableSinceTime = null;
    }

    private void trackAfterHoursTick(MarketTick tick) {
        afterHoursBuffer.add(tick);
        if (afterHoursBuffer.size() > 250) {
            afterHoursBuffer.remove(0);
        }
        log.debug(
            "Ignoring after-hours tick for trading seq={} price={} exchangeTs={} receivedAt={}",
            tick.sequenceId(),
            tick.price(),
            marketSessionService.toMarketTime(tick.exchangeTimestamp()),
            marketSessionService.toMarketTime(tick.receivedAt())
        );
    }
}
