package com.riskpilot.config;

import com.riskpilot.service.AngelOneMarketDataService;
import com.riskpilot.service.HeartbeatMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HealthIndicatorConfig {

    private final AngelOneMarketDataService marketDataService;
    private final HeartbeatMonitor heartbeatMonitor;

    @Bean
    public HealthIndicator marketDataHealth() {
        return new MarketDataHealthIndicator(marketDataService);
    }

    @Bean
    public HealthIndicator tradingEngineHealth() {
        return new TradingEngineHealthIndicator(heartbeatMonitor);
    }

    @Component
    public static class MarketDataHealthIndicator implements HealthIndicator {
        private final AngelOneMarketDataService marketDataService;

        public MarketDataHealthIndicator(AngelOneMarketDataService marketDataService) {
            this.marketDataService = marketDataService;
        }

        @Override
        public Health health() {
            try {
                boolean isConnected = marketDataService.isConnected();
                if (isConnected) {
                    return Health.up()
                            .withDetail("status", "Connected")
                            .withDetail("lastCheck", LocalDateTime.now())
                            .build();
                } else {
                    return Health.down()
                            .withDetail("status", "Disconnected")
                            .withDetail("lastCheck", LocalDateTime.now())
                            .build();
                }
            } catch (Exception e) {
                log.error("Market data health check failed", e);
                return Health.down()
                        .withDetail("error", e.getMessage())
                        .withDetail("lastCheck", LocalDateTime.now())
                        .build();
            }
        }
    }

    @Component
    public static class TradingEngineHealthIndicator implements HealthIndicator {
        private final HeartbeatMonitor heartbeatMonitor;

        public TradingEngineHealthIndicator(HeartbeatMonitor heartbeatMonitor) {
            this.heartbeatMonitor = heartbeatMonitor;
        }

        @Override
        public Health health() {
            try {
                boolean isHealthy = heartbeatMonitor.isHealthy();
                long lastHeartbeat = heartbeatMonitor.getLastHeartbeatTime();
                
                if (isHealthy) {
                    return Health.up()
                            .withDetail("status", "Healthy")
                            .withDetail("lastHeartbeat", lastHeartbeat)
                            .withDetail("lastCheck", LocalDateTime.now())
                            .build();
                } else {
                    return Health.down()
                            .withDetail("status", "Unhealthy")
                            .withDetail("lastHeartbeat", lastHeartbeat)
                            .withDetail("lastCheck", LocalDateTime.now())
                            .build();
                }
            } catch (Exception e) {
                log.error("Trading engine health check failed", e);
                return Health.down()
                        .withDetail("error", e.getMessage())
                        .withDetail("lastCheck", LocalDateTime.now())
                        .build();
            }
        }
    }
}
