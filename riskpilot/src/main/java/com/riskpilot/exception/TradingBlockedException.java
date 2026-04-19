package com.riskpilot.exception;

public class TradingBlockedException extends RuntimeException {
    public TradingBlockedException(String message) {
        super(message);
    }
}