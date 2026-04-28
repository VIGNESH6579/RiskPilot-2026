package com.riskpilot.controller;

import com.riskpilot.service.AngelAuthService;
import com.riskpilot.service.HeartbeatMonitor;
import com.riskpilot.service.MarketDataStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @Autowired
    private AngelAuthService angelAuthService;
    @Autowired
    private MarketDataStateService marketDataStateService;
    @Autowired
    private HeartbeatMonitor heartbeatMonitor;

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getHealthState() {
        Map<String, Object> healthStatus = new HashMap<>();
        var snapshot = marketDataStateService.snapshot();
        boolean healthy = snapshot.connected() && snapshot.subscribed() && snapshot.ready() && !snapshot.feedBlocked() && !snapshot.halted() && heartbeatMonitor.isHealthy();
        
        healthStatus.put("status", healthy ? "healthy" : "unhealthy");
        healthStatus.put("timestamp", Instant.now());
        healthStatus.put("service", "riskpilot-2026");
        healthStatus.put("angelOneConnected", snapshot.connected());
        healthStatus.put("credentialsConfigured", angelAuthService.hasCredentials());
        healthStatus.put("feedSubscribed", snapshot.subscribed());
        healthStatus.put("feedBlocked", snapshot.feedBlocked());
        healthStatus.put("feedBlockReason", snapshot.blockReason());
        healthStatus.put("halted", snapshot.halted());
        healthStatus.put("consecutiveRejectedTicks", snapshot.consecutiveRejectedTicks());
        healthStatus.put("parseFailureCount", snapshot.parseFailureCount());
        healthStatus.put("ready", snapshot.ready());
        healthStatus.put("transport", snapshot.transport() != null ? snapshot.transport().name() : null);
        healthStatus.put("sourceAgeMs", snapshot.lastTick() != null ? snapshot.lastTick().sourceAgeMs() : null);
        healthStatus.put("lastFreshTickAt", snapshot.lastAcceptedAt());
        healthStatus.put("version", "1.0.0");
        healthStatus.put("uptimeRobotReady", healthy);
        
        return ResponseEntity.status(healthy ? 200 : 503).body(healthStatus);
    }

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }

    @GetMapping("/detailed")
    public ResponseEntity<Map<String, Object>> getDetailedStatus() {
        Map<String, Object> details = new HashMap<>();
        var snapshot = marketDataStateService.snapshot();
        
        details.put("status", heartbeatMonitor.isHealthy() && snapshot.ready() && !snapshot.feedBlocked() && !snapshot.halted() ? "operational" : "degraded");
        details.put("uptime", "N/A");
        details.put("angelOneConnection", snapshot.connected());
        details.put("angelOneAuthenticated", angelAuthService.getJwtToken() != null);
        details.put("signalsProcessed", 0);
        details.put("lastSignalTime", null);
        details.put("webSocketConnected", snapshot.connected());
        details.put("halted", snapshot.halted());
        details.put("consecutiveRejectedTicks", snapshot.consecutiveRejectedTicks());
        details.put("parseFailureCount", snapshot.parseFailureCount());
        details.put("ready", snapshot.ready());
        details.put("transport", snapshot.transport() != null ? snapshot.transport().name() : null);
        details.put("sourceAgeMs", snapshot.lastTick() != null ? snapshot.lastTick().sourceAgeMs() : null);
        details.put("lastFreshTickAt", snapshot.lastAcceptedAt());
        details.put("timestamp", Instant.now());
        
        return ResponseEntity.ok(details);
    }
}
