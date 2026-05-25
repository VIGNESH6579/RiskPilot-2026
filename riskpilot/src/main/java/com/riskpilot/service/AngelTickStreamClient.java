package com.riskpilot.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AngelTickStreamClient — DEPRECATED REST-poller stub.
 *
 * The actual market-data feed is now handled by AngelSmartStreamClient (WebSocket).
 * This class is kept only so that any controllers or monitors that inject it
 * by name continue to compile. All tick-processing logic has been removed.
 *
 * Do NOT add polling logic back here. If you need to trigger a feed reconnect
 * from a controller, call AngelSmartStreamClient.resubscribe() instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AngelTickStreamClient {

    private final AngelSmartStreamClient smartStreamClient;

    @PostConstruct
    public void init() {
        log.info("AngelTickStreamClient stub active — live feed via AngelSmartStreamClient (WebSocket)");
    }

    @PreDestroy
    public void shutdown() {
        // nothing to clean up — lifecycle owned by AngelSmartStreamClient
    }

    /**
     * Request a feed reconnect. Delegates to the WebSocket client.
     * Called by MonitoringController and PipelineHealthController.
     */
    public void resubscribe() {
        log.info("resubscribe() called — delegating to AngelSmartStreamClient");
        smartStreamClient.resubscribe();
    }

    /** Whether the underlying WebSocket feed is currently connected. */
    public boolean isStreamActive() {
        return smartStreamClient.isConnected();
    }
}
