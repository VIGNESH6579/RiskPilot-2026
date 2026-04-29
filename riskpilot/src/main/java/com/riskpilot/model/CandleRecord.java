package com.riskpilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "candles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandleRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private LocalDateTime candleTime;

    @Column(nullable = false)
    private Double openPrice;

    @Column(nullable = false)
    private Double highPrice;

    @Column(nullable = false)
    private Double lowPrice;

    @Column(nullable = false)
    private Double closePrice;

    @Column(nullable = false)
    private Long tickCount;

    @Column(nullable = false)
    private Double priceRange;

    @Column(nullable = false)
    private Integer timeframe;

    @Column(nullable = false)
    private Boolean bullish;

    public static CandleRecord fromCandle(String symbol, Candle candle) {
        return CandleRecord.builder()
            .symbol(symbol)
            .tradeDate(candle.timestamp().toLocalDate())
            .candleTime(candle.timestamp())
            .openPrice(candle.open)
            .highPrice(candle.high)
            .lowPrice(candle.low)
            .closePrice(candle.close)
            .tickCount(candle.tickCount())
            .priceRange(candle.high - candle.low)
            .timeframe(5)
            .bullish(candle.close >= candle.open)
            .build();
    }

    public Candle toCandle() {
        return new Candle(
            candleTime.toLocalDate().toString(),
            candleTime.toLocalTime().withSecond(0).withNano(0).toString(),
            openPrice,
            highPrice,
            lowPrice,
            closePrice,
            tickCount
        );
    }
}
