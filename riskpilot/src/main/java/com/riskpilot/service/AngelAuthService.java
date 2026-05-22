package com.riskpilot.service;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Service
public class AngelAuthService {
    private static final Logger log = LoggerFactory.getLogger(AngelAuthService.class);
    private static final String AUTH_URL = "https://apiconnect.angelbroking.com/rest/auth/angelbroking/user/v1/loginByPassword";
    private static final long AUTH_RETRY_GUARD_MS = 30000L; // Increase guard to 30s to reduce log noise

    // Render env vars use ANGEL_* naming; legacy fallbacks for ANGELONE_* and property-style names
    @Value("${ANGEL_API_KEY:${ANGELONE_API_KEY:${angelapi.key:}}}")
    private String apiKey;

    @Value("${ANGEL_CLIENT_ID:${ANGELONE_CLIENT_ID:${angelapi.clientcode:}}}")
    private String clientCode;

    @Value("${ANGEL_PIN:${ANGELONE_PIN:${angelapi.pin:}}}")
    private String pin;

    @Value("${ANGEL_TOTP_SECRET:${ANGELONE_TOTP_SECRET:${angelapi.totp.secret:}}}")
    private String totpSecret;

    @Value("${ANGEL_CLIENT_LOCAL_IP:}")
    private String configuredLocalIp;

    @Value("${ANGEL_CLIENT_PUBLIC_IP:}")
    private String configuredPublicIp;

    @Value("${ANGEL_CLIENT_MAC:}")
    private String configuredMac;

    private final Environment environment;

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
    private long lastSuccessfulAuthEpochMs = 0L;
    private static final long TOKEN_EXPIRY_THRESHOLD_MS = 18 * 60 * 60 * 1000L; // 18 hours
    private volatile String cachedPublicIp;

    public AngelAuthService(Environment environment) {
        this.environment = environment;
    }

    public synchronized boolean authenticate() {
        if (!hasCredentials()) {
            log.warn("Angel auth skipped: missing credentials");
            return false;
        }

        long now = System.currentTimeMillis();

        // If we already have a token and it's not "old", don't re-auth
        if (isAuthenticated() && (now - lastSuccessfulAuthEpochMs < TOKEN_EXPIRY_THRESHOLD_MS)) {
            log.debug("Using cached Angel One token (age: {}ms)", now - lastSuccessfulAuthEpochMs);
            return true;
        }

        if (now - lastAuthAttemptEpochMs < AUTH_RETRY_GUARD_MS) {
            // Only warn if we are NOT authenticated. If we ARE authenticated, just return true silently.
            if (!isAuthenticated()) {
                log.warn("Angel auth throttled: last attempt was {}ms ago", now - lastAuthAttemptEpochMs);
            }
            return isAuthenticated();
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
                return applyAuthTokens(response.getBody(), "Angel auth");
            } else {
                Map<?, ?> respBody = response.getBody();
                String errorCode = respBody != null ? String.valueOf(respBody.get("errorcode")) : "UNKNOWN";
                String message = respBody != null ? String.valueOf(respBody.get("message")) : "NO_RESPONSE";
                log.warn("Angel auth rejected errorCode={} message={}", errorCode, message);
                
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

    /**
     * BUG-FIX: Only enforce credentials as a hard startup requirement on the prod profile.
     * In dev/test the app must be able to start without Angel One credentials so
     * unit tests and local smoke-tests work without live broker access.
     */
    @jakarta.annotation.PostConstruct
    public void assertCredentialsPresent() {
        boolean isProd = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));

        if (!hasCredentials()) {
            if (isProd) {
                throw new IllegalStateException(
                    "Angel One credentials are not configured. " +
                    "Set env vars: ANGEL_API_KEY, ANGEL_CLIENT_ID, ANGEL_PIN, ANGEL_TOTP_SECRET " +
                    "(check Render Dashboard → Environment tab).");
            } else {
                log.warn("Angel One credentials not set — running in credential-less dev mode. " +
                         "Live market data will be unavailable.");
            }
        }
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
        if (cachedPublicIp != null && !cachedPublicIp.isBlank()) {
            return cachedPublicIp;
        }
        try {
            String ip = restTemplate.getForObject("https://api.ipify.org", String.class);
            if (ip != null && !ip.isBlank()) {
                cachedPublicIp = ip.trim();
                return cachedPublicIp;
            }
        } catch (Exception e) {
            log.warn("ANGEL_CLIENT_PUBLIC_IP not set and public IP discovery failed: {}", e.getMessage());
        }
        cachedPublicIp = resolveLocalIp();
        return cachedPublicIp;
    }

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
        return generatePseudoMac();
    }
    
    private String generatePseudoMac() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest((clientCode != null ? clientCode : "default").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(":");
                byte b = (i == 0) ? (byte)(hash[i] & 0xFE | 0x02) : hash[i];
                sb.append(String.format("%02X", b));
            }
            log.debug("Generated pseudo-MAC from clientCode hash");
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to generate pseudo-MAC: {}", e.getMessage());
            return "02:00:00:00:00:01";
        }
    }
    
    private boolean tryForwardBucketAuth(Map<String, String> originalBody, HttpHeaders originalHeaders) {
        try {
            Map<String, String> retryBody = new HashMap<>(originalBody);
            DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            long currentBucket = Math.floorDiv(System.currentTimeMillis() / 1000, 30);
            retryBody.put("totp", generator.generate(totpSecret, currentBucket + 1));

            HttpEntity<Map<String, String>> retryRequest = new HttpEntity<>(retryBody, originalHeaders);
            ResponseEntity<Map> response = restTemplate.postForEntity(AUTH_URL, retryRequest, Map.class);
            
            if (response.getBody() != null && Boolean.TRUE.equals(response.getBody().get("status"))) {
                return applyAuthTokens(response.getBody(), "Forward bucket Angel auth");
            } else {
                Map<?, ?> respBody = response.getBody();
                String errorCode = respBody != null ? String.valueOf(respBody.get("errorcode")) : "UNKNOWN";
                log.warn("Forward bucket auth also failed errorCode={}", errorCode);
            }
        } catch (Exception e) {
            log.warn("Forward bucket auth failed: {}", e.getMessage());
        }
        return false;
    }

    private boolean applyAuthTokens(Map<?, ?> responseBody, String context) {
        Object rawData = responseBody.get("data");
        if (!(rawData instanceof Map<?, ?> data)) {
            log.warn("{}: unexpected response shape data={}", context, rawData);
            currentJwtToken = null;
            currentFeedToken = null;
            return false;
        }

        Object jwt = data.get("jwtToken");
        Object feed = data.get("feedToken");
        if (jwt == null || jwt.toString().isBlank()) {
            log.warn("{}: missing jwtToken in response", context);
            currentJwtToken = null;
            currentFeedToken = null;
            return false;
        }

        currentJwtToken = jwt.toString();
        currentFeedToken = feed != null ? feed.toString() : null;
        lastSuccessfulAuthEpochMs = System.currentTimeMillis();
        log.info("{} success", context);
        return true;
    }
}
