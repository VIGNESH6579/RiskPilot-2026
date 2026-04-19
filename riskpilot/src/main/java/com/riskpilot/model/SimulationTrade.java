package com.riskpilot.model;

import java.time.LocalDateTime;

public class SimulationTrade {
    public final double entry;
    public final double sl;
    public final double target;
    public final int quantity;

    public boolean active;
    public final LocalDateTime entryTime;

    public SimulationTrade(double entry, double sl, double target, int quantity, LocalDateTime entryTime) {
        this.entry = entry;
        this.sl = sl;
        this.target = target;
        this.quantity = quantity;
        this.entryTime = entryTime;
        this.active = true;
    }
}
