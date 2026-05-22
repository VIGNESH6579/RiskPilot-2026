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
    
    @Column(name = "realized_pn_l", nullable = false, precision = 10, scale = 2)
    private BigDecimal realizedPnL;
    
    @Column(name = "unrealized_pn_l", nullable = false, precision = 10, scale = 2)
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

    /** Initial risk in index points (|entry − stopLoss| × positionSize at trade open).
     *  Used to calculate R-multiples: realizedR = totalPnlPoints / initialRiskPoints */
    @Column(name = "initial_risk_points", nullable = false, precision = 10, scale = 4)
    private BigDecimal initialRiskPoints;

    /** Realised R-multiple: (tp1Pnl + exitPnl) / (riskPts × positionSize).
     *  Positive = profit, negative = loss.  Stored for accurate dashboard display. */
    @Column(name = "realized_r", nullable = false, precision = 10, scale = 4)
    private BigDecimal realizedR;

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
        // BUG-FIX: Initialize nullable=false fields that were missing from @PrePersist
        if (maxFavorableExcursion == null) maxFavorableExcursion = BigDecimal.ZERO;
        if (maxAdverseExcursion == null) maxAdverseExcursion = BigDecimal.ZERO;
        if (trailingStopLoss == null) trailingStopLoss = (stopLoss != null ? stopLoss : BigDecimal.ZERO);
        if (initialRiskPoints == null) initialRiskPoints = BigDecimal.ZERO;
        if (realizedR == null) realizedR = BigDecimal.ZERO;
        if (exitReason == null) exitReason = "NONE";
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
    
    public BigDecimal getTotalPnL() {
        BigDecimal realized = realizedPnL != null ? realizedPnL : BigDecimal.ZERO;
        BigDecimal unrealized = unrealizedPnL != null ? unrealizedPnL : BigDecimal.ZERO;
        return realized.add(unrealized);
    }
}
