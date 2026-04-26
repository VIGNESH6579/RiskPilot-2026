package com.riskpilot.model;

public class Signal {
    private String symbol;
    private String direction;
    private double entry;
    private double stopLoss;
    private double target;
    private int confidence;
    private int quantity;

    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSymbol() { return symbol; }
    
    public void setDirection(String direction) { this.direction = direction; }
    public String getDirection() { return direction; }
    
    public void setEntry(double entry) { this.entry = entry; }
    public double getEntry() { return entry; }
    
    public void setStopLoss(double stopLoss) { this.stopLoss = stopLoss; }
    public double getStopLoss() { return stopLoss; }
    
    public void setTarget(double target) { this.target = target; }
    public double getTarget() { return target; }
    
    public void setConfidence(int confidence) { this.confidence = confidence; }
    public int getConfidence() { return confidence; }

    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getQuantity() { return quantity; }
}
