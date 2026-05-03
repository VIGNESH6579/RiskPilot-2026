package com.riskpilot.service;

import com.riskpilot.engine.AdaptiveRegimeEngine;
import com.riskpilot.engine.KillSwitchEngine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskPilotLifecycle {
    private final StrictValidationService strictValidationService;
    private final KillSwitchEngine killSwitchEngine;
    private final AdaptiveRegimeEngine adaptiveRegimeEngine;

    @PostConstruct
    public void run() {
        log.info("Enabling strict validation");
        strictValidationService.validateSystem();

        log.info("Initializing adaptive regime engine");
        adaptiveRegimeEngine.initialize();

        log.info("RiskPilot system ready - shadow mode active");
    }

    @Scheduled(fixedDelay = 30000)
    public void monitorKillSwitch() {
        if (killSwitchEngine.isKillSwitchTriggered()) {
            log.error("KILL_SWITCH_DETECTED - shutting down");
            System.exit(1);
        }
    }
}
