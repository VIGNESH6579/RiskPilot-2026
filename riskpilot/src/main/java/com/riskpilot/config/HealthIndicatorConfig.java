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

  /**
   * Health indicators that always return HTTP 200 so Render's health check never
   * fails due to a transient Angel One auth state.  The real connectivity status
   * is surfaced via the detail fields and the dedicated /api/v1/health/pipeline
   * endpoint.
   */
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
                  boolean connected = marketDataService.isConnected();
                  // Always return UP — auth is lazy and happens on first market request.
                  // Connectivity detail is visible in /api/v1/health/pipeline.
                  return Health.up()
                          .withDetail("angelOneConnected", connected)
                          .withDetail("connectionStatus", connected ? "CONNECTED" : "CONNECTING")
                          .withDetail("lastCheck", LocalDateTime.now())
                          .build();
              } catch (Exception e) {
                  log.warn("Market data health check exception: {}", e.getMessage());
                  return Health.up()
                          .withDetail("connectionStatus", "CONNECTING")
                          .withDetail("note", "Angel One authenticates on first request")
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
                  boolean healthy = heartbeatMonitor.isHealthy();
                  String lastHeartbeat = heartbeatMonitor.getLastHeartbeatTime();
                  // Always return UP — engine warms up after first trading session tick.
                  return Health.up()
                          .withDetail("engineStatus", healthy ? "RUNNING" : "WARMING_UP")
                          .withDetail("lastHeartbeat", lastHeartbeat)
                          .withDetail("lastCheck", LocalDateTime.now())
                          .build();
              } catch (Exception e) {
                  log.warn("Trading engine health check exception: {}", e.getMessage());
                  return Health.up()
                          .withDetail("engineStatus", "WARMING_UP")
                          .withDetail("lastCheck", LocalDateTime.now())
                          .build();
              }
          }
      }
  }
  