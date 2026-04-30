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
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final int INGEST_QUEUE_CAPACITY = 2048;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger parseFailureCounter = new AtomicInteger(0);

    /**
     * The JDK HttpClient WebSocket reader thread MUST NOT be blocked by
     * downstream work — if it is, TCP backpressure builds up, exchange
     * timestamps drift relative to receive timestamps, and the feed
     * appears stale. We hand parsed ticks off to a single-threaded
     * executor that runs validation, candle aggregation, and engine
     * evaluation on its own thread. Single thread is intentional: tick
     * processing must be strictly serial to preserve seq ordering.
     */
    private final BlockingQueue<MarketTick> ingestQueue = new ArrayBlockingQueue<>(INGEST_QUEUE_CAPACITY);
    private final ExecutorService ingestExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "angel-ingest");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong ingestDropCount = new AtomicLong(0L);
    private final AtomicBoolean ingestRunning = new AtomicBoolean(true);

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
            throw new IllegalStateException("ANGEL_CREDENTIALS_MISSING");
        }

        if (properties.getInfra().getFeed().getTransport() != MarketDataTransport.WEBSOCKET) {
            throw new IllegalStateException("WEBSOCKET_REQUIRED");
        }

        startIngestWorker();
        connectWebSocket();
    }

    private void startIngestWorker() {
        ingestExecutor.execute(() -> {
            log.info("Angel ingest worker started capacity={}", INGEST_QUEUE_CAPACITY);
            while (ingestRunning.get() || !ingestQueue.isEmpty()) {
                try {
                    MarketTick tick = ingestQueue.poll(250L, TimeUnit.MILLISECONDS);
                    if (tick == null) {
                        continue;
                    }
                    processIngestedTick(tick);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("Angel ingest worker interrupted, draining and exiting");
                    return;
                } catch (Exception other) {
                    // Defensive: a single tick failure must never kill the
                    // worker. processIngestedTick already records the
                    // failure to MarketDataStateService.
                    log.error("Angel ingest worker swallowed unexpected error reason={}", other.getMessage(), other);
                }
            }
            log.info("Angel ingest worker exited cleanly");
        });
    }

    @PreDestroy
    public void shutdown() {
        ingestRunning.set(false);
        disconnect();
        executor.shutdownNow();
        ingestExecutor.shutdown();
        try {
            if (!ingestExecutor.awaitTermination(2L, TimeUnit.SECONDS)) {
                ingestExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ingestExecutor.shutdownNow();
        }
    }

    /** Visible for tests and metrics endpoints. */
    public long getIngestDropCount() {
        return ingestDropCount.get();
    }

    /** Visible for tests and metrics endpoints. */
    public int getIngestQueueDepth() {
        return ingestQueue.size();
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
                marketDataStateService.markConnected(MarketDataTransport.WEBSOCKET);
                log.info("Connecting Angel websocket to {}", SMART_STREAM_URI);
                httpClient.newWebSocketBuilder()
                    .header("x-client-code", angelAuthService.getClientCode())
                    .header("x-feed-token", feedToken)
                    .header("x-client-lib", "JAVA")
                    .buildAsync(URI.create(SMART_STREAM_URI), new AngelWebSocketListener())
                    .exceptionally(error -> {
                        marketDataStateService.markFeedFailure(error.getMessage(), MarketDataTransport.WEBSOCKET);
                        scheduleReconnect();
                        return null;
                    });
            } catch (Exception e) {
                marketDataStateService.markFeedFailure(e.getMessage(), MarketDataTransport.WEBSOCKET);
                scheduleReconnect();
            }
        });
    }

    public void disconnect() {
        WebSocket current = this.webSocket;
        this.webSocket = null;
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

    /**
     * Hand-off point from the WebSocket reader thread. Must complete in
     * sub-millisecond time so the JDK HttpClient WS reader is free to
     * pull the next frame. Heavy work (validation, candle aggregation,
     * engine tick evaluation) runs on the {@code angel-ingest} worker
     * thread inside {@link #processIngestedTick}.
     *
     * Drop policy: if the queue is full we drop the NEWEST tick (i.e. the
     * one we are trying to add) and log+meter the drop. Dropping newest
     * is the right choice for a market data feed because older queued
     * ticks must still be processed in order to keep candle continuity
     * intact; dropping the oldest would break aggregation.
     */
    private void enqueueIngest(MarketTick tick) {
        if (!ingestQueue.offer(tick)) {
            long drops = ingestDropCount.incrementAndGet();
            // Mark stale on the aggregator so the engine refuses to
            // execute on the next tick we DO accept — we cannot trust
            // continuity once we've dropped one.
            candleAggregator.markUnstable();
            marketDataStateService.markRejectedTick("INGEST_QUEUE_FULL", tick.transport());
            // Log every 1st, 10th, 100th drop to avoid log flooding while
            // still surfacing the issue.
            if (drops <= 3 || drops % 100L == 0L) {
                log.error(
                    "INGEST_QUEUE_FULL droppedNewest seq={} totalDrops={} queueDepth={} capacity={}",
                    tick.sequenceId(),
                    drops,
                    ingestQueue.size(),
                    INGEST_QUEUE_CAPACITY
                );
            }
        }
    }

    private void processIngestedTick(MarketTick tick) {
        try {
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
            log.info(
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
            log.info(
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
                // Parsing is cheap and CPU-bound; keep it on the WS reader
                // thread so a malformed packet can't poison the ingest
                // queue. Heavy downstream work (validation, candles,
                // engine evaluation) is offloaded via enqueueIngest so the
                // WS reader is never blocked by the engine's tradeStateLock
                // or by JPA writes.
                MarketTick tick = parseTick(data);
                enqueueIngest(tick);
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
