package com.riskpilot;

import com.riskpilot.config.RiskPilotProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(RiskPilotProperties.class)
@EnableScheduling
@EnableAsync
public class RiskPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskPilotApplication.class, args);
        log.info("RiskPilot shadow execution engine live");
    }
}
