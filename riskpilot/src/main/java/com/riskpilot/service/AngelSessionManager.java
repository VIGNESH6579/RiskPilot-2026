package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Centralized Session Management
 * Login ONCE at startup, proactive refresh before expiry.
 * Compatible with Spring Boot 3.x / Java 17
 */
@Slf4j
@Service
public class AngelSessionManager {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${angel.api.url:https://apiconnect.angelone.co.in}")
    private String apiUrl;

    @Value("${angel.session.refresh-before-expiry-minutes:5}")
    private int refreshBeforeExpiryMinutes;

    @Value("${angel.session.max-retry-attempts:3}")
    private int maxRetryAttempts;

    private final AtomicReference<SessionState> sessionState = new AtomicReference<>();
    private final ReentrantLock authLock = new ReentrantLock();
    private volatile int consecutiveFailures = 0;

    public AngelSessionManager() {
        sessionState.set(new SessionState(null, null, null, Instant.EPOCH, false));
    }

    /** Initialize session ONCE at startup */
    public void initialize() {
        log.info("SESSION_MANAGER: Initializing broker authentication (ONCE)...");
        performAuthentication();
        log.info("Session manager initialized");
    }

    private void performAuthentication() {
        if (!authLock.tryLock()) {
            log.warn("Auth already in progress - skipping duplicate request");
            return;
        }

        try {
            if (consecutiveFailures > 0) {
                long backoffMs = (long) Math.min(1000 * Math.pow(2, consecutiveFailures), 30000);
                log.warn("Backing off auth attempt {} for {}ms", consecutiveFailures, backoffMs);
                Thread.sleep(backoffMs);
            }

            log.info("Executing broker login...");
            boolean loginSuccess = attemptLogin();

            if (loginSuccess) {
                SessionState newState = new SessionState(
                    "simulated-jwt-token",
                    "simulated-feed-token",
                    "client-code",
                    Instant.now().plusSeconds(3600),
                    true
                );
                sessionState.set(newState);
                consecutiveFailures = 0;
                log.info("SESSION_ESTABLISHED | Next refresh before expiry");
            } else {
                handleAuthFailure("Login returned unsuccessful response");
            }

        } catch (Exception e) {
            handleAuthFailure(e.getMessage());
        } finally {
            authLock.unlock();
        }
    }

    private boolean attemptLogin() {
        try {
            log.info("TODO: Implement actual Angel One authentication in attemptLogin()");
            return true;
        } catch (Exception e) {
            log.error("Login failed: {}", e.getMessage());
            return false;
        }
    }

    private void handleAuthFailure(String reason) {
        consecutiveFailures++;
        log.error("AUTH_FAILURE #{} | Reason: {}", consecutiveFailures, reason);

        if (consecutiveFailures >= maxRetryAttempts) {
            log.error("CIRCUIT_BREAKER_OPEN | Auth failed {} times. TRADING DISABLED.", maxRetryAttempts);
            // Use emergencyStop alias so TradingSafetyManager.emergency() is called correctly
            TradingSafetyManager.getInstance().emergencyStop(
                "Broker authentication circuit breaker tripped");
        }
    }

    /** Scheduled check every minute - refresh if expiring soon */
    @Scheduled(fixedRate = 60000)
    public void checkAndRefreshIfNeeded() {
        SessionState current = sessionState.get();
        if (!current.isValid || current.expiry == null) return;

        // FIX: Instant does not have minusMinutes(); use minus(long, ChronoUnit) instead
        Instant refreshThreshold = current.expiry.minus(refreshBeforeExpiryMinutes, ChronoUnit.MINUTES);

        if (Instant.now().isAfter(refreshThreshold)) {
            log.info("Token expiring soon - Proactive refresh initiated");
            performAuthentication();
        }
    }

    public String getJwtToken() {
        SessionState state = sessionState.get();
        if (!state.isValid || state.jwtToken == null) {
            throw new IllegalStateException("No valid session available");
        }
        return state.jwtToken;
    }

    public String getFeedToken() {
        SessionState state = sessionState.get();
        if (!state.isValid || state.feedToken == null) {
            throw new IllegalStateException("No valid feed token available");
        }
        return state.feedToken;
    }

    public boolean isSessionValid() {
        SessionState state = sessionState.get();
        return state.isValid && state.expiry != null && Instant.now().isBefore(state.expiry);
    }

    public SessionHealth getHealth() {
        return new SessionHealth(isSessionValid(), consecutiveFailures, sessionState.get().expiry);
    }

    // ---- Immutable DTOs -------------------------------------------------------

    public static class SessionState {
        final String jwtToken;
        final String feedToken;
        final String clientCode;
        final Instant expiry;
        final boolean isValid;

        public SessionState(String jwtToken, String feedToken, String clientCode,
                            Instant expiry, boolean isValid) {
            this.jwtToken = jwtToken;
            this.feedToken = feedToken;
            this.clientCode = clientCode;
            this.expiry = expiry;
            this.isValid = isValid;
        }
    }

    public static class SessionHealth {
        public final boolean isValid;
        public final int failureCount;
        public final Instant nextExpiry;

        public SessionHealth(boolean isValid, int failureCount, Instant nextExpiry) {
            this.isValid = isValid;
            this.failureCount = failureCount;
            this.nextExpiry = nextExpiry;
        }
    }
}
