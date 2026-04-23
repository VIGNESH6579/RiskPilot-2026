package com.riskpilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
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

        public static void broadcastTradeData(Object tradeData) {
            if (tradeData == null) return;
            
            String message;
            try {
                // Convert to JSON string properly
                if (tradeData instanceof Map) {
                    // Simple JSON conversion for Map objects
                    message = mapToJson((Map<String, Object>) tradeData);
                } else {
                    message = tradeData.toString();
                }
            } catch (Exception e) {
                message = "{\"error\":\"Failed to serialize trade data\"}";
            }

            sessions.values().removeIf(session -> {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(message));
                        return false;
                    }
                } catch (IOException e) {
                    // Remove failed session
                }
                return true;
            });
        }

        private static String mapToJson(Map<String, Object> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value instanceof String) {
                    json.append("\"").append(value).append("\"");
                } else if (value instanceof Boolean) {
                    json.append(value);
                } else {
                    json.append(value);
                }
                first = false;
            }
            json.append("}");
            return json.toString();
        }
    }
}
