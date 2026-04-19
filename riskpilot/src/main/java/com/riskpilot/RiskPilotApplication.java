package com.riskpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RiskPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskPilotApplication.class, args);
        System.out.println("=================================================");
        System.out.println(">>> RISK PILOT : SHADOW EXECUTION ENGINE LIVE <<<");
        System.out.println("=================================================");
    }
}
