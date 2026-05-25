package com.riskpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskpilot.model.CandleEntity;
import com.riskpilot.repository.CandleRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Angel One SmartAPI WebSocket client.
 *
 * Replaces AngelTickStreamClient's 1-second REST poll loop with a real persistent
 * WebSocket connection to Angel One's SmartStream endpoint. Ticks arrive as they
 * happen at the exchange (~every 0.1–0.5s during trading hours) instead of being
 * artificially throttled by a 1s scheduler.
 *
 * ── Protocol ──────────────────────────────────────────────────────────────────
 * Endpoint : wss://smartapisocket.angelone.in/smart-stream
 * Auth     : Three HTTP headers on the upgrade request:
 *              Authorization: <jwtToken>
 *              x-api-key:     <apiKey>
 *              x-client-code: <clientCode>
 *              x-feed-token:  <feedToken>
 *
 * Subscribe (text JSON after connect):
 * {
 *   "correlationID": "riskpilot_1",
 *   "action": 1,
 *   "params": { "mode": 2, "tokenList": [{ "exchangeType": 1, "tokens": ["99926000"] }] }
 * }
 * action=1 = subscribe, mode=2 = QUOTE (OHLCV), exchangeType=1 = NSE
 *
 * Heartbeat: server sends text "pong" every ~30s; client MUST reply "ping" every 25s
 *            or the server closes the connection.
 *
 * Response (binary, Little-Endian):
 *  Offset  Len  Type    Field
 *   0       1   u8      subscription_mode
 *   1       1   u8      exchange_type
 *   2      25   char[]  token (null-padded ASCII)
 *  27       4   u32     sequence_number
 *  31       8   u64     exchange_timestamp_ms  (epoch ms)
 *  39       8   i64     ltp         (price × 100, divide to get float)
 *  47       8   i64     last_qty
 *  55       8   i64     avg_price   (price × 100)
 *  63       8   i64     volume
 *  71       8   i64     total_buy_qty
 *  79       8   i64     total_sell_qty
 *  87       8   i64     open_price  (price × 100)
 *  95       8   i64     high_price  (price × 100)
 * 103       8   i64     low_price   (price × 100)
 * 111       8   i64     close_price (price × 100)
 * Total: 119 bytes for QUOTE mode.
 */
@Slf4j
@Service
public class AngelSmartStreamClient {

    // ── Angel One SmartAPI constants ───────────────────────────────────────────
    private static final String WS_URL          = "wss://smartapisocket.angelone.in/smart-stream";
    private static final String NIFTY_TOKEN     = "99926000";
    private static final String BANKNIFTY_TOKEN = "99926009";
    private static final int    MODE_QUOTE      = 2;   // OHLCV ticks
    private static final int    EXCHANGE_NSE    = 1;
    private static final int    QUOTE_FRAME_LEN = 119; // bytes per QUOTE frame

    // ── Timing ─────────────────────────────────────────────────────────────────
    private static final long PING_INTERVAL_MS  = 25_000L;  // send ping every 25s
    private static final long PONG_TIMEOUT_MS   = 70_000L;  // reconnect if no pong for 70s
    private static final long[] BACKOFF_MS      = {2_000, 5_000, 10_000, 30_000, 60_000};

    // ── Dependencies ───────────────────────────────────────────────────────────
    private final AngelAuthService         angelAuthService;
    private final AngelSessionManager      angelSessionManager;
    private final CandleAggregator         candleAggregator;
    private final HeartbeatMonitor         heartbeatMonitor;
    private final ShadowExecutionEngine    shadowExecutionEngine;
    private final MarketSessionService     marketSessionService;
    private final MarketDataStateService   marketDataStateService;
    private final CandleRepository         candleRepository;
    private final VixService               vixService;

    @Autowired private WebSocketService webSocketService;

    @Value("${TRADING_SYMBOL:NIFTY}")
    private String tradingSymbol;

    // ── State ──────────────────────────────────────────────────────────────────
    private final ObjectMapper              mapper         = new ObjectMapper();
    private final AtomicReference<WebSocket> activeSocket  = new AtomicReference<>();
    private final AtomicBoolean             running        = new AtomicBoolean(false);
    private final AtomicBoolean             connected      = new AtomicBoolean(false);
    private final AtomicLong                reconnectCount = new AtomicLong(0);
    private final AtomicLong                lastPongMs     = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong                tickSeq        = new AtomicLong(0);

    // Candle slot tracking for close detection
    private volatile LocalDateTime lastCandleSlot = null;

    // Scheduler: ping + watchdog + reconnect delays
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SmartStream-Mgr");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pingTask;
    private ScheduledFuture<?> watchdogTask;

    // ── Constructor ────────────────────────────────────────────────────────────

    public AngelSmartStreamClient(
            AngelAuthService       angelAuthService,
            AngelSessionManager    angelSessionManager,
            CandleAggregator       candleAggregator,
            HeartbeatMonitor       heartbeatMonitor,
            ShadowExecutionEngine  shadowExecutionEngine,
            MarketSessionService   marketSessionService,
            MarketDataStateService marketDataStateService,
            CandleRepository       candleRepository,
            VixService             vixService) {
        this.angelAuthService    = angelAuthService;
        this.angelSessionManager = angelSessionManager;
        this.candleAggregator    = candleAggregator;
        this.heartbeatMonitor    = heartbeatMonitor;
        this.shadowExecutionEngine = shadowExecutionEngine;
        this.marketSessionService  = marketSessionService;
        this.marketDataStateService = marketDataStateService;
        this.candleRepository    = candleRepository;
        this.vixService          = vixService;
    }

    @PostConstruct
    public void init() {
        running.set(true);
        log.info("🌐 AngelSmartStreamClient initializing — pure WebSocket feed");
        connect();
        startWatchdog();
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        stopPing();
        if (watchdogTask != null) watchdogTask.cancel(false);
        scheduler.shutdownNow();
        closeSocket("shutdown");
        log.info("🛑 AngelSmartStreamClient stopped");
    }

    // ── Connection lifecycle ───────────────────────────────────────────────────

    private void connect() {
        if (!running.get()) return;

        try {
            // Ensure we have a valid session before attempting WS
            if (!angelSessionManager.isSessionValid()) {
                log.warn("⚠️ Session invalid — skipping SmartStream connect, scheduling retry");
                scheduleReconnect();
                return;
            }

            String jwt        = angelAuthService.getJwtToken();
            String feedToken  = angelAuthService.getFeedToken();
            String clientCode = angelAuthService.getClientCode();
            String apiKey     = angelAuthService.getApiKey();

            if (jwt == null || jwt.isBlank() || feedToken == null || feedToken.isBlank()) {
                log.warn("⚠️ Missing jwt or feedToken — scheduling retry");
                scheduleReconnect();
                return;
            }

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();

            WebSocket ws = client.newWebSocketBuilder()
                .header("Authorization", jwt)
                .header("x-api-key",     apiKey     != null ? apiKey     : "")
                .header("x-client-code", clientCode != null ? clientCode : "")
                .header("x-feed-token",  feedToken)
                .buildAsync(URI.create(WS_URL), new SmartStreamListener())
                .get(15, TimeUnit.SECONDS);

            closeSocket("replaced by new connection");  // close any stale socket
            activeSocket.set(ws);
            connected.set(true);
            reconnectCount.set(0);
            lastPongMs.set(System.currentTimeMillis());

            log.info("✅ SmartStream WebSocket connected (feedToken present)");

            // Subscribe to market data immediately
            sendSubscribe(ws);

            // Start heartbeat ping loop
            startPing(ws);

        } catch (Exception e) {
            log.error("❌ SmartStream connect failed: {}", e.getMessage());
            connected.set(false);
            scheduleReconnect();
        }
    }

    private void sendSubscribe(WebSocket ws) {
        try {
            boolean isBankNifty = "BANKNIFTY".equalsIgnoreCase(
                tradingSymbol != null ? tradingSymbol.trim() : "");
            String token = isBankNifty ? BANKNIFTY_TOKEN : NIFTY_TOKEN;

            Map<String, Object> tokenEntry = new HashMap<>();
            tokenEntry.put("exchangeType", EXCHANGE_NSE);
            tokenEntry.put("tokens", List.of(token));

            Map<String, Object> params = new HashMap<>();
            params.put("mode", MODE_QUOTE);
            params.put("tokenList", List.of(tokenEntry));

            Map<String, Object> msg = new HashMap<>();
            msg.put("correlationID", "riskpilot_1");
            msg.put("action", 1);  // 1 = subscribe
            msg.put("params", params);

            ws.sendText(mapper.writeValueAsString(msg), true);
            log.info("📡 SmartStream subscribed: symbol={} token={}", tradingSymbol, token);
        } catch (Exception e) {
            log.error("❌ Failed to send subscribe message: {}", e.getMessage());
        }
    }

    private void startPing(WebSocket ws) {
        stopPing();
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            if (!connected.get()) return;
            try {
                ws.sendText("ping", true);
                log.debug("→ ping");
            } catch (Exception e) {
                log.warn("Ping failed: {} — triggering reconnect", e.getMessage());
                triggerReconnect();
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPing() {
        if (pingTask != null && !pingTask.isCancelled()) {
            pingTask.cancel(false);
            pingTask = null;
        }
    }

    private void startWatchdog() {
        watchdogTask = scheduler.scheduleAtFixedRate(() -> {
            if (!connected.get()) return;
            long silenceMs = System.currentTimeMillis() - lastPongMs.get();
            if (silenceMs > PONG_TIMEOUT_MS) {
                log.warn("⚠️ SmartStream silent for {}ms (no pong) — reconnecting", silenceMs);
                triggerReconnect();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void triggerReconnect() {
        if (!running.get()) return;
        connected.set(false);
        stopPing();
        closeSocket("reconnect triggered");
        candleAggregator.markUnstable();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        long attempt = reconnectCount.getAndIncrement();
        long delayMs = BACKOFF_MS[(int) Math.min(attempt, BACKOFF_MS.length - 1)];
        log.info("⏳ SmartStream reconnect #{} scheduled in {}ms", attempt + 1, delayMs);
        scheduler.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
    }

    private void closeSocket(String reason) {
        WebSocket ws = activeSocket.getAndSet(null);
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, reason); } catch (Exception ignored) {}
        }
    }

    // ── WebSocket listener ─────────────────────────────────────────────────────

    private class SmartStreamListener implements WebSocket.Listener {

        // Accumulate binary fragments before processing
        private ByteBuffer fragmentBuf = null;

        @Override
        public void onOpen(WebSocket ws) {
            log.info("🔗 SmartStream onOpen");
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            ws.request(1);
            String text = data.toString().trim();
            if ("pong".equalsIgnoreCase(text)) {
                lastPongMs.set(System.currentTimeMillis());
                log.debug("← pong");
            } else {
                // Could be a JSON error/ack message from the server
                log.debug("← text: {}", text);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer rawData, boolean last) {
            ws.request(1);
            try {
                // Copy incoming data (ByteBuffer position/limit may be reused by JDK)
                byte[] chunk = new byte[rawData.remaining()];
                rawData.get(chunk);

                if (!last) {
                    // Fragment: accumulate
                    if (fragmentBuf == null) {
                        fragmentBuf = ByteBuffer.allocate(chunk.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                    }
                    // Grow if needed
                    if (fragmentBuf.remaining() < chunk.length) {
                        ByteBuffer grown = ByteBuffer.allocate(fragmentBuf.capacity() * 2)
                            .order(ByteOrder.LITTLE_ENDIAN);
                        fragmentBuf.flip();
                        grown.put(fragmentBuf);
                        fragmentBuf = grown;
                    }
                    fragmentBuf.put(chunk);
                    return null;
                }

                // Last fragment (or complete single frame)
                ByteBuffer frame;
                if (fragmentBuf != null) {
                    fragmentBuf.put(chunk);
                    fragmentBuf.flip();
                    frame = fragmentBuf;
                    fragmentBuf = null;
                } else {
                    frame = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
                }

                processBinaryFrame(frame);

            } catch (Exception e) {
                log.warn("Binary frame error: {}", e.getMessage());
                fragmentBuf = null;  // reset accumulator on error
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("⚠️ SmartStream closed — code={} reason={}", statusCode, reason);
            connected.set(false);
            candleAggregator.markUnstable();
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("❌ SmartStream error: {}", error.getMessage());
            connected.set(false);
            candleAggregator.markUnstable();
            scheduleReconnect();
        }
    }

    // ── Binary frame decoder ───────────────────────────────────────────────────

    private void processBinaryFrame(ByteBuffer buf) {
        if (buf.remaining() < QUOTE_FRAME_LEN) {
            log.debug("Short frame {} bytes (need {})", buf.remaining(), QUOTE_FRAME_LEN);
            return;
        }

        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Offsets per Angel One SmartAPI protocol spec
        // byte 0: subscription_mode, byte 1: exchange_type — read but not used further
        long seqNum      = Integer.toUnsignedLong(buf.getInt(27));
        long exchangeTsMs = buf.getLong(31);
        double ltp       = buf.getLong(39)  / 100.0;
        long   volume    = buf.getLong(63);
        double open      = buf.getLong(87)  / 100.0;
        double high      = buf.getLong(95)  / 100.0;
        double low       = buf.getLong(103) / 100.0;
        double close     = buf.getLong(111) / 100.0;

        if (ltp <= 0.0) {
            log.debug("Zero LTP in frame — skipping");
            return;
        }

        // Resolve tick timestamp: use exchange timestamp when valid
        LocalDateTime tickTime;
        long nowMs = System.currentTimeMillis();
        if (exchangeTsMs > 1_000_000_000_000L && exchangeTsMs <= nowMs + 5_000L) {
            // Plausible epoch-ms timestamp (after year 2001, not more than 5s in future)
            tickTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(exchangeTsMs),
                ZoneId.of("Asia/Kolkata"));
        } else {
            tickTime = marketSessionService.nowIst().toLocalDateTime();
        }

        // Ignore ticks outside market session
        if (!marketSessionService.isMarketOpen()) {
            return;
        }

        // ── Feed state update (PRIMARY WebSocket source) ───────────────────────
        marketDataStateService.updateNiftyFromWebSocket(ltp, 0, 0);

        // ── VIX: still REST-based, VixService throttles internally to 5-min cache ─
        try {
            double vix = vixService.getIndiaVix();
            marketDataStateService.updateVix(vix, Instant.now());
        } catch (Exception e) {
            // VIX unavailable — TradingSafetyManager handles the block
            log.debug("VIX fetch skipped this tick: {}", e.getMessage());
        }

        // ── Heartbeat ─────────────────────────────────────────────────────────
        heartbeatMonitor.registerTick();

        // ── Candle aggregation ────────────────────────────────────────────────
        long seq = tickSeq.incrementAndGet();
        candleAggregator.processTick(tickTime, ltp, volume, seq, LocalDateTime.now());

        // ── Engine evaluation ─────────────────────────────────────────────────
        shadowExecutionEngine.evaluateTick(ltp);

        // ── Candle slot rollover detection ────────────────────────────────────
        int slotMin = (tickTime.getMinute() / 5) * 5;
        LocalDateTime currentSlot = tickTime.withMinute(slotMin).withSecond(0).withNano(0);
        LocalDateTime prevSlot    = lastCandleSlot;

        if (prevSlot != null && currentSlot.isAfter(prevSlot)) {
            // A new 5-minute slot just started — persist and evaluate the candle that just closed
            persistClosedCandle(prevSlot, ltp, open, high, low, close, volume);
            shadowExecutionEngine.evaluateCandleClose();
        }
        lastCandleSlot = currentSlot;

        log.debug("← WS ltp={} O={} H={} L={} C={} vol={} seq={}", ltp, open, high, low, close, volume, seqNum);
    }

    // ── Candle persistence ─────────────────────────────────────────────────────

    private void persistClosedCandle(LocalDateTime slot,
                                     double lastLtp,
                                     double wsOpen, double wsHigh, double wsLow, double wsClose,
                                     long wsVolume) {
        try {
            String symbol = tradingSymbol != null && !tradingSymbol.isBlank()
                ? tradingSymbol.trim().toUpperCase() : "NIFTY";
            LocalDate date = slot.toLocalDate();

            // Deduplicate
            List<CandleEntity> existing =
                candleRepository.findBySymbolAndTimeframeAndDateOrderByTimestampAsc(symbol, 5, date);
            if (existing.stream().anyMatch(e -> e.getTimestamp().equals(slot))) {
                log.debug("Candle slot {} already persisted — skip", slot);
                return;
            }

            // Some feeds send 0 for OHLC fields on first few ticks — fall back to LTP
            double effectiveClose = wsClose > 0 ? wsClose : lastLtp;
            double effectiveOpen  = wsOpen  > 0 ? wsOpen  : effectiveClose;
            double effectiveHigh  = wsHigh  > 0 ? wsHigh  : effectiveClose;
            double effectiveLow   = wsLow   > 0 ? wsLow   : effectiveClose;

            BigDecimal bdOpen  = round(effectiveOpen);
            BigDecimal bdHigh  = round(effectiveHigh);
            BigDecimal bdLow   = round(effectiveLow);
            BigDecimal bdClose = round(effectiveClose);

            CandleEntity entity = CandleEntity.builder()
                .symbol(symbol)
                .date(date)
                .timestamp(slot)
                .openPrice(bdOpen)
                .highPrice(bdHigh)
                .lowPrice(bdLow)
                .closePrice(bdClose)
                .volume(wsVolume)
                .range(bdHigh.subtract(bdLow))
                .timeframe(5)
                .isBullish(bdClose.compareTo(bdOpen) > 0)
                .createdAt(LocalDateTime.now())
                .build();

            candleRepository.save(entity);
            log.info("📦 WS candle persisted: {} O={} H={} L={} C={} vol={}",
                slot, bdOpen, bdHigh, bdLow, bdClose, wsVolume);

        } catch (Exception e) {
            log.warn("⚠️ WS candle persist failed (non-fatal): {}", e.getMessage());
        }
    }

    private static BigDecimal round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    // ── Public status ──────────────────────────────────────────────────────────

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Force a reconnect. Called by AngelTickStreamClient.resubscribe() and
     * any monitoring endpoint that needs to reset the feed manually.
     */
    public void resubscribe() {
        log.info("🔄 Manual resubscribe requested — triggering reconnect");
        triggerReconnect();
    }
}
