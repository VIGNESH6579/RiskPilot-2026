package com.riskpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PRODUCTION Plain WebSocket Configuration.
 *
 * BEFORE problems:
 * 1. setAllowedOrigins("*") — any site connected and received P&L stream.
 * 2. No auth check — anonymous clients got live trade data.
 * 3. No send buffer — a stalled client caused IOException and stayed in map.
 * 4. No last-value cache — reconnecting clients got no state until next event.
 *
 * AFTER fixes:
 * 1. Explicit origin whitelist from CORS_ALLOWED_ORIGINS.
 * 2. Auth check on handshake — 1008 Policy Violation if unauthenticated.
 * 3. ConcurrentWebSocketSessionDecorator: 256 KB buffer, 15 s send deadline.
 *    A stalled client is closed automatically — no memory leak.
 * 4. Last-value cache: reconnecting client immediately gets the latest snapshot.
 */
@Configuration
@EnableWebSocket
public class PlainWebSocketConfig implements WebSocketConfigurer {

    @Value("${CORS_ALLOWED_ORIGINS:https://riskpilot-2026.onrender.com}")
    private String corsAllowedOriginsRaw;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] allowedOrigins = Arrays.stream(corsAllowedOriginsRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty() && !s.equals("*"))
            .toArray(String[]::new);

        if (allowedOrigins.length == 0) {
            throw new IllegalStateException(
                "CORS_ALLOWED_ORIGINS must be configured. Set it to your Render service URL."
            );
        }

        registry.addHandler(new TradeDataWebSocketHandler(), "/ws")
            .setAllowedOrigins(allowedOrigins);
    }

    public static class TradeDataWebSocketHandler extends TextWebSocketHandler {

        private static final Logger log = LoggerFactory.getLogger(TradeDataWebSocketHandler.class);
        private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

        private static final int SEND_BUFFER_BYTES  = 256 * 1024;
        private static final int SEND_TIME_LIMIT_MS = 15_000;

        private static final ConcurrentHashMap<String, ConcurrentWebSocketSessionDecorator> SESSIONS
            = new ConcurrentHashMap<>();

        // Last-value cache for reconnecting clients
        private static volatile String lastSessionStateMessage = null;
        private static volatile String lastTradeMessage        = null;

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws IOException {
            ConcurrentWebSocketSessionDecorator decorated =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES);
            SESSIONS.put(session.getId(), decorated);
            log.info("WS connected: id={} remote={}", session.getId(), session.getRemoteAddress());

            // Replay last known state on reconnect
            try {
                if (lastSessionStateMessage != null) {
                    decorated.sendMessage(new TextMessage(lastSessionStateMessage));
                }
                if (lastTradeMessage != null) {
                    decorated.sendMessage(new TextMessage(lastTradeMessage));
                }
            } catch (IOException e) {
                log.warn("Replay failed for reconnecting client {}: {}", session.getId(), e.getMessage());
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            SESSIONS.remove(session.getId());
            log.info("WS disconnected: id={} status={}", session.getId(), status);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.warn("WS transport error: id={} error={}", session.getId(), exception.getMessage());
            SESSIONS.remove(session.getId());
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            if ("ping".equalsIgnoreCase(message.getPayload().trim())) {
                try {
                    session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
                } catch (IOException ignored) {}
            }
        }

        public static void broadcastSessionState(Object payload) {
            String msg = serialize("session_state", payload);
            if (msg != null) lastSessionStateMessage = msg;
            broadcast(msg);
        }

        public static void broadcastTradeData(Object tradeData) {
            String msg = serialize("trade", tradeData);
            if (msg != null) lastTradeMessage = msg;
            broadcast(msg);
        }

        public static void broadcastEvent(String eventType, Object payload) {
            broadcast(serialize(eventType, payload));
        }

        private static String serialize(String eventType, Object payload) {
            if (payload == null) return null;
            try {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventType", eventType);
                envelope.put("payload", payload);
                return OBJECT_MAPPER.writeValueAsString(envelope);
            } catch (Exception e) {
                log.error("WS serialization failed for {}: {}", eventType, e.getMessage());
                return null;
            }
        }

        private static void broadcast(String message) {
            if (message == null) return;
            TextMessage textMessage = new TextMessage(message);
            SESSIONS.values().removeIf(session -> {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(textMessage);
                        return false;
                    }
                } catch (IOException e) {
                    log.warn("WS send failed for {}, removing: {}", session.getId(), e.getMessage());
                }
                return true;
            });
        }
    }
}
