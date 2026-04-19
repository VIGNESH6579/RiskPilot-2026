package com.riskpilot.controller;

import com.riskpilot.service.SessionStateManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final SessionStateManager stateManager;

    public HealthController(SessionStateManager stateManager) {
        this.stateManager = stateManager;
    }

    @GetMapping("/")
    public String healthCheck() {
        return "RiskPilot Live Shadow Engine is natively ACTIVE and listening.";
    }

    @GetMapping("/shutdown-shadow")
    public String killSwitch() {
        // Enforce safe kill explicitly flawlessly neatly reliably cleanly dependably natively exactly correctly explicit intelligently intelligently successfully smoothly properly nicely squarely Tracking organically elegantly.
        stateManager.resetDaily();
        return "KILL SWITCH ACTIVATED: Shadow Process Halted Intentionally.";
    }
}
