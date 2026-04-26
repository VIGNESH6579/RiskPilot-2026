package com.riskpilot.controller;

import com.riskpilot.service.AngelAuthService;
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

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getHealthState() {
        Map<String, Object> healthStatus = new HashMap<>();
        
        healthStatus.put("status", "healthy");
        healthStatus.put("timestamp", Instant.now());
        healthStatus.put("service", "riskpilot-2026");
        healthStatus.put("angelOneConnected", angelAuthService.getJwtToken() != null);
        healthStatus.put("credentialsConfigured", angelAuthService.hasCredentials());
        healthStatus.put("version", "1.0.0");
        healthStatus.put("uptimeRobotReady", true);
        
        return ResponseEntity.ok(healthStatus);
    }

    @GetMapping("/detailed")
    public ResponseEntity<Map<String, Object>> getDetailedStatus() {
        Map<String, Object> details = new HashMap<>();
        
        details.put("status", "operational");
        details.put("uptime", "N/A");
        details.put("angelOneConnection", angelAuthService.hasCredentials());
        details.put("angelOneAuthenticated", angelAuthService.getJwtToken() != null);
        details.put("signalsProcessed", 0);
        details.put("lastSignalTime", null);
        details.put("webSocketConnected", true);
        details.put("timestamp", Instant.now());
        
        return ResponseEntity.ok(details);
    }
}
