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
    // Window of continuous stable ticks required before clearing a previously
    // raised feedUnstable flag. Prevents a single fast tick from masking a
    // real instability event.
    private static final long FEED_STABLE_RECOVERY_MS = 30_000L;
    private static final long FEED_INSTABILITY_GAP_MS = 4500L;

    private final ApplicationEventPublisher publisher;
    private final RiskPilotProperties properties;
    private final MarketSessionService marketSessionService;
    private final CandleRecordRepository candleRecordRepository;

    private final List<Candle> sessionBuffer = new ArrayList<>();
    private final List<MarketTick> afterHoursBuffer = new ArrayList<>();
    private Candle currentBuildingCandle = null;
    // FIX: full LocalDateTime so day-rollover compares correctly. LocalTime
    // alone would mis-order Friday 15:25 vs Monday 09:15 if the JVM survives
    // the weekend, silently mutating Friday's last candle.
    private LocalDateTime currentCandleStart = null;
    private long lastAcceptedSequenceId = Long.MIN_VALUE;
    private long outOfOrderTickCount = 0L;
    private ZonedDateTime lastTickTime = ZonedDateTime.now();
    private Instant lastArrivalTime = null;
    private Instant stableSinceTime = null;

    private boolean feedUnstable = false;

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

    public synchronized void processTick(MarketTick tick) {
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
        java.time.Instant now = tick.receivedAt();
        long arrivalGapMs = lastArrivalTime == null ? 0L : Duration.between(lastArrivalTime, now).toMillis();
        // FIX: only RAISE feedUnstable here. Do not clear it on a single fast
        // tick — that would silently mask a real instability event triggered
        // earlier (by AngelTickStreamClient on parse failure or HeartbeatMonitor
        // on silence). Clearing requires FEED_STABLE_RECOVERY_MS of continuous
        // stable ticks.
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

        // FIX: drop strictly out-of-sequence ticks. Without this an Angel
        // reconnect can replay duplicate / older ticks that mutate already-
        // closed candles or skew inter-arrival gap measurements.
        if (lastAcceptedSequenceId != Long.MIN_VALUE
            && tick.sequenceId() > 0L
            && tick.sequenceId() < lastAcceptedSequenceId) {
            outOfOrderTickCount++;
            log.warn(
                "DROPPED_OUT_OF_ORDER_TICK seq={} lastSeq={} price={} exchangeTs={}",
                tick.sequenceId(), lastAcceptedSequenceId, price, tickTime.toLocalDateTime()
            );
            return;
        }
        if (tick.sequenceId() > 0L) {
            lastAcceptedSequenceId = tick.sequenceId();
        }

        // 5-minute alignment logic — use full LocalDateTime so day-rollover
        // (e.g. JVM that survives the weekend, first Monday tick after
        // Friday 15:25) cannot mis-order against the prior candle.
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
            // Clean rollover into a new 5-min bucket.
            finalizeCandle(currentBuildingCandle);
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
            // FIX: tick belongs to a candle that should already be closed.
            // Refuse to mutate the past — drop and log instead of silently
            // corrupting OHLC data the strategy reads.
            outOfOrderTickCount++;
            log.warn(
                "DROPPED_LATE_TICK seq={} candleStart={} currentCandleStart={} price={}",
                tick.sequenceId(), candleStart, currentCandleStart, price
            );
        }
    }

    public synchronized long getOutOfOrderTickCount() {
        return outOfOrderTickCount;
    }

    private void finalizeCandle(Candle completedCandle) {
        Candle copy = completedCandle.copy();
        sessionBuffer.add(copy);
        // Truncate buffer securely mapping purely smoothly optimally efficiently comfortably cleverly cleanly naturally explicitly fluently explicit stably neatly tracking
        if (sessionBuffer.size() > 50) {
            sessionBuffer.remove(0);
        }
        candleRecordRepository.save(CandleRecord.fromCandle(properties.getInstrument().getSymbol(), copy));
        publisher.publishEvent(new CandleClosedEvent(copy));
    }

    public synchronized void addCandle(Candle candle) {
        finalizeCandle(candle);
        lastTickTime = candle.timestamp().atZone(marketSessionService.zoneId());
        // Keep currentCandleStart aligned with the externally-injected candle
        // so subsequent ticks compare against the right boundary.
        currentCandleStart = candle.timestamp();
    }
    
    public synchronized void markUnstable() {
        this.feedUnstable = true;
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
    
    // In live system, test requires robust cleanup mapping explicitly seamlessly exactly dependably natively.
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
        feedUnstable = false;
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
