package com.riskpilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "candles", indexes = {
    @Index(name = "idx_candle_symbol_time", columnList = "symbol,timestamp"),
    @Index(name = "idx_candle_date", columnList = "date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal openPrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal highPrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal lowPrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal closePrice;

    @Column(nullable = false)
    private Long volume;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal range;

    @Column(nullable = false)
    private Integer timeframe;

    @Column(nullable = false)
    private Boolean isBullish;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (range == null && highPrice != null && lowPrice != null) {
            range = highPrice.subtract(lowPrice);
        }
        if (isBullish == null && closePrice != null && openPrice != null) {
            isBullish = closePrice.compareTo(openPrice) > 0;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public BigDecimal getBodySize() {
        return closePrice.subtract(openPrice).abs();
    }

    public BigDecimal getUpperWick() {
        return Boolean.TRUE.equals(isBullish) ? highPrice.subtract(closePrice) : highPrice.subtract(openPrice);
    }

    public BigDecimal getLowerWick() {
        return Boolean.TRUE.equals(isBullish) ? openPrice.subtract(lowPrice) : closePrice.subtract(lowPrice);
    }

    public BigDecimal getMidPrice() {
        return highPrice.add(lowPrice).divide(BigDecimal.valueOf(2));
    }

    public Candle toCandle() {
        return new Candle(
            date.toString(),
            timestamp.toLocalTime().toString(),
            openPrice.doubleValue(),
            highPrice.doubleValue(),
            lowPrice.doubleValue(),
            closePrice.doubleValue(),
            volume != null ? volume : 0L
        );
    }
}
