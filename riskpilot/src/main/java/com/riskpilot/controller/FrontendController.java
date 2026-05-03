package com.riskpilot.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class FrontendController {

    private static final Path EXTERNAL_FRONTEND = Path.of("frontend.html");
    private static final List<String> CLASSPATH_FRONTENDS = List.of(
            "static/index.html",
            "frontend.html"
    );

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getFrontend() {
        try {
            return ResponseEntity.ok(loadFrontendHtml());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Error loading frontend");
        }
    }

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
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
                "websocket", "ws://host/ws",
                "apiEndpoints", Map.of(
                    "tradeHistory", "/api/v1/data/trade-history",
                    "monitoring", "/api/v1/monitor/state",
                    "health", "/api/v1/data/health"
                )
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to load initial data"
            ));
        }
    }

    private String loadFrontendHtml() throws IOException {
        if (Files.isRegularFile(EXTERNAL_FRONTEND)) {
            return Files.readString(EXTERNAL_FRONTEND, StandardCharsets.UTF_8);
        }

        for (String location : CLASSPATH_FRONTENDS) {
            Resource resource = new ClassPathResource(location);
            if (resource.exists()) {
                try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                    return FileCopyUtils.copyToString(reader);
                }
            }
        }

        throw new IOException("No frontend asset found");
    }
}
