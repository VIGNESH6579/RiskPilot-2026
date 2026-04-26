package com.riskpilot.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class FrontendController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getFrontend() {
        try {
            Resource resource = new ClassPathResource("static/index.html");
            if (resource.exists()) {
                String content = FileCopyUtils.copyToString(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
                return ResponseEntity.ok(content);
            } else {
                // Fallback to simple HTML if the bundled dashboard is not available
                String fallbackHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>RiskPilot</title></head>
                    <body>
                        <h1>RiskPilot Trading System</h1>
                        <p>Frontend file not found. Please check deployment.</p>
                    </body>
                    </html>
                    """;
                return ResponseEntity.ok(fallbackHtml);
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Error loading frontend");
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "frontend", "SERVED",
            "timestamp", System.currentTimeMillis()
        ));
    }

    @GetMapping("/api/initial-data")
    public ResponseEntity<Map<String, Object>> getInitialData() {
        try {
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Frontend-backend connection established",
                "websocket", "/ws",
                "apiEndpoints", Map.of(
                    "tradeHistory", "/api/v1/data/trade-history",
                    "monitoring", "/api/v1/monitor/state",
                    "health", "/api/v1/data/health",
                    "systemHealth", "/api/v1/health/state"
                )
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to load initial data"
            ));
        }
    }
}
