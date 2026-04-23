package com.riskpilot.service;

import com.riskpilot.model.CandleEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealTimeTickAggregator {

    private final RealTimeMarketDataService marketDataService;
    
    // Real-time candle aggregation
    private final Map<String, CandleBuilder> candleBuilders = new ConcurrentHashMap<>();
    private final List<CandleEntity> candleHistory = new ArrayList<>();
    private final AtomicReference<Instant> lastCandleTime = new AtomicReference<>(Instant.now());
    
    // Feed stability monitoring
    private final AtomicReference<Instant> lastTickTime = new AtomicReference<>(Instant.now());
    private volatile boolean feedStable = true;
    private static final long FEED_STABILITY_THRESHOLD_SECONDS = 30; // 30 seconds without ticks = unstable
    
    private static class CandleBuilder {
        double open = 0.0;
        double high = 0.0;
        double low = Double.MAX_VALUE;
        double close = 0.0;
        long volume = 0;
        Instant startTime;
        String symbol;
        
        CandleBuilder(String symbol, double firstPrice, Instant startTime) {
            this.symbol = symbol;
            this.open = firstPrice;
            this.high = firstPrice;
            this.low = firstPrice;
            this.close = firstPrice;
            this.volume = 0;
            this.startTime = startTime;
        }
        
        void update(double price, long vol) {
            this.close = price;
            this.high = Math.max(this.high, price);
            this.low = Math.min(this.low, price);
            this.volume += vol;
        }
        
        CandleEntity build() {
            return new CandleEntity(
                symbol,
                open, high, low, close, volume,
                LocalDateTime.ofInstant(startTime, ZoneId.of("Asia/Kolkata")),
                LocalDateTime.ofInstant(Instant.now(), ZoneId.of("Asia/Kolkata"))
            );
        }
    }

    /**
     * Process real-time tick and update candles
     * NO SYNTHETIC DATA - ONLY REAL MARKET TICKS
     */
    public void processRealTimeTick(String symbol, double price, long volume) {
        Instant now = Instant.now();
        lastTickTime.set(now);
        
        // Update feed stability
        updateFeedStability(now);
        
        // Get or create candle builder for this symbol
        CandleBuilder builder = candleBuilders.computeIfAbsent(symbol, 
            s -> new CandleBuilder(s, price, now));
        
        // Check if we need to start a new candle (5-minute intervals)
        if (shouldStartNewCandle(builder.startTime, now)) {
            // Complete previous candle
            CandleEntity completedCandle = builder.build();
            addCandleToHistory(completedCandle);
            
            // Start new candle
            candleBuilders.put(symbol, new CandleBuilder(symbol, price, now));
        } else {
            // Update current candle
            builder.update(price, volume);
        }
        
        log.debug("📈 Real-time tick processed: {} @ {}, Volume: {}", symbol, price, volume);
    }

    /**
     * Process tick from Angel One stream
     */
    public void processAngelTick(String symbol, double ltp, long volume) {
        processRealTimeTick(symbol, ltp, volume);
    }

    /**
     * Get latest candle for symbol
     */
    public Optional<CandleEntity> getLatestCandle(String symbol) {
        return candleHistory.stream()
                .filter(candle -> candle.getSymbol().equals(symbol))
                .max(Comparator.comparing(CandleEntity::getTimestamp));
    }

    /**
     * Get candle history for analysis
     */
    public List<CandleEntity> getCandleHistory(int maxCount) {
        synchronized (candleHistory) {
            int size = candleHistory.size();
            if (size == 0) return new ArrayList<>();
            
            int fromIndex = Math.max(0, size - maxCount);
            return new ArrayList<>(candleHistory.subList(fromIndex, size));
        }
    }

    /**
     * Check if feed is stable based on real-time ticks
     */
    public boolean isFeedStable() {
        updateFeedStability(Instant.now());
        return feedStable;
    }

    private void updateFeedStability(Instant now) {
        long secondsSinceLastTick = java.time.Duration.between(lastTickTime.get(), now).getSeconds();
        boolean wasStable = feedStable;
        feedStable = secondsSinceLastTick <= FEED_STABILITY_THRESHOLD_SECONDS;
        
        if (wasStable && !feedStable) {
            log.warn("🚨 FEED INSTABILITY DETECTED: {} seconds since last tick", secondsSinceLastTick);
        } else if (!wasStable && feedStable) {
            log.info("✅ Feed stability restored");
        }
    }

    private boolean shouldStartNewCandle(Instant candleStart, Instant now) {
        // 5-minute candles
        long candleMinute = candleStart.atZone(ZoneId.of("Asia/Kolkata")).getMinute();
        long currentMinute = now.atZone(ZoneId.of("Asia/Kolkata")).getMinute();
        
        return (currentMinute / 5) > (candleMinute / 5) || 
               java.time.Duration.between(candleStart, now).toMinutes() >= 5;
    }

    private void addCandleToHistory(CandleEntity candle) {
        synchronized (candleHistory) {
            candleHistory.add(candle);
            lastCandleTime.set(candle.getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toInstant());
            
            // Keep only last 1000 candles to prevent memory issues
            if (candleHistory.size() > 1000) {
                candleHistory.remove(0);
            }
        }
        
        log.info("🕯 5-minute candle completed: {} OHLC=[{}/{}/{}/{}] Vol={}", 
                candle.getSymbol(), candle.getOpenPrice(), candle.getHighPrice(), 
                candle.getLowPrice(), candle.getClosePrice(), candle.getVolume());
    }

    /**
     * Force refresh with real-time data
     */
    public void forceRefresh() {
        log.info("🔄 Forcing real-time data refresh");
        
        // Refresh NIFTY
        marketDataService.getNiftyLtp().ifPresent(ltp -> {
            processRealTimeTick("NIFTY", ltp, 1000); // Default volume
        });
        
        // Refresh BankNIFTY
        marketDataService.getBankNiftyLtp().ifPresent(ltp -> {
            processRealTimeTick("BANKNIFTY", ltp, 1000); // Default volume
        });
    }

    /**
     * Get aggregation statistics
     */
    public Map<String, Object> getStats() {
        return Map.of(
            "totalCandles", candleHistory.size(),
            "activeBuilders", candleBuilders.size(),
            "feedStable", feedStable,
            "lastTickTime", lastTickTime.get().toString(),
            "lastCandleTime", lastCandleTime.get().toString()
        );
    }

    /**
     * Clear all data (for reset)
     */
    public void clear() {
        candleBuilders.clear();
        synchronized (candleHistory) {
            candleHistory.clear();
        }
        lastTickTime.set(Instant.now());
        lastCandleTime.set(Instant.now());
        feedStable = true;
        log.info("🧹 Real-time aggregator cleared");
    }
}
