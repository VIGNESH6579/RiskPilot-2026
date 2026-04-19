package com.riskpilot.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class SystemState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private boolean tradingEnabled;

    private String lastSignal;

    private long lastTradeTime;
}