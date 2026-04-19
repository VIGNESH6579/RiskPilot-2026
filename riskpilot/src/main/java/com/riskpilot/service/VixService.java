package com.riskpilot.service;

import org.springframework.stereotype.Service;

@Service
public class VixService {

    // Temporary mock (we replace later with real API)
    public double getCurrentVix() {

        // 🔴 CHANGE THIS MANUALLY FOR TESTING
        return 23;

        // Later:
        // return fetchFromAPI();
    }
}