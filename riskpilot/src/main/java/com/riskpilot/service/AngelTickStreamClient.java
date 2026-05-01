package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.AuthenticationException;
import com.riskpilot.exception.MarketDataException;
import com.riskpilot.model.MarketDataTransport;
import com.riskpilot.model.MarketTick;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AngelTickStreamClient {
    private static final String SMART_STREAM_URI = "wss://smartapisocket.angelone.in/smart-stream";
    private static final String NIFTY_SMART_STREAM_TOKEN = "26000";
    private static final int TOKEN_START_OFFSET = 2;
    private static final int TOKEN_LENGTH = 25;
    private static final int SEQUENCE_NUMBER_OFFSET = 27;
    private static final int EXCHANGE_FEED_TIME_OFFSET = 35;
    private static final int LAST_TRADED_PRICE_OFFSET = 43;
    private static final int MIN_PACKET_LENGTH = 51;
    private static final int PACKET_TYPE_LTP = 1;
    private static final int RAW_PACKET_LOG_BYTES = 128;
    private static final long EPOCH_SECONDS_THRESHOLD = 10_000_000_000L;
    private static final long MIN_REASONABLE_EPOCH_MILLIS = 1_500_000_000_000L;
    private static final int MIN_VALID_YEAR = 2020;

    private final CandleAggregator candleAggregator;
    private final HeartbeatMonitor heartbeatMonitor;
    private final ShadowExecutionEngine shadowExecutionEngine;
    private final StrictValidationService strictValidationService;
    private final MarketDataStateService marketDataStateService;
    private final AngelAuthService angelAuthService;
    private final RiskPilotProperties properties;
    private final MarketSessionService marketSessionService;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService executor =
        Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "angel-ws-scheduler");
            t.setDaemon(true);
            return t;
        });
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger parseFailureCounter = new AtomicInteger(0);

    private volatile WebSocket webSocket;
    private volatile boolean tcpConnected = false;
    private volatile Instant lastDataTime = Instant.EPOCH;
    private volatile String streamStatus = "DISCONNECTED";
    private volatile String streamStatusText = "Stream disconnected";

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
            streamStatus = "DISCONNECTED";
            streamStatusText = "Stream disconnected - Angel credentials missing";
            if (properties.getInfra().getFeed().isStartupFailFast()) {
                throw new IllegalStateException("ANGEL_CREDENTIALS_MISSING");
            }
            log.warn("Angel credentials missing; startup continues because startup-fail-fast=false");
            return;
        }

        if (properties.getInfra().getFeed().getTransport() != MarketDataTransport.WEBSOCKET) {
            throw new IllegalStateException("WEBSOCKET_REQUIRED");
        }

        connectWebSocket();
    }

    @PreDestroy
    public void shutdown() {
        disconnect();
        executor.shutdownNow();
    }

    public void reconnectAfterAuthentication() {
        executor.execute(() -> {
            disconnect();
            connectWebSocket();
        });
    }

    private void connectWebSocket() {
        executor.execute(() -> {
            try {
                String feedToken = requireFeedToken();
                streamStatus = "CONNECTING";
                streamStatusText = "Stream connecting...";
                log.info("Connecting Angel websocket to {}", SMART_STREAM_URI);
                httpClient.newWebSocketBuilder()
                    .header("x-client-code", angelAuthService.getClientCode())
                    .header("x-feed-token", feedToken)
                    .header("x-client-lib", "JAVA")
                    .buildAsync(URI.create(SMART_STREAM_URI), new AngelWebSocketListener())
                    .exceptionally(error -> {
                        tcpConnected = false;
                        streamStatus = "DISCONNECTED";
                        streamStatusText = "Stream disconnected";
                        marketDataStateService.markFeedFailure(error.getMessage(), MarketDataTransport.WEBSOCKET);
                        scheduleReconnect();
                        return null;
                    });
            } catch (Exception e) {
                tcpConnected = false;
                streamStatus = "DISCONNECTED";
                streamStatusText = "Stream disconnected";
                marketDataStateService.markFeedFailure(e.getMessage(), MarketDataTransport.WEBSOCKET);
                scheduleReconnect();
            }
        });
    }

    public void disconnect() {
        WebSocket current = this.webSocket;
        this.webSocket = null;
        tcpConnected = false;
        streamStatus = "DISCONNECTED";
        streamStatusText = "Stream disconnected";
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
            } catch (Exception ignored) {
                current.abort();
            }
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
            lastDataTime = Instant.now();
            log.debug(
                "INGESTION_RECEIVED mode={} marketOpen={} transport={} seq={} price={} exchangeTs={} receiveTs={} ageMs={}",
                properties.getMode(),
                marketSessionService.isMarketOpen(),
                tick.transport(),
                tick.sequenceId(),
                tick.price(),
                marketSessionService.toMarketTime(tick.exchangeTimestamp()),
                marketSessionService.toMarketTime(tick.receivedAt()),
                tick.sourceAgeMs()
            );
            StrictValidationService.ValidationResult validationResult = strictValidationService.validateFreshTick(tick);
            MarketTick acceptedTick = validationResult.tick();
            marketDataStateService.recordAcceptedTick(acceptedTick);
            marketDataStateService.markReady(acceptedTick.transport());
            if (!"LIVE".equals(streamStatus)) {
                streamStatus = "LIVE";
                streamStatusText = "Live stream connected";
            }
            heartbeatMonitor.registerFreshTick(acceptedTick);
            candleAggregator.processTick(acceptedTick);
            if (!validationResult.allowExecution()) {
                log.info(
                    "After-hours tick accepted for analytics seq={} price={} exchangeTs={} receiveTs={} ageMs={}",
                    acceptedTick.sequenceId(),
                    acceptedTick.price(),
                    marketSessionService.toMarketTime(acceptedTick.exchangeTimestamp()),
                    marketSessionService.toMarketTime(acceptedTick.receivedAt()),
                    acceptedTick.sourceAgeMs()
                );
            }
            shadowExecutionEngine.evaluateTick(validationResult);
            log.debug(
                "INGESTION_ACCEPTED mode={} marketOpen={} allowExecution={} seq={} price={} exchangeTs={} receiveTs={} ageMs={}",
                properties.getMode(),
                marketSessionService.isMarketOpen(),
                validationResult.allowExecution(),
                acceptedTick.sequenceId(),
                acceptedTick.price(),
                marketSessionService.toMarketTime(acceptedTick.exchangeTimestamp()),
                marketSessionService.toMarketTime(acceptedTick.receivedAt()),
                acceptedTick.sourceAgeMs()
            );
        } catch (Exception e) {
            int rejected = marketDataStateService.markRejectedTick(e.getMessage(), tick.transport());
            candleAggregator.markUnstable();
            log.error("TICK_REJECTED reason={} price={} seq={}",
                e.getMessage(), tick.price(), tick.sequenceId());
            if (rejected >= properties.getInfra().getFeed().getMaxMissingTicks()) {
                log.error("SYSTEM_WARNING_CONSECUTIVE_REJECTIONS count={} lastReason={}", rejected, e.getMessage());
            }
            throw e;
        }
    }

    private MarketTick parseTick(ByteBuffer data) {
        byte[] payload = toPayload(data);
        String rawPacket = toHexPreview(payload);
        if (payload.length < MIN_PACKET_LENGTH) {
            throw new MarketDataException("ANGEL_WS_PACKET_TOO_SHORT");
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            int packetType = Byte.toUnsignedInt(buffer.get(0));
            if (packetType != PACKET_TYPE_LTP) {
                throw new MarketDataException("ANGEL_WS_UNSUPPORTED_PACKET_TYPE");
            }

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
            if (rawLtp <= 0L) {
                throw new MarketDataException("ANGEL_WS_PAYLOAD_INVALID");
            }

            Instant exchangeTimestamp = Instant.ofEpochMilli(exchangeFeedEpochMs);
            Instant receivedAt = Instant.now();
            double price = rawLtp / 100.0;
            long computedAgeMs = Math.max(0L, receivedAt.toEpochMilli() - exchangeFeedEpochMs);
            log.debug(
                "Angel WS tick token={} seq={} price={} rawExchangeTime={} normalizedExchangeTime={} exchangeTs={} receiveTs={} ageMs={} rawPacket={}",
                token,
                sequenceNumber,
                price,
                exchangeFeedRaw,
                exchangeFeedEpochMs,
                marketSessionService.toMarketTime(exchangeTimestamp),
                marketSessionService.toMarketTime(receivedAt),
                computedAgeMs,
                rawPacket
            );
            return MarketTick.of(
                "NIFTY",
                price,
                exchangeTimestamp,
                receivedAt,
                MarketDataTransport.WEBSOCKET,
                sequenceNumber,
                exchangeFeedRaw
            );
        } catch (MarketDataException e) {
            int failures = parseFailureCounter.incrementAndGet();
            marketDataStateService.recordParseFailure(e.getMessage(), MarketDataTransport.WEBSOCKET);
            log.error("ANGEL_WS_PARSE_FAILURE count={} reason={} rawPacket={}", failures, e.getMessage(), rawPacket, e);
            throw e;
        } catch (RuntimeException e) {
            int failures = parseFailureCounter.incrementAndGet();
            marketDataStateService.recordParseFailure("ANGEL_WS_PARSE_FAILURE", MarketDataTransport.WEBSOCKET);
            log.error("ANGEL_WS_PARSE_FAILURE count={} reason={} rawPacket={}", failures, e.getMessage(), rawPacket, e);
            throw new MarketDataException("ANGEL_WS_PARSE_FAILURE");
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
            throw new MarketDataException("INVALID_EXCHANGE_TIMESTAMP");
        }
        long epochMillis;
        if (rawValue < EPOCH_SECONDS_THRESHOLD) {
            epochMillis = rawValue * 1000L;
        } else if (rawValue < MIN_REASONABLE_EPOCH_MILLIS) {
            throw new MarketDataException("INVALID_EXCHANGE_TIMESTAMP");
        } else {
            epochMillis = rawValue;
        }
        Instant instant = Instant.ofEpochMilli(epochMillis);
        if (instant.atZone(ZoneOffset.UTC).getYear() < MIN_VALID_YEAR) {
            throw new MarketDataException("INVALID_EXCHANGE_TIMESTAMP");
        }
        return epochMillis;
    }

    private byte[] toPayload(ByteBuffer data) {
        ByteBuffer duplicate = data.duplicate();
        byte[] payload = new byte[duplicate.remaining()];
        duplicate.get(payload);
        return payload;
    }

    private String toHexPreview(byte[] payload) {
        int size = Math.min(payload.length, RAW_PACKET_LOG_BYTES);
        byte[] bytes = new byte[size];
        System.arraycopy(payload, 0, bytes, 0, size);
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

    @Scheduled(fixedRate = 5000)
    public void checkStreamHealth() {
        WebSocket current = this.webSocket;
        if (!tcpConnected || current == null) {
            streamStatus = "DISCONNECTED";
            streamStatusText = "Stream disconnected";
            marketDataStateService.markDisconnected("ANGEL_WS_DISCONNECTED", MarketDataTransport.WEBSOCKET);
            return;
        }

        long dataAgeSeconds = Duration.between(lastDataTime, Instant.now()).getSeconds();
        if (lastDataTime.equals(Instant.EPOCH)) {
            streamStatus = "CONNECTING";
            streamStatusText = "Stream connecting...";
            return;
        }
        if (dataAgeSeconds > 15) {
            streamStatus = "STALE";
            streamStatusText = "Stream stale (" + dataAgeSeconds + "s no data)";
            marketDataStateService.markFeedFailure("DATA_FLOW_STALE", MarketDataTransport.WEBSOCKET);
        } else if (dataAgeSeconds > 5) {
            streamStatus = "DELAYED";
            streamStatusText = "Stream delayed (" + dataAgeSeconds + "s)";
        } else if (!"LIVE".equals(streamStatus)) {
            streamStatus = "LIVE";
            streamStatusText = "Live stream connected";
        }
    }

    public String streamStatus() {
        return streamStatus;
    }

    public String streamStatusText() {
        return streamStatusText;
    }

    public boolean tcpConnected() {
        return tcpConnected;
    }

    public Instant lastDataTime() {
        return lastDataTime;
    }

    private final class AngelWebSocketListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            AngelTickStreamClient.this.webSocket = webSocket;
            tcpConnected = true;
            streamStatus = "CONNECTING";
            streamStatusText = "Stream connecting...";
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
                log.error("Angel websocket tick rejected reason={} rawPacket={}", e.getMessage(), toHexPreview(toPayload(data)), e);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            tcpConnected = false;
            streamStatus = "DISCONNECTED";
            streamStatusText = "Stream disconnected";
            marketDataStateService.markDisconnected(reason == null || reason.isBlank() ? "ANGEL_WS_CLOSED" : reason, MarketDataTransport.WEBSOCKET);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            tcpConnected = false;
            streamStatus = "DISCONNECTED";
            streamStatusText = "Stream disconnected";
            marketDataStateService.markFeedFailure(error.getMessage(), MarketDataTransport.WEBSOCKET);
            scheduleReconnect();
        }
    }
}
