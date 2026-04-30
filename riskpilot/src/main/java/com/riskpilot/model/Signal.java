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

    /**
     * Per-signal sizing multiplier in [0.0, 1.0]. The position sizer
     * computes a maximum lot count from risk budget; this score scales
     * that maximum down for lower-conviction setups (e.g. shallow
     * traps, weak rejection wicks). 1.0 = full size, 0.0 = no trade.
     *
     * Defaults to 1.0 so any pre-existing signal source that does not
     * set the field continues to behave exactly as before.
     */
    private double convictionScore = 1.0;

    public void setConvictionScore(double convictionScore) {
        if (Double.isNaN(convictionScore) || convictionScore < 0.0) {
            this.convictionScore = 0.0;
        } else if (convictionScore > 1.0) {
            this.convictionScore = 1.0;
        } else {
            this.convictionScore = convictionScore;
        }
    }
    public double getConvictionScore() { return convictionScore; }
}
