package com.riskpilot.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trading_signals")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingSignal {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @NotBlank
    @Column(nullable = false, length = 20)
    private String symbol;
    
    @NotBlank
    @Column(nullable = false, length = 10)
    private String direction; // LONG or SHORT
    
    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal expectedEntry;
    
    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal stopLoss;
    
    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal targetPrice;
    
    @Column(nullable = false)
    private Integer confidence; // 1-100
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal riskAmount;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal rewardAmount;
    
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal riskRewardRatio;
    
    @Column(nullable = false, length = 20)
    private String regime; // TREND, RANGE, VOLATILE
    
    @Column(nullable = false, length = 20)
    private String timePhase; // EARLY, MIDDLE, LATE
    
    @Column(nullable = false, length = 20)
    private String status; // GENERATED, EXECUTED, REJECTED, EXPIRED
    
    @Column(nullable = true, length = 100)
    private String rejectionReason;
    
    @Column(nullable = false)
    private LocalDateTime signalTime;
    
    @Column(nullable = true)
    private LocalDateTime executionTime;
    
    @Column(nullable = true, precision = 8, scale = 3)
    private BigDecimal executionLatencySeconds;
    
    @Column(nullable = true, precision = 10, scale = 2)
    private BigDecimal actualEntry;
    
    @Column(nullable = true, precision = 5, scale = 2)
    private BigDecimal entrySlippage;
    
    @Column(nullable = true, length = 50)
    private String strategy; // TRAP, BREAKOUT, etc.
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "GENERATED";
        if (confidence == null) confidence = 50;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public boolean isExecutable() {
        return "GENERATED".equals(status);
    }
    
    public boolean isExpired() {
        return signalTime.plusMinutes(15).isBefore(LocalDateTime.now());
    }
}
