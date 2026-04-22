package com.riskpilot.service;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class AngelAuthService {
    private static final Logger log = LoggerFactory.getLogger(AngelAuthService.class);
    private static final String AUTH_URL = "https://apiconnect.angelbroking.com/rest/auth/angelbroking/user/v1/loginByPassword";
    private static final long AUTH_RETRY_GUARD_MS = 5000L;

    @Value("${ANGEL_API_KEY:${angelapi.key:}}")
    private String apiKey;

    @Value("${ANGEL_CLIENT_ID:${angelapi.clientcode:}}")
    private String clientCode;

    @Value("${ANGEL_PIN:${angelapi.pin:}}")
    private String pin;

    @Value("${ANGEL_TOTP_SECRET:${angelapi.totp.secret:}}")
    private String totpSecret;

    private final RestTemplate restTemplate = new RestTemplate();
    private String currentJwtToken;
    private String currentFeedToken;
    private long lastAuthAttemptEpochMs = 0L;

    public synchronized boolean authenticate() {
        if (!hasCredentials()) {
            log.warn("Angel auth skipped: missing credentials");
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastAuthAttemptEpochMs < AUTH_RETRY_GUARD_MS) {
            return currentJwtToken != null && !currentJwtToken.isBlank();
        }
        lastAuthAttemptEpochMs = now;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        headers.set("X-ClientLocalIP", "192.168.1.1");
        headers.set("X-ClientPublicIP", "106.193.147.98");
        headers.set("X-MACAddress", "fe80::216:3eff:fe00:0000");
        headers.set("X-PrivateKey", apiKey);

        Map<String, String> body = new HashMap<>();
        body.put("clientcode", clientCode);
        body.put("password", pin);
        try {
            body.put("totp", generateTotp());
        } catch (Exception e) {
            log.error("Angel auth failed: unable to generate TOTP: {}", e.getMessage());
            return false;
        }

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(AUTH_URL, request, Map.class);
            if (response.getBody() != null && Boolean.TRUE.equals(response.getBody().get("status"))) {
                Map<String, String> data = (Map<String, String>) response.getBody().get("data");
                currentJwtToken = data.get("jwtToken");
                currentFeedToken = data.get("feedToken");
                log.info("Angel auth success");
                return true;
            } else {
                log.warn("Angel auth rejected: {}", response.getBody());
            }
        } catch (Exception e) {
            log.warn("Angel auth request failed: {}", e.getMessage());
        }
        currentJwtToken = null;
        currentFeedToken = null;
        return false;
    }

    private String generateTotp() throws CodeGenerationException {
        DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long currentBucket = Math.floorDiv(System.currentTimeMillis() / 1000, 30);
        return generator.generate(totpSecret, currentBucket);
    }
    
    public String getJwtToken() { return currentJwtToken; }
    public String getFeedToken() { return currentFeedToken; }
    public String getApiKey() { return apiKey; }
    public String getClientCode() { return clientCode; }
    public boolean hasCredentials() {
        return apiKey != null && !apiKey.isBlank()
            && clientCode != null && !clientCode.isBlank()
            && pin != null && !pin.isBlank()
            && totpSecret != null && !totpSecret.isBlank();
    }
    public synchronized void invalidateSession() {
        currentJwtToken = null;
        currentFeedToken = null;
    }
}
