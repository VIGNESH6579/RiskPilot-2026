package com.riskpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableWebSocket
public class PlainWebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TradeDataWebSocketHandler(), "/ws")
                .setAllowedOrigins("*");
    }

    public static class TradeDataWebSocketHandler extends TextWebSocketHandler {
        
        private static final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            sessions.put(session.getId(), session);
            System.out.println("WebSocket connected: " + session.getId());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            sessions.remove(session.getId());
            System.out.println("WebSocket disconnected: " + session.getId());
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            // Handle incoming messages if needed
        }

        public static void broadcastSessionState(Object payload) {
            broadcastEvent("session", payload);
        }

        public static void broadcastTradeData(Object tradeData) {
            broadcastEvent("trade", tradeData);
        }

        public static void broadcastEvent(String eventType, Object payload) {
            if (payload == null) return;

            String message;
            try {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventType", eventType);
                envelope.put("payload", payload);
                message = OBJECT_MAPPER.writeValueAsString(envelope);
            } catch (Exception e) {
                message = "{\"error\":\"Failed to serialize trade data\"}";
            }
            final String serializedMessage = message;

            sessions.values().removeIf(session -> {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(serializedMessage));
                        return false;
                    }
                } catch (IOException e) {
                    // Remove failed session
                }
                return true;
            });
        }
    }
}
