package com.riskpilot.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "candles", indexes = {
    @Index(name = "idx_candle_symbol_time", columnList = "symbol,timestamp"),
    @Index(name = "idx_candle_date", columnList = "date")
})
public record CandleEntity(
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id,
    
    @Column(nullable = false, length = 20)
    String symbol,
    
    @Column(nullable = false)
    LocalDate date,
    
    @Column(nullable = false)
    LocalDateTime timestamp,
    
    @Column(nullable = false, precision = 10, scale = 2)
    BigDecimal openPrice,
    
    @Column(nullable = false, precision = 10, scale = 2)
    BigDecimal highPrice,
    
    @Column(nullable = false, precision = 10, scale = 2)
    BigDecimal lowPrice,
    
    @Column(nullable = false, precision = 10, scale = 2)
    BigDecimal closePrice,
    
    @Column(nullable = false)
    Long volume,
    
    @Column(nullable = false, precision = 10, scale = 2)
    BigDecimal range, // high - low
    
    @Column(nullable = false)
    Integer timeframe, // in minutes (5 for 5-minute candles)
    
    @Column(nullable = false)
    Boolean isBullish, // close > open
    
    @Column(nullable = false)
    LocalDateTime createdAt
) {
    @PrePersist
    protected void onCreate() {
        if (range == null) range = highPrice.subtract(lowPrice);
        if (isBullish == null) isBullish = closePrice.compareTo(openPrice) > 0;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
    
    public BigDecimal getBodySize() {
        return closePrice.subtract(openPrice).abs();
    }
    
    public BigDecimal getUpperWick() {
        return isBullish ? highPrice.subtract(closePrice) : highPrice.subtract(openPrice);
    }
    
    public BigDecimal getLowerWick() {
        return isBullish ? openPrice.subtract(lowPrice) : closePrice.subtract(lowPrice);
    }
    
    public BigDecimal getMidPrice() {
        return highPrice.add(lowPrice).divide(BigDecimal.valueOf(2));
    }
}
