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
    // FIX: AngelSessionManager.initialize() was never called on startup.
    // This meant the first LTP fetch triggered lazy auth inside the 1-second poller tick,
    // causing the first ~30 seconds of market data to fail (auth latency) and producing
    // "Angel credentials missing" logs until the lazy auth finally succeeded.
    // Initializing here guarantees a valid JWT/feedToken before AngelTickStreamClient polls.
    private final AngelSessionManager angelSessionManager;

    @org.springframework.beans.factory.annotation.Value("${RISKPILOT_EXIT_ON_KILL_SWITCH:true}")
    private boolean exitOnKillSwitch;

    private boolean shutdownInitiated = false;

    @PostConstruct
    public void run() {
        log.info("Enabling strict validation");
        strictValidationService.validateSystem();

        log.info("🔐 Initializing Angel One broker session (BEFORE first tick poll)");
        try {
            angelSessionManager.initialize();
        } catch (Exception e) {
            // Non-fatal: AngelOneMarketDataService will retry auth on each LTP call.
            // But log loudly — if this fails, the first N seconds of data will be missing.
            log.error("⚠️ Angel One session initialization failed at startup: {}. "
                + "LTP fetches will retry auth automatically but first ticks may be missed.", e.getMessage());
        }

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
            
            // FIX: kill switch shutdown thread must also be a daemon thread
            Thread shutdown = new Thread(() -> {
                try {
                    Thread.sleep(10000);
                    log.info("Shutdown countdown complete - exiting");
                    int exitCode = SpringApplication.exit(applicationContext, () -> 1);
                    System.exit(exitCode);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "KillSwitchShutdown");
            shutdown.setDaemon(true);
            shutdown.start();
        }
    }
}
