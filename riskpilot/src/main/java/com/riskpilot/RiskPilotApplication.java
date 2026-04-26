package com.riskpilot;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.engine.AdaptiveRegimeEngine;
import com.riskpilot.engine.KillSwitchEngine;
import com.riskpilot.service.StrictValidationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

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
        normalizeDatasourceEnvironment();
        SpringApplication.run(RiskPilotApplication.class, args);
        log.info("RiskPilot shadow execution engine live");
    }

    @PostConstruct
    public void run() {
        strictValidationService.validateSystem();
        adaptiveRegimeEngine.initialize();
        log.info("RiskPilot system ready in shadow mode");
    }

    @Scheduled(fixedDelay = 30000)
    public void monitorKillSwitch() {
        if (killSwitchEngine.isKillSwitchTriggered()) {
            log.error("Kill switch detected - shutting down");
            System.exit(1);
        }
    }

    private static void normalizeDatasourceEnvironment() {
        if (isBlank(System.getProperty("JDBC_DATABASE_URL")) && isBlank(System.getenv("JDBC_DATABASE_URL"))) {
            String databaseUrl = firstConfigured(System.getProperty("DATABASE_URL"), System.getenv("DATABASE_URL"));
            String jdbcUrl = toJdbcUrl(databaseUrl);
            if (!isBlank(jdbcUrl)) {
                System.setProperty("JDBC_DATABASE_URL", jdbcUrl);
            }
        }

        String effectiveJdbcUrl = firstConfigured(System.getProperty("JDBC_DATABASE_URL"), System.getenv("JDBC_DATABASE_URL"));
        if (!isBlank(effectiveJdbcUrl) && effectiveJdbcUrl.startsWith("jdbc:postgresql://")) {
            if (isBlank(System.getProperty("DATABASE_DRIVER")) && isBlank(System.getenv("DATABASE_DRIVER"))) {
                System.setProperty("DATABASE_DRIVER", "org.postgresql.Driver");
            }
            if (isBlank(System.getProperty("JPA_DIALECT")) && isBlank(System.getenv("JPA_DIALECT"))) {
                System.setProperty("JPA_DIALECT", "org.hibernate.dialect.PostgreSQLDialect");
            }
        }
    }

    private static String toJdbcUrl(String rawUrl) {
        if (isBlank(rawUrl)) {
            return null;
        }
        if (rawUrl.startsWith("jdbc:")) {
            return rawUrl;
        }
        if (rawUrl.startsWith("postgresql://")) {
            return "jdbc:" + rawUrl;
        }
        if (rawUrl.startsWith("postgres://")) {
            return "jdbc:postgresql://" + rawUrl.substring("postgres://".length());
        }
        return rawUrl;
    }

    private static String firstConfigured(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
