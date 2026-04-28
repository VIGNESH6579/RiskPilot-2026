package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.AuthenticationException;
import com.riskpilot.exception.MarketDataException;
import com.riskpilot.model.MarketDataTransport;
import com.riskpilot.model.MarketTick;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class AngelTickStreamClient {
    private static final String SMART_STREAM_URI = "wss://smartapisocket.angelone.in/smart-stream";
    private static final String NIFTY_SMART_STREAM_TOKEN = "26000";
    private static final int EXCHANGE_TYPE_NSE_CM = 1;
    private static final int TOKEN_START_OFFSET = 2;
    private static final int TOKEN_LENGTH = 25;
    private static final int SEQUENCE_NUMBER_OFFSET = 27;
    private static final int EXCHANGE_FEED_TIME_OFFSET = 35;
    private static final int LAST_TRADED_PRICE_OFFSET = 43;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L;
    private static final int RAW_PACKET_LOG_BYTES = 64;

    private final CandleAggregator candleAggregator;
    private final HeartbeatMonitor heartbeatMonitor;
    private final ShadowExecutionEngine shadowExecutionEngine;
    private final StrictValidationService strictValidationService;
    private final MarketDataStateService marketDataStateService;
    private final AngelAuthService angelAuthService;
    private final RiskPilotProperties properties;
    private final MarketSessionService marketSessionService;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final CountDownLatch firstValidTickLatch = new CountDownLatch(1);

    private volatile WebSocket webSocket;

    public AngelTickStreamClient(
        CandleAggregator candleAggregator,
        HeartbeatMonitor heartbeatMonitor,
        ShadowExecutionEngine shadowExecutionEngine,
        StrictValidationService strictValidationService,
        MarketDataStateService marketDataStateService,
        AngelAuthService angelAuthService,
        RiskPilotProperties properties,
        MarketSessionService marketSessionService
    ) {
        this.candleAggregator = candleAggregator;
        this.heartbeatMonitor = heartbeatMonitor;
        this.shadowExecutionEngine = shadowExecutionEngine;
        this.strictValidationService = strictValidationService;
        this.marketDataStateService = marketDataStateService;
        this.angelAuthService = angelAuthService;
        this.properties = properties;
        this.marketSessionService = marketSessionService;
    }

    @PostConstruct
    public void init() {
        if (!angelAuthService.hasCredentials()) {
            marketDataStateService.markFeedFailure("ANGEL_CREDENTIALS_MISSING", MarketDataTransport.WEBSOCKET);
            if (properties.getInfra().getFeed().isStartupFailFast()) {
                throw new IllegalStateException("ANGEL_CREDENTIALS_MISSING");
            }
            log.warn("Angel live feed not started: credentials missing");
            return;
        }

        if (properties.getInfra().getFeed().getTransport() != MarketDataTransport.WEBSOCKET) {
            throw new IllegalStateException("WEBSOCKET_REQUIRED");
        }

        connectWebSocket();
        if (properties.getInfra().getFeed().isStartupFailFast()) {
            awaitFirstValidTick();
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (webSocket != null) {
                webSocket.abort();
            }
        } catch (Exception ignored) {
        }
        executor.shutdownNow();
    }

    private void connectWebSocket() {
        try {
            String feedToken = requireFeedToken();
            marketDataStateService.markConnected(MarketDataTransport.WEBSOCKET);
            log.info("Connecting Angel websocket to {}", SMART_STREAM_URI);
            httpClient.newWebSocketBuilder()
                .header("x-client-code", angelAuthService.getClientCode())
                .header("x-feed-token", feedToken)
                .header("x-client-lib", "JAVA")
                .buildAsync(URI.create(SMART_STREAM_URI), new AngelWebSocketListener())
                .join();
        } catch (Exception e) {
            marketDataStateService.markFeedFailure(e.getMessage(), MarketDataTransport.WEBSOCKET);
            scheduleReconnect();
        }
    }

    private void subscribeNifty() {
        WebSocket current = this.webSocket;
        if (current == null) {
            throw new AuthenticationException("ANGEL_WS_NOT_CONNECTED");
        }
        String payload = """
            {"action":1,"params":{"mode":1,"tokenList":[{"exchangeType":1,"tokens":["26000"]}]}}
            """;
        current.sendText(payload, true);
        marketDataStateService.markSubscribed(MarketDataTransport.WEBSOCKET);
        log.info("Subscribed Angel websocket to NIFTY token {}", NIFTY_SMART_STREAM_TOKEN);
    }

    private void ingestTick(MarketTick tick) {
        try {
            log.debug(
                "Ingress tick mode={} marketOpen={} transport={} seq={} price={} exchangeTs={} receivedAt={} ageMs={}",
                properties.getMode(),
                marketSessionService.isMarketOpen(),
                tick.transport(),
                tick.sequenceId(),
                tick.price(),
                tick.exchangeTimestamp(),
                tick.receivedAt(),
                tick.sourceAgeMs()
            );
            StrictValidationService.TickValidationResult validationResult = strictValidationService.validateFreshTick(tick);
            MarketTick acceptedTick = validationResult.tick();
            marketDataStateService.recordAcceptedTick(acceptedTick);
            heartbeatMonitor.registerFreshTick(acceptedTick);
            candleAggregator.processTick(acceptedTick);
            if (!validationResult.allowExecution()) {
                log.info(
                    "After-hours tick accepted for analytics seq={} price={} exchangeTs={} receiveTs={} ageMs={}",
                    acceptedTick.sequenceId(),
                    acceptedTick.price(),
                    acceptedTick.exchangeTimestamp(),
                    acceptedTick.receivedAt(),
                    acceptedTick.sourceAgeMs()
                );
            }
            shadowExecutionEngine.evaluateTick(validationResult);
            firstValidTickLatch.countDown();
            log.info(
                "INGESTION_ACCEPTED mode={} marketOpen={} allowExecution={} seq={} price={} exchangeTs={} receiveTs={} ageMs={}",
                properties.getMode(),
                marketSessionService.isMarketOpen(),
                validationResult.allowExecution(),
                acceptedTick.sequenceId(),
                acceptedTick.price(),
                acceptedTick.exchangeTimestamp(),
                acceptedTick.receivedAt(),
                acceptedTick.sourceAgeMs()
            );
        } catch (Exception e) {
            int rejected = marketDataStateService.markRejectedTick(e.getMessage(), tick.transport());
            candleAggregator.markUnstable();
            if (rejected >= properties.getInfra().getFeed().getMaxMissingTicks()) {
                log.error("SYSTEM_WARNING_CONSECUTIVE_REJECTIONS count={} lastReason={}", rejected, e.getMessage());
            }
            throw e;
        }
    }

    private MarketTick parseTick(ByteBuffer data) {
        ByteBuffer buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        byte[] tokenBytes = new byte[TOKEN_LENGTH];
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            tokenBytes[i] = buffer.get(TOKEN_START_OFFSET + i);
        }
        String token = new String(tokenBytes, StandardCharsets.UTF_8).trim().replace("\u0000", "");
        if (!NIFTY_SMART_STREAM_TOKEN.equals(token)) {
            throw new MarketDataException("ANGEL_TOKEN_MISMATCH");
        }

        long sequenceNumber = buffer.getLong(SEQUENCE_NUMBER_OFFSET);
        long exchangeFeedRaw = buffer.getLong(EXCHANGE_FEED_TIME_OFFSET);
        long exchangeFeedEpochMs = normalizeExchangeFeedEpochMs(exchangeFeedRaw);
        long rawLtp = buffer.getLong(LAST_TRADED_PRICE_OFFSET);
        if (exchangeFeedEpochMs <= 0L || rawLtp <= 0L) {
            log.warn(
                "Angel websocket payload invalid token={} seq={} rawExchangeTime={} rawLtp={} rawPacket={}",
                token,
                sequenceNumber,
                exchangeFeedRaw,
                rawLtp,
                toHexPreview(data)
            );
            throw new MarketDataException("ANGEL_WS_PAYLOAD_INVALID");
        }

        LocalDateTime exchangeTimestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(exchangeFeedEpochMs), IST);
        double price = rawLtp / 100.0;
        long nowEpochMs = System.currentTimeMillis();
        long computedAgeMs = Math.max(0L, nowEpochMs - exchangeFeedEpochMs);
        log.info(
            "Angel WS tick token={} seq={} price={} rawExchangeTime={} normalizedExchangeTime={} exchangeTs={} receiveTs={} ageMs={} rawPacket={}",
            token,
            sequenceNumber,
            price,
            exchangeFeedRaw,
            exchangeFeedEpochMs,
            exchangeTimestamp,
            Instant.ofEpochMilli(nowEpochMs).atZone(IST).toLocalDateTime(),
            computedAgeMs,
            toHexPreview(data)
        );
        return MarketTick.of(
            "NIFTY",
            price,
            exchangeTimestamp,
            LocalDateTime.now(),
            MarketDataTransport.WEBSOCKET,
            sequenceNumber,
            exchangeFeedRaw
        );
    }

    private void awaitFirstValidTick() {
        try {
            boolean received = firstValidTickLatch.await(properties.getInfra().getFeed().getStartupValidTickTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!received) {
                marketDataStateService.markHalted("FIRST_VALID_TICK_TIMEOUT", MarketDataTransport.WEBSOCKET);
                throw new IllegalStateException("FIRST_VALID_TICK_TIMEOUT");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            marketDataStateService.markHalted("FIRST_VALID_TICK_INTERRUPTED", MarketDataTransport.WEBSOCKET);
            throw new IllegalStateException("FIRST_VALID_TICK_INTERRUPTED", e);
        }
    }

    private String requireFeedToken() {
        if (!angelAuthService.authenticate() && (angelAuthService.getFeedToken() == null || angelAuthService.getFeedToken().isBlank())) {
            throw new AuthenticationException("ANGEL_FEED_AUTH_FAILED");
        }
        String feedToken = angelAuthService.getFeedToken();
        if (feedToken == null || feedToken.isBlank()) {
            throw new AuthenticationException("ANGEL_FEED_TOKEN_MISSING");
        }
        return feedToken;
    }

    private long normalizeExchangeFeedEpochMs(long rawValue) {
        if (rawValue <= 0L) {
            return rawValue;
        }
        if (rawValue < EPOCH_MILLIS_THRESHOLD) {
            long normalized = rawValue * 1000L;
            log.warn("Angel websocket exchange time looked like seconds, normalizing {} -> {}", rawValue, normalized);
            return normalized;
        }
        return rawValue;
    }

    private String toHexPreview(ByteBuffer data) {
        ByteBuffer preview = data.duplicate();
        int size = Math.min(preview.remaining(), RAW_PACKET_LOG_BYTES);
        byte[] bytes = new byte[size];
        preview.get(bytes, 0, size);
        return HexFormat.of().formatHex(bytes);
    }

    private void scheduleReconnect() {
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(() -> {
            reconnectScheduled.set(false);
            connectWebSocket();
        }, 5L, TimeUnit.SECONDS);
    }

    private final class AngelWebSocketListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            AngelTickStreamClient.this.webSocket = webSocket;
            marketDataStateService.markConnected(MarketDataTransport.WEBSOCKET);
            log.info("Angel websocket connected");
            subscribeNifty();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
                MarketTick tick = parseTick(data);
                ingestTick(tick);
            } catch (Exception e) {
                marketDataStateService.markFeedFailure(e.getMessage(), MarketDataTransport.WEBSOCKET);
                candleAggregator.markUnstable();
                log.warn("Angel websocket tick rejected: {}", e.getMessage());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            marketDataStateService.markDisconnected(reason == null || reason.isBlank() ? "ANGEL_WS_CLOSED" : reason, MarketDataTransport.WEBSOCKET);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            marketDataStateService.markFeedFailure(error.getMessage(), MarketDataTransport.WEBSOCKET);
            scheduleReconnect();
        }
    }
}
