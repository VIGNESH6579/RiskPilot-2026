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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
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
    @Value("${ANGEL_CLIENT_LOCAL_IP:}")
    private String configuredLocalIp;
    @Value("${ANGEL_CLIENT_PUBLIC_IP:}")
    private String configuredPublicIp;
    @Value("${ANGEL_CLIENT_MAC:}")
    private String configuredMac;

    // BUG-029: RestTemplate with proper timeouts
    private final RestTemplate restTemplate = buildRestTemplate();
    
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }
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
        headers.set("X-ClientLocalIP", resolveLocalIp());
        headers.set("X-ClientPublicIP", resolvePublicIp());
        headers.set("X-MACAddress", resolveMacAddress());
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
                // BUG-038: Sanitized log - never log full response body
                Map<?, ?> respBody = response.getBody();
                String errorCode = respBody != null ? String.valueOf(respBody.get("errorcode")) : "UNKNOWN";
                String message = respBody != null ? String.valueOf(respBody.get("message")) : "NO_RESPONSE";
                log.warn("Angel auth rejected errorCode={} message={}", errorCode, message);
                
                // BUG-028: If TOTP error (AB1010), try forward bucket
                if ("AB1010".equals(errorCode)) {
                    log.info("TOTP validation failed, trying forward bucket...");
                    return tryForwardBucketAuth(body, headers);
                }
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
    public boolean isAuthenticated() { return currentJwtToken != null && !currentJwtToken.isBlank(); }
    public String getLocalIp() { return resolveLocalIp(); }
    public String getPublicIp() { return resolvePublicIp(); }
    public String getMacAddress() { return resolveMacAddress(); }
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

    private String resolveLocalIp() {
        if (configuredLocalIp != null && !configuredLocalIp.isBlank()) {
            return configuredLocalIp.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
            return "127.0.0.1";
        }
    }

    private String resolvePublicIp() {
        if (configuredPublicIp != null && !configuredPublicIp.isBlank()) {
            return configuredPublicIp.trim();
        }
        // Best-effort fallback; keep request valid even if lookup fails.
        try {
            String ip = restTemplate.getForObject("https://api.ipify.org", String.class);
            if (ip != null && !ip.isBlank()) {
                return ip.trim();
            }
        } catch (Exception ignored) {
        }
        return resolveLocalIp();
    }

    /**
     * BUG-039: Resolve MAC address with proper fallback.
     * If no real MAC found, generates stable pseudo-MAC from clientCode hash.
     */
    private String resolveMacAddress() {
        if (configuredMac != null && !configuredMac.isBlank()) {
            return configuredMac.trim();
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length == 0) continue;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mac.length; i++) {
                    if (i > 0) sb.append(":");
                    sb.append(String.format("%02X", mac[i]));
                }
                return sb.toString();
            }
        } catch (SocketException ignored) {
        }
        
        // BUG-039: Generate stable pseudo-MAC from clientCode hash
        return generatePseudoMac();
    }
    
    /**
     * BUG-039: Generate stable pseudo-MAC from clientCode hash.
     * Angel flags zero-MAC as suspicious, so we generate a valid-looking MAC.
     */
    private String generatePseudoMac() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(clientCode.getBytes(StandardCharsets.UTF_8));
            // First 6 bytes as MAC address
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(":");
                // Locally administered, unicast: set bit 1 of first byte
                byte b = (i == 0) ? (byte)(hash[i] & 0xFE | 0x02) : hash[i];
                sb.append(String.format("%02X", b));
            }
            log.debug("Generated pseudo-MAC from clientCode hash");
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to generate pseudo-MAC: {}", e.getMessage());
            return "02:00:00:00:00:01"; // Fallback: locally administered MAC
        }
    }
    
    /**
     * BUG-028: Forward-bucket TOTP retry for clock drift > 30s ahead.
     */
    private boolean tryForwardBucketAuth(Map<String, String> originalBody, HttpHeaders originalHeaders) {
        try {
            Map<String, String> retryBody = new HashMap<>(originalBody);
            // BUG-028: Try forward bucket (currentBucket + 1)
            DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            long currentBucket = Math.floorDiv(System.currentTimeMillis() / 1000, 30);
            retryBody.put("totp", generator.generate(totpSecret, currentBucket + 1));

            HttpEntity<Map<String, String>> retryRequest = new HttpEntity<>(retryBody, originalHeaders);
            ResponseEntity<Map> response = restTemplate.postForEntity(AUTH_URL, retryRequest, Map.class);
            
            if (response.getBody() != null && Boolean.TRUE.equals(response.getBody().get("status"))) {
                Map<String, String> data = (Map<String, String>) response.getBody().get("data");
                currentJwtToken = data.get("jwtToken");
                currentFeedToken = data.get("feedToken");
                log.info("Angel auth success with forward bucket TOTP");
                return true;
            } else {
                // BUG-038: Sanitized log
                Map<?, ?> respBody = response.getBody();
                String errorCode = respBody != null ? String.valueOf(respBody.get("errorcode")) : "UNKNOWN";
                log.warn("Forward bucket auth also failed errorCode={}", errorCode);
            }
        } catch (Exception e) {
            log.warn("Forward bucket auth failed: {}", e.getMessage());
        }
        return false;
    }
}
