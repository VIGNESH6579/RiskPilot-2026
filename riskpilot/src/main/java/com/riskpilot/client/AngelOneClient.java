package com.riskpilot.client;

import org.springframework.stereotype.Component;

@Component
public class AngelOneClient {

    public double fetchSpotPrice(String symbol) {
        // TODO: integrate Angel One SmartAPI
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public double fetchOptionPrice(String symbol) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public double fetchVix() {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}