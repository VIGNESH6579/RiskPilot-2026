package com.riskpilot;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.engine.AdaptiveRegimeEngine;
import com.riskpilot.engine.KillSwitchEngine;
import com.riskpilot.service.StrictValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PostConstruct;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(RiskPilotProperties.class)
@EnableScheduling
@RequiredArgsConstructor
public class RiskPilotApplication {

    private final StrictValidationService strictValidationService;
    private final KillSwitchEngine killSwitchEngine;
    private final AdaptiveRegimeEngine adaptiveRegimeEngine;

    public static void main(String[] args) {
        SpringApplication.run(RiskPilotApplication.class, args);
        log.info("🚀 RISKPILOT SHADOW EXECUTION ENGINE LIVE");
    }

    @PostConstruct
    public void run() throws Exception {
        log.info("🔒 ENABLING STRICT VALIDATION");
        strictValidationService.validateSystem();
        
        log.info("🔄 INITIALIZING ADAPTIVE REGIME ENGINE");
        adaptiveRegimeEngine.initialize();
        
        log.info("✅ RISKPILOT SYSTEM READY - SHADOW MODE ACTIVE");
    }

    /**
     * Kill-switch monitor - checks every 30 seconds
     */
    @Scheduled(fixedDelay = 30000)
    public void monitorKillSwitch() {
        if (killSwitchEngine.isKillSwitchTriggered()) {
            log.error("🚨 KILL SWITCH DETECTED - SHUTTING DOWN");
            System.exit(1);
        }
    }
}
