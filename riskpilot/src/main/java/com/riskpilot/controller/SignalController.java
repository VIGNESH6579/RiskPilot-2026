package com.riskpilot.controller;

import org.springframework.web.bind.annotation.*;
import com.riskpilot.service.SignalService;
import com.riskpilot.model.Signal;

@RestController
@RequestMapping("/api")
public class SignalController {

    private final SignalService signalService;

    public SignalController(SignalService signalService) {
        this.signalService = signalService;
    }

    @GetMapping("/signal")
    public Signal getSignal() {
        return signalService.generateSignal();
    }
}