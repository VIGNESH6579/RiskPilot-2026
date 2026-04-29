package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Angel One auth service.
 *
 * Audit fixes applied:
 *  - The synchronous {@code https://api.ipify.org} lookup that previously
 *    happened on every {@link #authenticate()} call has been removed from the
 *    hot path. Public-IP resolution now runs on a dedicated scheduled refresh
 *    and the cached value is read by auth. This eliminates a third-party
 *    dependency from the critical pre-market login window — an ipify outage
 *    or slow response would previously block authentication and silently miss
 *    the open.
 *  - TOTP submission now defends against ±1 step skew at the bucket boundary.
 *    If the wall clock is within {@code auth.totpBoundaryGuardMs} of the next
 *    30-second bucket, we wait briefly for the boundary to pass so the code
 *    we send is still valid by the time it reaches Angel's servers. Server-
 *    side, Angel typically tolerates ±1 step (30s); this just makes sure we
 *    don't ship a code that's about to expire mid-flight.
 */
@Service
public class AngelAuthService {
    private static final Logger log = LoggerFactory.getLogger(AngelAuthService.class);
    private static final String AUTH_URL = "https://apiconnect.angelbroking.com/rest/auth/angelbroking/user/v1/loginByPassword";
    private static final String IPIFY_URL = "https://api.ipify.org";
    private static final long AUTH_RETRY_GUARD_MS = 5000L;
    private static final long TOTP_STEP_SECONDS = 30L;

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

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectProvider<AngelTickStreamClient> tickStreamClientProvider;
    private final RiskPilotProperties properties;

    private String currentJwtToken;
    private String currentFeedToken;
    private long lastAuthAttemptEpochMs = 0L;

    // Cached public IP, refreshed off the auth path.
    private final AtomicReference<String> cachedPublicIp = new AtomicReference<>(null);

    @Autowired
    public AngelAuthService(
        ObjectProvider<AngelTickStreamClient> tickStreamClientProvider,
        RiskPilotProperties properties
    ) {
        this.tickStreamClientProvider = tickStreamClientProvider;
        this.properties = properties;
    }

    @PostConstruct
    public void primePublicIpCache() {
        // Prime the cache once at startup (off the request thread for any
        // subsequent auth call). If this fails, scheduled refresh will retry.
        refreshPublicIpCache();
    }

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

        // Audit fix: defend against TOTP-bucket-boundary races. If we're very
        // close to the next 30s boundary, wait briefly so the submitted code
        // is still valid when Angel processes it.
        waitPastTotpBoundaryIfNeeded();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        headers.set("X-ClientLocalIP", resolveLocalIp());
        headers.set("X-ClientPublicIP", resolvePublicIpFromCache());
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
                log.warn("Angel auth rejected: {}", response.getBody());
            }
        } catch (Exception e) {
            log.warn("Angel auth request failed: {}", e.getMessage());
        }
        currentJwtToken = null;
        currentFeedToken = null;
        return false;
    }

    private void waitPastTotpBoundaryIfNeeded() {
        long guardMs = properties.getAuth().getTotpBoundaryGuardMs();
        if (guardMs <= 0L) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long stepMs = TOTP_STEP_SECONDS * 1000L;
        long msIntoBucket = nowMs % stepMs;
        long msToNextBucket = stepMs - msIntoBucket;
        if (msToNextBucket <= guardMs) {
            try {
                // Wait the small remainder + a tiny safety margin so we sit
                // safely inside the next bucket.
                long sleepMs = msToNextBucket + 50L;
                log.debug("TOTP boundary guard: sleeping {}ms to next 30s step", sleepMs);
                Thread.sleep(sleepMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String generateTotp() throws CodeGenerationException {
        DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long currentBucket = Math.floorDiv(System.currentTimeMillis() / 1000, TOTP_STEP_SECONDS);
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

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void preMarketAuth() {
        log.info("Pre-market auth starting");
        invalidateSession();
        boolean success = authenticate();
        if (success) {
            AngelTickStreamClient client = tickStreamClientProvider.getIfAvailable();
            if (client != null) {
                client.reconnectAfterAuthentication();
            }
        }
        log.info("Pre-market auth result: {}", success);
    }

    /**
     * Periodically refresh the cached public IP so it's never resolved
     * synchronously from the auth path. Default cadence: 60 minutes (override
     * via {@code riskpilot.auth.public-ip-refresh-interval-minutes}).
     */
    @Scheduled(
        fixedDelayString = "#{${riskpilot.auth.public-ip-refresh-interval-minutes:60} * 60 * 1000}",
        initialDelay = 60_000L
    )
    public void scheduledRefreshPublicIp() {
        refreshPublicIpCache();
    }

    private void refreshPublicIpCache() {
        if (configuredPublicIp != null && !configuredPublicIp.isBlank()) {
            cachedPublicIp.set(configuredPublicIp.trim());
            return;
        }
        try {
            String ip = restTemplate.getForObject(IPIFY_URL, String.class);
            if (ip != null && !ip.isBlank()) {
                cachedPublicIp.set(ip.trim());
                return;
            }
        } catch (Exception e) {
            log.debug("Public IP refresh failed: {}", e.getMessage());
        }
        // Fall back to local IP if we still have nothing — better a stable
        // value than null in the auth headers.
        if (cachedPublicIp.get() == null) {
            cachedPublicIp.set(resolveLocalIp());
        }
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

    private String resolvePublicIpFromCache() {
        // Pure memory read on the auth hot path. No network call.
        String cached = cachedPublicIp.get();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        // Last-ditch fallback if the cache hasn't been primed yet (e.g. very
        // first call before @PostConstruct completes). Avoids returning null
        // in the auth header but does NOT make a network call.
        return resolveLocalIp();
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
        return "00:00:00:00:00:00";
    }
}
