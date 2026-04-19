package com.riskpilot.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
public class SignalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime timestamp;
    private double spot;
    private double vix;
    private int support;
    private int resistance;
    private int confidence;
    private String decision; // BUY / NO_TRADE
    private String reason;

    public void setId(Long id) { this.id = id; }
    public Long getId() { return id; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setSpot(double spot) { this.spot = spot; }
    public double getSpot() { return spot; }
    public void setVix(double vix) { this.vix = vix; }
    public double getVix() { return vix; }
    public void setSupport(int support) { this.support = support; }
    public int getSupport() { return support; }
    public void setResistance(int resistance) { this.resistance = resistance; }
    public int getResistance() { return resistance; }
    public void setConfidence(int confidence) { this.confidence = confidence; }
    public int getConfidence() { return confidence; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getDecision() { return decision; }
    public void setReason(String reason) { this.reason = reason; }
    public String getReason() { return reason; }
}
