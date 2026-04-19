package com.riskpilot.model;

import lombok.Data;

@Data
public class Signal {
    private String symbol;
    private String direction;
    private double entry;
    private double target;
    private double stopLoss;
    private int confidence;
}