package com.riskpilot.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Instrument (NIFTY, BANKNIFTY, etc.)
    private String symbol;

    // BUY or SELL
    private String direction;

    // Entry price
    private Double entryPrice;

    // Exit price
    private Double exitPrice;

    // Quantity
    private Integer quantity;

    // Stop Loss
    private Double stopLoss;

    // Target
    private Double target;

    // Actual PnL
    private Double pnl;

    // Trade open time
    private LocalDateTime entryTime;

    // Trade close time
    private LocalDateTime exitTime;

    // Status: OPEN / CLOSED / CANCELLED
    private String status;
}