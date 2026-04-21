package com.riskpilot.service;

public record GateDecision(boolean allowed, String reason) {
    public static GateDecision allow() {
        return new GateDecision(true, "ALLOW");
    }

    public static GateDecision reject(String reason) {
        return new GateDecision(false, reason);
    }
}
