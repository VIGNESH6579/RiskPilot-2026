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
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TRUE WebSocket client for Angel One SmartAPI
 * NO POLLING - Uses genuine streaming tick data
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
    private final NtfyNotificationService notificationService;
    
    private WebSocket webSocket;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    
    private volatile boolean isConnected = false;
    private volatile boolean isAuthenticated = false;
    private volatile boolean isSubscribed = false;
    private volatile long reconnectAttempts = 0;
    private volatile long lastTickReceivedTime = 0;
    
    private final AtomicLong tickCount = new AtomicLong(0);
    private final AtomicLong totalVolume = new AtomicLong(0);
    private final ConcurrentHashMap<String, Double> lastPrice = new ConcurrentHashMap<>();
    
    private static final long MAX_RECONNECT_DELAY_MS = 30000;
    private static final long TICK_TIMEOUT_MS = 30000; // Alert if no ticks for 30s
    private static final String WS_URL = "wss://smartapisocket.angelone.in/smart-stream";

    public AngelTickStreamClient(
            CandleAggregator candleAggregator,
            RealTimeTickAggregator realTimeTickAggregator,
            ObjectMapper objectMapper,
            NtfyNotificationService notificationService) {
        this.candleAggregator = candleAggregator;
        this.realTimeTickAggregator = realTimeTickAggregator;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.httpClient = HttpClient.newHttpClient();
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    @PostConstruct
    public void initialize() {
        validateConfiguration();
        log.info("🚀 Initializing TRUE WebSocket connection (NO POLLING)...");
        connectWebSocket();
        
        // Health monitor every 30 seconds
        scheduler.scheduleAtFixedRate(this::healthCheck, 30, 30, TimeUnit.SECONDS);
        
        // Tick timeout monitor
        scheduler.scheduleAtFixedRate(this::checkTickTimeout, 60, 60, TimeUnit.SECONDS);
    }

    private void validateConfiguration() {
        if (niftyToken == null || niftyToken.isBlank()) {
            throw new IllegalStateException("❌ NIFTY token not configured. Set RISK_NIFTY_TOKEN");
        }
        if (feedToken == null || feedToken.isBlank()) {
            throw new IllegalStateException("❌ Feed token not configured. Set RISK_FEED_TOKEN");
        }
        if (clientCode == null || clientCode.isBlank()) {
            throw new IllegalStateException("❌ Client code not configured. Set RISK_CLIENT_CODE");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("❌ API key not configured. Set RISK_API_KEY");
        }
        
        log.info("✅ Configuration validated:");
        log.info("   Client: {}", clientCode);
        log.info("   NIFTY Token: {}", niftyToken);
        log.info("   Feed Token: {} **** ", feedToken.substring(0, Math.min(8, feedToken.length())));
    }

    private void connectWebSocket() {
        if (isConnected) {
            log.warn("WebSocket already connected, skipping...");
            return;
        }

        try {
            log.info("Connecting to Angel One WebSocket: {}", WS_URL);
            
            webSocket = httpClient.newWebSocketBuilder()
                .header("User-Agent", "RiskPilot/2.0")
                .header("Origin", "https://smartapi.angelbroking.com")
                .buildAsync(URI.create(WS_URL), new WebSocketHandler())
                .join();

        } catch (Exception e) {
            log.error("❌ WebSocket connection failed", e);
            notificationService.sendWebSocketDisconnected();
            scheduleReconnect();
        }
    }

    private class WebSocketHandler implements WebSocket.Listener {
        
        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("✅✅✅ WebSocket OPENED - Connection established!");
            isConnected = true;
            reconnectAttempts = 0;
            authenticate(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            handleTextMessage(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            handleBinaryMessage(data);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("⚠️ WebSocket CLOSED - Status: {}, Reason: {}", statusCode, reason);
            isConnected = false;
            isAuthenticated = false;
            isSubscribed = false;
            notificationService.sendWebSocketDisconnected();
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("❌ WebSocket ERROR", error);
            isConnected = false;
            isAuthenticated = false;
            isSubscribed = false;
            notificationService.sendWebSocketDisconnected();
            scheduleReconnect();
        }
    }

    private void authenticate(WebSocket ws) {
        try {
            Map<String, Object> authMessage = Map.of(
                "action", 1,  // Authenticate action code
                "params", Map.of(
                    "jwtToken", feedToken,
                    "apiKey", apiKey,
                    "clientCode", clientCode,
                    "feedToken", feedToken
                )
            );
            
            String json = objectMapper.writeValueAsString(authMessage);
            ws.sendText(json, true);
            log.info("📤 Authentication message sent");
            
        } catch (Exception e) {
            log.error("❌ Authentication failed", e);
            ws.abort();
        }
    }

    private void subscribeToNifty(WebSocket ws) {
        try {
            Map<String, Object> subscribeMessage = Map.of(
                "action", 2,  // Subscribe action code
                "params", Map.of(
                    "mode", 3,  // SNAP_QUOTE mode (LTP + Volume + OI + Depth)
                    "tokenList", List.of(
                        Map.of(
                            "exchangeType", 1,  // NSE = 1
                            "tokens", List.of(niftyToken)
                        )
                    )
                )
            );
            
            String json = objectMapper.writeValueAsString(subscribeMessage);
            ws.sendText(json, true);
            log.info("📤 Subscription request sent for NIFTY token: {}", niftyToken);
            
        } catch (Exception e) {
            log.error("❌ Subscription failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleTextMessage(String message) {
        try {
            log.debug("📥 Text message received: {}", message);
            
            Map<String, Object> data = objectMapper.readValue(message, Map.class);
            
            // Check authentication response
            if (data.containsKey("action")) {
                String action = String.valueOf(data.get("action"));
                
                if ("1".equals(action) || "authenticate".equals(action)) {
                    boolean success = Boolean.TRUE.equals(data.get("success"));
                    if (success) {
                        log.info("✅✅✅ AUTHENTICATION SUCCESSFUL!");
                        isAuthenticated = true;
                        notificationService.sendWebSocketReconnected();
                        subscribeToNifty(webSocket);
                    } else {
                        log.error("❌ Authentication FAILED: {}", data.get("message"));
                        webSocket.abort();
                    }
                } else if ("2".equals(action) || "subscribe".equals(action)) {
                    log.info("✅ SUBSCRIPTION CONFIRMED: {}", data);
                    isSubscribed = true;
                }
            }
            
        } catch (Exception e) {
            log.error("Error parsing text message: {}", message, e);
        }
    }

    private void handleBinaryMessage(ByteBuffer buffer) {
        try {
            if (buffer.remaining() < 2) {
                log.warn("Binary message too short: {} bytes", buffer.remaining());
                return;
            }
            
            // Parse Angel One binary format
            buffer.order(ByteOrder.BIG_ENDIAN);
            
            int subscriptionMode = buffer.get() & 0xFF;
            int exchangeType = buffer.get() & 0xFF;
            
            log.debug("Binary tick - Mode: {}, Exchange: {}, Remaining: {}", 
                subscriptionMode, exchangeType, buffer.remaining());
            
            // Mode 3 = SNAP_QUOTE (full tick data)
            if (subscriptionMode == 3) {
                parseModeThreeTick(buffer);
            } else if (subscriptionMode == 2) {
                parseModeTwoTick(buffer);
            } else if (subscriptionMode == 1) {
                parseModeOneTick(buffer);
            } else {
                log.warn("Unknown subscription mode: {}", subscriptionMode);
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to parse binary tick", e);
        }
    }

    private void parseModeThreeTick(ByteBuffer buffer) {
        try {
            // Token (25 bytes)
            byte[] tokenBytes = new byte[Math.min(25, buffer.remaining())];
            buffer.get(tokenBytes);
            String token = new String(tokenBytes).trim();
            
            if (buffer.remaining() < 8) return;
            
            // LTP (8 bytes) - in paise
            long ltpPaise = buffer.getLong();
            double ltp = ltpPaise / 100.0;
            
            // Volume (8 bytes) if available
            long volume = buffer.remaining() >= 8 ? buffer.getLong() : 0;
            
            // Last trade time (8 bytes) if available
            long lastTradeTime = buffer.remaining() >= 8 ? buffer.getLong() : System.currentTimeMillis();
            
            processTick(token, ltp, volume, lastTradeTime);
            
        } catch (Exception e) {
            log.error("Error parsing Mode 3 tick", e);
        }
    }

    private void parseModeTwoTick(ByteBuffer buffer) {
        // Simplified mode - LTP only
        try {
            byte[] tokenBytes = new byte[Math.min(25, buffer.remaining())];
            buffer.get(tokenBytes);
            
            if (buffer.remaining() >= 8) {
                long ltpPaise = buffer.getLong();
                double ltp = ltpPaise / 100.0;
                processTick(new String(tokenBytes).trim(), ltp, 0, System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.error("Error parsing Mode 2 tick", e);
        }
    }

    private void parseModeOneTick(ByteBuffer buffer) {
        // Basic mode - minimal data
        parseModeTwoTick(buffer);
    }

    private void processTick(String token, double ltp, long volume, long timestamp) {
        long now = System.currentTimeMillis();
        long seq = tickCount.incrementAndGet();
        
        // Update last received time
        lastTickReceivedTime = now;
        
        // Aggregate volume
        totalVolume.addAndGet(volume);
        
        // Store last price
        lastPrice.put(token, ltp);
        
        // Feed to aggregators
        candleAggregator.processTick(timestamp, ltp, volume, seq, timestamp);
        realTimeTickAggregator.processAngelTick("NIFTY", ltp, volume);
        
        // Log periodically
        if (seq % 100 == 0) {
            log.info("📊 Tick #{} - LTP: {}, Volume: {}, Total Volume: {}", 
                seq, ltp, volume, totalVolume.get());
        }
        
        // Detailed debug log
        log.debug("✅ REAL TICK: Token={}, LTP={}, Vol={}, Time={}", 
            token, ltp, volume, timestamp);
    }

    private void healthCheck() {
        if (!isConnected || !isAuthenticated) {
            log.warn("⚠️ WebSocket UNHEALTHY - Connected: {}, Auth: {}, Subscribed: {}", 
                isConnected, isAuthenticated, isSubscribed);
            
            if (!isConnected) {
                scheduleReconnect();
            }
        } else {
            log.debug("✅ WebSocket HEALTHY - Ticks: {}, Vol: {}", 
                tickCount.get(), totalVolume.get());
        }
    }

    private void checkTickTimeout() {
        if (isAuthenticated && lastTickReceivedTime > 0) {
            long timeSinceLastTick = System.currentTimeMillis() - lastTickReceivedTime;
            
            if (timeSinceLastTick > TICK_TIMEOUT_MS) {
                log.error("🚨 NO TICKS received for {} seconds!", timeSinceLastTick / 1000);
                notificationService.sendSystemError(
                    "No ticks received for " + (timeSinceLastTick / 1000) + " seconds"
                );
                
                // Force reconnect
                if (webSocket != null) {
                    webSocket.abort();
                }
            }
        }
    }

    private void scheduleReconnect() {
        if (isConnected) return;
        
        reconnectAttempts++;
        long delay = Math.min(2000 * reconnectAttempts, MAX_RECONNECT_DELAY_MS);
        
        log.info("🔄 Scheduling reconnect in {}ms (attempt #{})", delay, reconnectAttempts);
        
        scheduler.schedule(() -> {
            log.info("Attempting reconnect #{}", reconnectAttempts);
            connectWebSocket();
        }, delay, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down WebSocket client...");
        scheduler.shutdown();
        
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Application shutdown");
            isConnected = false;
            isAuthenticated = false;
            isSubscribed = false;
        }
        
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Public monitoring methods
    public boolean isHealthy() {
        return isConnected && isAuthenticated && isSubscribed;
    }

    public long getTickCount() {
        return tickCount.get();
    }

    public long getTotalVolume() {
        return totalVolume.get();
    }

    public Map<String, Double> getLastPrices() {
        return Map.copyOf(lastPrice);
    }

    public long getTimeSinceLastTick() {
        if (lastTickReceivedTime == 0) return -1;
        return System.currentTimeMillis() - lastTickReceivedTime;
    }
}
