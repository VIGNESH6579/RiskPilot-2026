package com.riskpilot.controller;

import com.riskpilot.model.SystemState;
import com.riskpilot.service.SystemStateService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system")
@CrossOrigin
public class SystemController {

    private final SystemStateService systemStateService;

    public SystemController(SystemStateService systemStateService) {
        this.systemStateService = systemStateService;
    }

    @PostMapping("/start")
    public String start() {
        SystemState state = systemStateService.getState();
        state.setTradingEnabled(true);
        systemStateService.update(state);
        return "Trading STARTED";
    }

    @PostMapping("/stop")
    public String stop() {
        SystemState state = systemStateService.getState();
        state.setTradingEnabled(false);
        systemStateService.update(state);
        return "Trading STOPPED";
    }

    @GetMapping("/status")
    public SystemState status() {
        return systemStateService.getState();
    }

    @GetMapping("/health")
    public String health() {
        return "RiskPilot running";
    }
}