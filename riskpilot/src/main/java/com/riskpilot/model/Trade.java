package com.riskpilot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trade {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 20)
    private String symbol;
    
    @Column(nullable = false, length = 10)
    private String direction; // LONG or SHORT
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal entryPrice;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal stopLoss;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal targetPrice;
    
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal positionSize;
    
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal remainingSize;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal realizedPnL;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unrealizedPnL;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal maxFavorableExcursion; // MFE
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal maxAdverseExcursion; // MAE
    
    @Column(nullable = false)
    private Boolean tp1Hit;
    
    @Column(nullable = false)
    private Boolean runnerActive;
    
    @Column(nullable = false)
    private Boolean tailHalfLocked;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal trailingStopLoss;
    
    @Column(nullable = false, length = 20)
    private String status; // ACTIVE, CLOSED, CANCELLED
    
    @Column(nullable = false, length = 50)
    private String exitReason;
    
    @Column(nullable = false)
    private LocalDateTime entryTime;
    
    @Column(nullable = true)
    private LocalDateTime exitTime;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "ACTIVE";
        if (realizedPnL == null) realizedPnL = BigDecimal.ZERO;
        if (unrealizedPnL == null) unrealizedPnL = BigDecimal.ZERO;
        if (tp1Hit == null) tp1Hit = false;
        if (runnerActive == null) runnerActive = false;
        if (tailHalfLocked == null) tailHalfLocked = false;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
    
    public BigDecimal getTotalPnL() {
        return realizedPnL.add(unrealizedPnL);
    }
}
