package com.riskpilot.model;

public record GateDecision(
        boolean allowed,
        String reason
) {
    public static GateDecision allow() {
        return new GateDecision(true, "OK");
    }

    public static GateDecision reject(String reason) {
        return new GateDecision(false, reason);
    }
}
