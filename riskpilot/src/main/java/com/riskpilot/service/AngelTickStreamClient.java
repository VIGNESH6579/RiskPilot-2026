package com.riskpilot.service;

import com.riskpilot.model.CandleEntity;
import com.riskpilot.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Refactored AngelTickStreamClient with state machine, reconnect manager, watchdog, and backoff.
 * Prevents infinite reconnect storms and ensures thread-safe lifecycle management.
 */
@Service
public class AngelTickStreamClient {
    private static final Logger log = LoggerFactory.getLogger(AngelTickStreamClient.class);

    private final CandleAggregator candleAggregator;
    private final HeartbeatMonitor heartbeatMonitor;
    private final OptionChainService optionChainService;
    private final ShadowExecutionEngine shadowExecutionEngine;
    private final MarketSessionService marketSessionService;
    private final AngelOneMarketDataService angelOneMarketDataService;
    private final CentralizedMarketDataService centralizedMarketDataService;

    private final VixService vixService;
    private final CandleRepository candleRepository;

    @Value("${TRADING_SYMBOL:NIFTY}")
    private String tradingSymbol;
    
    @Autowired private MarketDataStateService marketDataStateService;
    @Autowired private WebSocketService webSocketService;

    // State Machine
    private final AtomicReference<FeedConnectionState> state = new AtomicReference<>(FeedConnectionState.DISCONNECTED);
    private final AtomicBoolean reconnectInProgress = new AtomicBoolean(false);
    private final AtomicLong lastReconnectAttempt = new AtomicLong(0);
    private final AtomicLong reconnectCounter = new AtomicLong(0);
    private final AtomicLong consecutiveTickErrors = new AtomicLong(0);
    
    // Backoff settings
    private static final long[] BACKOFF_SCHEDULE = {1000, 2000, 5000, 10000, 30000, 60000};
    private static final long MIN_RECONNECT_COOLDOWN_MS = 10000; // 10s minimum between attempts

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactory() {
            private final AtomicLong counter = new AtomicLong(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "FeedManager-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }
    );
    
    private ScheduledFuture<?> pollerFuture;
    private LocalDateTime lastCandleSlot = null;
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    public AngelTickStreamClient(
        CandleAggregator candleAggregator,
        HeartbeatMonitor heartbeatMonitor,
        OptionChainService optionChainService,
        ShadowExecutionEngine shadowExecutionEngine,
        MarketSessionService marketSessionService,
        AngelOneMarketDataService angelOneMarketDataService,
        CentralizedMarketDataService centralizedMarketDataService,
        VixService vixService,
        CandleRepository candleRepository
    ) {
        this.candleAggregator = candleAggregator;
        this.heartbeatMonitor = heartbeatMonitor;
        this.optionChainService = optionChainService;
        this.shadowExecutionEngine = shadowExecutionEngine;
        this.marketSessionService = marketSessionService;
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.centralizedMarketDataService = centralizedMarketDataService;
        this.vixService = vixService;
        this.candleRepository = candleRepository;
    }

    @PostConstruct
    public void init() {
        log.info("🚀 Initializing AngelTickStreamClient...");
        startConnection();
    }

    @PreDestroy
    public void shutdown() {
        log.info("🛑 Shutting down AngelTickStreamClient...");
        state.set(FeedConnectionState.SHUTDOWN);
        stopPoller();
        scheduler.shutdownNow();
    }

    private synchronized void startConnection() {
        if (state.get() == FeedConnectionState.SHUTDOWN) return;
        
        if (!transitionState(FeedConnectionState.DISCONNECTED, FeedConnectionState.CONNECTING) &&
            !transitionState(FeedConnectionState.RECONNECTING, FeedConnectionState.CONNECTING)) {
            log.warn("Cannot start connection from state: {}", state.get());
            return;
        }

        try {
            stopPoller();
            
            // Start polling
            pollerFuture = scheduler.scheduleAtFixedRate(this::pollSpotAsTick, 0, 1, TimeUnit.SECONDS);
            
            transitionState(FeedConnectionState.CONNECTING, FeedConnectionState.CONNECTED);
            reconnectCounter.set(0); // Reset backoff on successful start
            log.info("✅ Feed connection established and poller started");
            
        } catch (Exception e) {
            log.error("❌ Failed to start feed connection: {}", e.getMessage());
            handleFailure();
        }
    }

    private void stopPoller() {
        if (pollerFuture != null) {
            pollerFuture.cancel(false);
            pollerFuture = null;
        }
    }

    private boolean transitionState(FeedConnectionState expected, FeedConnectionState next) {
        if (!expected.canTransitionTo(next)) {
            log.warn("⛔ INVALID_TRANSITION: {} -> {} (not allowed)", expected, next);
            return false;
        }
        boolean success = state.compareAndSet(expected, next);
        if (success) {
            log.info("🔄 STATE_TRANSITION: {} -> {}", expected, next);
        }
        return success;
    }

    private void pollSpotAsTick() {
        if (state.get() != FeedConnectionState.CONNECTED) return;

        try {
            LocalDateTime now = marketSessionService.nowIst().toLocalDateTime();
            if (!marketSessionService.isMarketOpen()) {
                return;
            }

            // Watchdog: Check for stale data
            long age = marketDataStateService.getLastTickAgeMs();
            if (age != Long.MAX_VALUE && age > 45000) {
                log.warn("⚠️ Feed stale ({}ms) - triggering recovery", age);
                handleFailure();
                return;
            }

            // BUG-FIX: Use getTradingSymbolLtp() so BANKNIFTY is fetched from cache
            // instead of always hitting NIFTY and then making a direct API call per tick.
            double spot = centralizedMarketDataService.getTradingSymbolLtp();

            if (!centralizedMarketDataService.isDataFresh()) {
                log.warn("Stale centralized data, skipping tick.");
                candleAggregator.markUnstable();
                return;
            }

            if (spot <= 0.0) {
                // Direct fallback: respect the configured trading symbol
                boolean isBankNifty = (tradingSymbol != null &&
                    "BANKNIFTY".equalsIgnoreCase(tradingSymbol.trim()));
                java.util.Optional<Double> directLtp = isBankNifty
                    ? angelOneMarketDataService.getLtp("NSE", "99926009")
                    : angelOneMarketDataService.getNiftyLtp();
                if (directLtp.isPresent() && directLtp.get() > 0.0) {
                    spot = directLtp.get();
                } else {
                    candleAggregator.markUnstable();
                    return;
                }
            }

            // Process valid tick
            marketDataStateService.updateNiftyFromWebSocket(spot, 0, 0);
            
            // BUG-FIX: Ensure VIX state is updated during tick polling
            // VixService already has internal caching/throttling
            double currentVix = vixService.getIndiaVix();
            marketDataStateService.updateVix(currentVix, java.time.Instant.now());
            
            heartbeatMonitor.registerTick();

            int candleMinute = (now.getMinute() / 5) * 5;
            LocalDateTime currentSlot = now.withMinute(candleMinute).withSecond(0).withNano(0);

            long seq = sequenceCounter.incrementAndGet();
            candleAggregator.processTick(now, spot, 1L, seq, now);

            shadowExecutionEngine.evaluateTick(spot);
            if (lastCandleSlot != null && currentSlot.isAfter(lastCandleSlot)) {
                // Candle slot just closed — persist the completed candle to DB before evaluating close
                persistClosedCandle(lastCandleSlot, spot);
                shadowExecutionEngine.evaluateCandleClose();
            }
            lastCandleSlot = currentSlot;
            
            // Reset consecutive errors on success
            consecutiveTickErrors.set(0);

        } catch (Exception e) {
            long errors = consecutiveTickErrors.incrementAndGet();
            log.error("Tick polling error (#{}): {}", errors, e.getMessage());
            candleAggregator.markUnstable();
            
            if (errors >= 10) {
                log.warn("High consecutive tick errors (10+) - broadcasting warning");
                webSocketService.sendSessionState(Map.of(
                    "type", "FEED_ERROR_CRITICAL",
                    "consecutiveErrors", errors,
                    "message", "High frequency of tick polling errors detected"
                ));
            }
        }
    }

    /**
     * Persists the just-closed 5-minute candle to the DB.
     * Called every time the candle slot rolls over so candle history survives restarts.
     */
    private void persistClosedCandle(LocalDateTime closedSlot, double lastSpot) {
        try {
            List<com.riskpilot.model.Candle> history = candleAggregator.getValidHistory();
            if (history.isEmpty()) return;

            // Find the candle whose time matches the closed slot (HH:mm or HH:mm:ss)
            String slotTime = closedSlot.toLocalTime().toString();
            if (slotTime.length() == 5) slotTime = slotTime + ":00";
            final String targetTime = slotTime;
            final String targetTimeShort = targetTime.substring(0, 5);

            com.riskpilot.model.Candle closed = history.stream()
                .filter(c -> c.time.equals(targetTime) || c.time.startsWith(targetTimeShort))
                .findFirst()
                .orElse(history.get(history.size() - 1));

            String symbol = (tradingSymbol != null && !tradingSymbol.isBlank())
                ? tradingSymbol.trim().toUpperCase() : "NIFTY";
            LocalDate date = closedSlot.toLocalDate();

            // Avoid duplicate inserts
            List<CandleEntity> existing =
                candleRepository.findBySymbolAndTimeframeAndDateOrderByTimestampAsc(symbol, 5, date);
            boolean alreadyExists = existing.stream()
                .anyMatch(e -> e.getTimestamp().equals(closedSlot));
            if (alreadyExists) {
                log.debug("Candle already persisted for slot={}, skipping", closedSlot);
                return;
            }

            BigDecimal open  = BigDecimal.valueOf(closed.open).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal high  = BigDecimal.valueOf(closed.high).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal low   = BigDecimal.valueOf(closed.low).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal closeP = BigDecimal.valueOf(closed.close).setScale(2, java.math.RoundingMode.HALF_UP);

            CandleEntity entity = CandleEntity.builder()
                .symbol(symbol)
                .date(date)
                .timestamp(closedSlot)
                .openPrice(open)
                .highPrice(high)
                .lowPrice(low)
                .closePrice(closeP)
                .volume(closed.volume())
                .range(high.subtract(low))
                .timeframe(5)
                .isBullish(closeP.compareTo(open) > 0)
                .createdAt(LocalDateTime.now())
                .build();

            candleRepository.save(entity);
            log.info("📦 Candle persisted: symbol={} slot={} O={} H={} L={} C={}",
                symbol, closedSlot, open, high, low, closeP);

        } catch (Exception e) {
            log.warn("⚠️ Failed to persist candle (non-fatal): {}", e.getMessage());
        }
    }

    public void resubscribe() {
        log.info("Manual resubscribe requested");
        handleFailure();
    }

    private void handleFailure() {
        if (state.get() == FeedConnectionState.SHUTDOWN) return;
        
        FeedConnectionState currentState = state.get();
        if (currentState == FeedConnectionState.RECONNECTING || currentState == FeedConnectionState.CONNECTING) {
            return;
        }

        state.set(FeedConnectionState.RECONNECTING);
        triggerReconnect();
    }

    private void triggerReconnect() {
        if (!reconnectInProgress.compareAndSet(false, true)) {
            log.debug("Reconnect already in progress, skipping");
            return;
        }

        long now = System.currentTimeMillis();
        long timeSinceLastAttempt = now - lastReconnectAttempt.get();
        
        if (timeSinceLastAttempt < MIN_RECONNECT_COOLDOWN_MS) {
            long wait = MIN_RECONNECT_COOLDOWN_MS - timeSinceLastAttempt;
            log.info("⏳ Reconnect cooldown active, waiting {}ms", wait);
            scheduler.schedule(this::executeReconnect, wait, TimeUnit.MILLISECONDS);
        } else {
            executeReconnect();
        }
    }

    private void executeReconnect() {
        try {
            long attempt = reconnectCounter.getAndIncrement();
            long backoff = BACKOFF_SCHEDULE[(int) Math.min(attempt, BACKOFF_SCHEDULE.length - 1)];
            
            log.info("🔄 Reconnect attempt #{} (backoff {}ms)", attempt + 1, backoff);
            lastReconnectAttempt.set(System.currentTimeMillis());
            
            stopPoller();
            
            scheduler.schedule(() -> {
                reconnectInProgress.set(false);
                startConnection();
            }, backoff, TimeUnit.MILLISECONDS);
            
        } catch (Exception e) {
            log.error("Fatal error during reconnect trigger: {}", e.getMessage());
            reconnectInProgress.set(false);
        }
    }

    public boolean isStreamActive() {
        return state.get() == FeedConnectionState.CONNECTED;
    }
}
