package com.riskpilot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "trading_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingSession {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private LocalDate sessionDate;
    
    @Column(nullable = false, length = 20)
    private String symbol;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyOpen;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal orHigh; // Opening Range High
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal orLow; // Opening Range Low
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal orExpansion; // Opening Range Expansion
    
    @Column(nullable = false)
    private Boolean fastCandleExists;
    
    @Column(nullable = false, length = 20)
    private String regime; // TREND, RANGE, DEAD
    
    @Column(nullable = false)
    private Boolean regimeLocked;
    
    @Column(nullable = false)
    private Integer tradesGenerated;
    
    @Column(nullable = false)
    private Integer tradesExecuted;
    
    @Column(nullable = false)
    private Integer tradesRejected;
    
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPnL;
    
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal maxDrawdown;
    
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal maxProfit;
    
    @Column(nullable = false)
    private Boolean sessionActive;
    
    @Column(nullable = false)
    private Boolean dayBlockedByFirstTradeFailure;
    
    @Column(nullable = false, length = 20)
    private String status; // ACTIVE, CLOSED, HALTED
    
    @Column(nullable = true, length = 500)
    private String notes;
    
    @Column(nullable = false)
    private LocalDateTime sessionStart;
    
    @Column(nullable = true)
    private LocalDateTime sessionEnd;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (sessionActive == null) sessionActive = true;
        if (status == null) status = "ACTIVE";
        if (tradesGenerated == null) tradesGenerated = 0;
        if (tradesExecuted == null) tradesExecuted = 0;
        if (tradesRejected == null) tradesRejected = 0;
        if (totalPnL == null) totalPnL = BigDecimal.ZERO;
        if (maxDrawdown == null) maxDrawdown = BigDecimal.ZERO;
        if (maxProfit == null) maxProfit = BigDecimal.ZERO;
        if (regimeLocked == null) regimeLocked = false;
        if (dayBlockedByFirstTradeFailure == null) dayBlockedByFirstTradeFailure = false;
        if (fastCandleExists == null) fastCandleExists = false;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public boolean isActive() {
        return sessionActive && "ACTIVE".equals(status);
    }
    
    public BigDecimal getOrRange() {
        return orHigh.subtract(orLow);
    }
    
    public void incrementTradesGenerated() {
        tradesGenerated++;
    }
    
    public void incrementTradesExecuted() {
        tradesExecuted++;
    }
    
    public void incrementTradesRejected() {
        tradesRejected++;
    }
}
