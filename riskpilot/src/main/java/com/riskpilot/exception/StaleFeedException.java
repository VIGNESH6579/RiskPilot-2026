package com.riskpilot.exception;

public class StaleFeedException extends MarketDataException {
    public StaleFeedException(String message) {
        super(message);
    }
}
