package com.riskpilot.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trade_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime signalTime;
    private LocalDateTime executionTime;
    private Double latencySec;
    private Double expectedEntry;
    private Double actualEntry;
    private Double entrySlippage;
    private Double expectedExit;
    private Double actualExit;
    private Double exitSlippage;
    private Boolean tp1Hit;
    private Boolean runnerCaptured;
    private Double mfe;
    private Double mae;
    private Double realizedR;
    private String gateDecision;
    private String rejectReason;
    private String regime;
    private String timePhase;
    private Boolean feedStable;
    private String exitReason;
    private LocalDateTime exitTime;
}
