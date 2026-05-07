package com.riskpilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.Arrays;
import java.util.List;

/**
 * PRODUCTION STOMP WebSocket Configuration.
 *
 * BEFORE: setAllowedOriginPatterns("*") — any site could subscribe and
 *         receive real-time trade data permanently via a WebSocket.
 *
 * AFTER:
 * - Explicit origin whitelist from CORS_ALLOWED_ORIGINS env var.
 * - Broker heartbeat (10s) to detect and evict dead connections.
 *   Without heartbeat a crashed client leaks a socket forever; on Render
 *   512 MB RAM 10 leaked sockets can cause OOM.
 * - Message size limits to prevent memory exhaustion.
 * - SockJS fallback endpoint kept for browser compatibility, but on a
 *   separate path so native WS clients use the clean /stomp endpoint.
 */
@Configuration
@EnableWebSocketMessageBroker
public class StompWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${CORS_ALLOWED_ORIGINS:https://riskpilot-2026.onrender.com}")
    private String corsAllowedOriginsRaw;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
            .setHeartbeatValue(new long[]{10_000, 10_000});
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] allowedOrigins = parseOrigins();

        // Native WebSocket (no SockJS overhead)
        registry.addEndpoint("/stomp")
            .setAllowedOrigins(allowedOrigins);

        // SockJS fallback for older browsers
        registry.addEndpoint("/stomp-sockjs")
            .setAllowedOrigins(allowedOrigins)
            .withSockJS()
                .setHeartbeatTime(10_000)
                .setDisconnectDelay(5_000);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
            .setMessageSizeLimit(64 * 1024)       // 64 KB per message
            .setSendBufferSizeLimit(512 * 1024)    // 512 KB send buffer per client
            .setSendTimeLimit(20_000);             // 20 s to drain a slow client then close it
    }

    private String[] parseOrigins() {
        List<String> origins = Arrays.stream(corsAllowedOriginsRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty() && !s.equals("*"))
            .toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                "CORS_ALLOWED_ORIGINS must be set to your Render URL. " +
                "Example: https://riskpilot-2026.onrender.com"
            );
        }
        return origins.toArray(new String[0]);
    }
}
