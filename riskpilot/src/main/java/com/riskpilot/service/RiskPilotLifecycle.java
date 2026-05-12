package com.riskpilot.service;

import com.riskpilot.engine.AdaptiveRegimeEngine;
import com.riskpilot.engine.KillSwitchEngine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskPilotLifecycle {
    private final StrictValidationService strictValidationService;
    private final KillSwitchEngine killSwitchEngine;
    private final AdaptiveRegimeEngine adaptiveRegimeEngine;
    private final ConfigurableApplicationContext applicationContext;
    private final WebSocketService webSocketService;

    @org.springframework.beans.factory.annotation.Value("${RISKPILOT_EXIT_ON_KILL_SWITCH:true}")
    private boolean exitOnKillSwitch;

    private boolean shutdownInitiated = false;

    @PostConstruct
    public void run() {
        log.info("Enabling strict validation");
        strictValidationService.validateSystem();

        log.info("Initializing adaptive regime engine");
        adaptiveRegimeEngine.initialize();

        log.info("RiskPilot system ready - shadow mode active");
    }

    @Scheduled(fixedDelay = 10000)
    public void monitorKillSwitch() {
        if (exitOnKillSwitch && killSwitchEngine.isKillSwitchTriggered() && !shutdownInitiated) {
            shutdownInitiated = true;
            String reason = killSwitchEngine.getCurrentState().getReason();
            log.error("KILL_SWITCH_DETECTED: {} - initiating rate-limited shutdown (10s countdown)", reason);
            
            webSocketService.broadcastKillSwitchExit(reason, 10);
            
            new Thread(() -> {
                try {
                    Thread.sleep(10000);
                    log.info("Shutdown countdown complete - exiting");
                    int exitCode = SpringApplication.exit(applicationContext, () -> 1);
                    System.exit(exitCode);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }
}
