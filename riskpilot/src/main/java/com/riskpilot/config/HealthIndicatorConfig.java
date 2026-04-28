package com.riskpilot.config;

import com.riskpilot.service.HeartbeatMonitor;
import com.riskpilot.service.MarketDataStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HealthIndicatorConfig {

    private final MarketDataStateService marketDataStateService;
    private final HeartbeatMonitor heartbeatMonitor;

    @Bean
    public HealthIndicator marketDataHealth() {
        return new MarketDataHealthIndicator(marketDataStateService);
    }

    @Bean
    public HealthIndicator tradingEngineHealth() {
        return new TradingEngineHealthIndicator(heartbeatMonitor);
    }

    public static class MarketDataHealthIndicator implements HealthIndicator {
        private final MarketDataStateService marketDataStateService;

        public MarketDataHealthIndicator(MarketDataStateService marketDataStateService) {
            this.marketDataStateService = marketDataStateService;
        }

        @Override
        public Health health() {
            try {
                var snapshot = marketDataStateService.snapshot();
                boolean isConnected = snapshot.connected() && snapshot.subscribed() && !snapshot.feedBlocked();
                if (isConnected && snapshot.lastTick() != null) {
                    return Health.up()
                            .withDetail("status", "Connected")
                            .withDetail("transport", snapshot.transport())
                            .withDetail("lastTickAt", snapshot.lastAcceptedAt())
                            .withDetail("sourceAgeMs", snapshot.lastTick().sourceAgeMs())
                            .withDetail("lastCheck", LocalDateTime.now())
                            .build();
                } else {
                    return Health.down()
                            .withDetail("status", "Disconnected")
                            .withDetail("reason", snapshot.blockReason())
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

    public static class TradingEngineHealthIndicator implements HealthIndicator {
        private final HeartbeatMonitor heartbeatMonitor;

        public TradingEngineHealthIndicator(HeartbeatMonitor heartbeatMonitor) {
            this.heartbeatMonitor = heartbeatMonitor;
        }

        @Override
        public Health health() {
            try {
                boolean isHealthy = heartbeatMonitor.isHealthy();
                String lastHeartbeat = heartbeatMonitor.getLastHeartbeatTime();
                String heartbeatDetail = lastHeartbeat == null ? "NEVER" : lastHeartbeat;
                
                if (isHealthy) {
                    return Health.up()
                            .withDetail("status", "Healthy")
                            .withDetail("lastHeartbeat", heartbeatDetail)
                            .withDetail("lastCheck", LocalDateTime.now())
                            .build();
                } else {
                    return Health.down()
                            .withDetail("status", "Unhealthy")
                            .withDetail("lastHeartbeat", heartbeatDetail)
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
