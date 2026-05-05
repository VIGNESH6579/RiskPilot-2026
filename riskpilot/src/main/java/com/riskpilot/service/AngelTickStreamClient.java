package com.riskpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

/**
 * Real-time WebSocket client for Angel One SmartAPI
 * Subscribes to live tick data feed
 */
@Slf4j
@Service
public class AngelTickStreamClient {

    @Value("${RISK_API_KEY}")
    private String apiKey;

    @Value("${RISK_CLIENT_CODE}")
    private String clientCode;

    @Value("${RISK_FEED_TOKEN}")
    private String feedToken;

    @Value("${RISK_NIFTY_TOKEN}")
    private String niftyToken;

    private final CandleAggregator candleAggregator;
    private final RealTimeTickAggregator realTimeTickAggregator;
    private final ObjectMapper objectMapper;
    
    private WebSocket webSocket;
    private final HttpClient httpClient;
    private volatile boolean isConnected = false;
    private volatile long reconnectAttempts = 0;
    private static final long MAX_RECONNECT_DELAY_MS = 30000;

    public AngelTickStreamClient(
            CandleAggregator candleAggregator,
            RealTimeTickAggregator realTimeTickAggregator,
            ObjectMapper objectMapper) {
        this.candleAggregator = candleAggregator;
        this.realTimeTickAggregator = realTimeTickAggregator;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @PostConstruct
    public void connect() {
        if (niftyToken == null || niftyToken.isBlank()) {
            throw new IllegalStateException("NIFTY token not configured. Set RISK_NIFTY_TOKEN.");
        }
        
        if (feedToken == null || feedToken.isBlank()) {
            throw new IllegalStateException("Feed token not configured. Set RISK_FEED_TOKEN.");
        }

        log.info("Connecting to Angel One WebSocket feed...");
        connectWebSocket();
    }

    private void connectWebSocket() {
        try {
            String wsUrl = "wss://smartapisocket.angelone.in/smart-stream";
            
            webSocket = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.info("WebSocket connection established");
                        isConnected = true;
                        reconnectAttempts = 0;
                        authenticate(webSocket);
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        handleTextMessage(data.toString());
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        handleBinaryMessage(data);
                        return WebSocket.Listener.super.onBinary(webSocket, data, last);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        log.warn("WebSocket closed: {} - {}", statusCode, reason);
                        isConnected = false;
                        scheduleReconnect();
                        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        log.error("WebSocket error", error);
                        isConnected = false;
                        scheduleReconnect();
                        WebSocket.Listener.super.onError(webSocket, error);
                    }
                })
                .join();

        } catch (Exception e) {
            log.error("Failed to connect WebSocket", e);
            scheduleReconnect();
        }
    }

    private void authenticate(WebSocket ws) {
        try {
            String authMessage = objectMapper.writeValueAsString(java.util.Map.of(
                "action", "authenticate",
                "params", java.util.Map.of(
                    "clientCode", clientCode,
                    "feedToken", feedToken
                )
            ));
            
            ws.sendText(authMessage, true);
            log.info("Authentication message sent");
            
            // Subscribe to NIFTY after authentication
            subscribeToSymbol(ws);
            
        } catch (Exception e) {
            log.error("Authentication failed", e);
        }
    }

    private void subscribeToSymbol(WebSocket ws) {
        try {
            String subscribeMessage = objectMapper.writeValueAsString(java.util.Map.of(
                "action", "subscribe",
                "params", java.util.Map.of(
                    "mode", 3, // LTP + Quotes + Depth
                    "tokenList", java.util.List.of(
                        java.util.Map.of(
                            "exchangeType", 1, // NSE
                            "tokens", java.util.List.of(niftyToken)
                        )
                    )
                )
            ));
            
            ws.sendText(subscribeMessage, true);
            log.info("Subscribed to NIFTY token: {}", niftyToken);
            
        } catch (Exception e) {
            log.error("Subscription failed", e);
        }
    }

    private void handleTextMessage(String message) {
        log.debug("Received text message: {}", message);
        // Handle acknowledgments and errors
    }

    private void handleBinaryMessage(ByteBuffer buffer) {
        try {
            // Angel One sends binary tick data - parse according to their format
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            
            // Parse binary tick (implement based on Angel One's binary protocol)
            TickData tick = parseBinaryTick(bytes);
            
            if (tick != null) {
                long now = System.currentTimeMillis();
                candleAggregator.processTick(now, tick.ltp, tick.volume, tick.sequence, now);
                realTimeTickAggregator.processAngelTick("NIFTY", tick.ltp, tick.volume);
                log.debug("Processed tick: LTP={}, Volume={}", tick.ltp, tick.volume);
            }
            
        } catch (Exception e) {
            log.error("Failed to process binary message", e);
        }
    }

    private TickData parseBinaryTick(byte[] data) {
        // Implement Angel One's binary protocol parsing
        // This is a placeholder - refer to Angel One API documentation
        // for exact binary format specification
        
        if (data.length < 20) {
            return null;
        }
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            
            TickData tick = new TickData();
            // Example parsing (adjust based on actual protocol):
            tick.sequence = buffer.getLong();
            tick.ltp = buffer.getDouble();
            tick.volume = buffer.getLong();
            
            return tick;
        } catch (Exception e) {
            log.error("Failed to parse binary tick", e);
            return null;
        }
    }

    private void scheduleReconnect() {
        reconnectAttempts++;
        long delay = Math.min(1000 * reconnectAttempts, MAX_RECONNECT_DELAY_MS);
        
        log.info("Scheduling reconnect in {} ms (attempt {})", delay, reconnectAttempts);
        
        new Thread(() -> {
            try {
                Thread.sleep(delay);
                connectWebSocket();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    @PreDestroy
    public void disconnect() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutting down");
            isConnected = false;
        }
    }

    private static class TickData {
        long sequence;
        double ltp;
        long volume;
    }
}
