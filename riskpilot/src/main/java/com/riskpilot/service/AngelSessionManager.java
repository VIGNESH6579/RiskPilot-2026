package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Centralized auth - login ONCE at startup only */
@Slf4j
@Service
public class AngelSessionManager {

    @Autowired private AngelOneClient angelOneClient;
    
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

    public void initialize() {
        log.info("🔐 SESSION_MANAGER: Initializing (ONCE)...");
        performAuthentication();
    }

    private void performAuthentication() {
        if (!authLock.tryLock()) {
            log.warn("Auth already in progress - skipping duplicate");
            return;
        }
        try {
            if (consecutiveFailures > 0) {
                Thread.sleep((long)Math.min(1000 * Math.pow(2, consecutiveFailures), 30000));
            }
            
            LoginResponse response = angelOneClient.login();
            if (response != null && response.isSuccess()) {
                sessionState.set(new SessionState(
                    response.getJwtToken(), response.getFeedToken(),
                    response.getClientCode(),
                    Instant.now().plusSeconds(response.getExpiresIn()), true
                ));
                consecutiveFailures = 0;
                log.info("✅ SESSION_ESTABLISHED");
            } else {
                handleFailure("Login failed");
            }
        } catch (Exception e) {
            handleFailure(e.getMessage());
        } finally {
            authLock.unlock();
        }
    }

    private void handleFailure(String reason) {
        consecutiveFailures++;
        log.error("❌ AUTH_FAILURE #{}", consecutiveFailures);
        if (consecutiveFailures >= maxRetryAttempts) {
            TradingSafetyManager.getInstance().emergencyStop("Auth circuit breaker tripped");
        }
    }

    @Scheduled(fixedRate = 60000)
    public void checkAndRefreshIfNeeded() {
        SessionState s = sessionState.get();
        if (s.isValid && s.expiry != null && 
            Instant.now().isAfter(s.expiry.minusMinutes(refreshBeforeExpiryMinutes))) {
            performAuthentication();
        }
    }

    public String getJwtToken() {
        SessionState s = sessionState.get();
        if (!s.isValid || s.jwtToken == null) throw new IllegalStateException("No valid session");
        return s.jwtToken;
    }

    public String getFeedToken() {
        SessionState s = sessionState.get();
        if (!s.isValid || s.feedToken == null) throw new IllegalStateException("No feed token");
        return s.feedToken;
    }

    public boolean isSessionValid() {
        SessionState s = sessionState.get();
        return s.isValid && s.expiry != null && Instant.now().isBefore(s.expiry);
    }

    static class SessionState {
        final String jwtToken, feedToken, clientCode;
        final Instant expiry;
        final boolean isValid;
        
        SessionState(String j, String f, String c, Instant e, boolean v) {
            jwtToken=j; feedToken=f; clientCode=c; expiry=e; isValid=v;
        }
    }
}