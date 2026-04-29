package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.MarketDataException;
import com.riskpilot.exception.TradingException;
import com.riskpilot.model.MarketDataTransport;
import com.riskpilot.model.MarketTick;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrictValidationService {

    private final RiskPilotProperties properties;
    private final MarketSessionService marketSessionService;
    private final RiskEngine riskEngine;
    private final AtomicInteger dailyTradeCount = new AtomicInteger(0);
    private final AtomicInteger consecutiveLosses = new AtomicInteger(0);

    private volatile double dailyLossR = 0.0;
    private volatile LocalDate lastTradeDate;

    @PostConstruct
    public void validate() {
        validateSystem();
    }

    public void validateSystem() {
        if (!properties.isLiveMode() && !properties.isShadowMode()) {
            throw new IllegalStateException("RUNTIME_MODE_INVALID: mode must be LIVE or SHADOW");
        }
        if (properties.getRisk().getMaxTradesPerDay() < 1) {
            throw new IllegalStateException("MAX_TRADES_VIOLATION: Max trades per day must be at least 1");
        }
        if (properties.getExecution().getSlippage().getEntryMax() > 3.0) {
            throw new IllegalStateException("SLIPPAGE_VIOLATION: Entry slippage too high");
        }
        if (!properties.isStrictMode()) {
            throw new IllegalStateException("STRICT_MODE_REQUIRED: Strict mode must be enabled");
        }
        if (!properties.getInfra().getFeed().isRealTimeOnly()) {
            throw new IllegalStateException("REAL_TIME_REQUIRED: System must use real-time data only");
        }
        if (properties.isRealFeedMode()
            && properties.getInfra().getFeed().getTransport() != MarketDataTransport.WEBSOCKET) {
            throw new IllegalStateException("WEBSOCKET_REQUIRED: LIVE and SHADOW modes require Angel websocket streaming");
        }
        log.info(
            "Runtime MODE={}, DATA_SOURCE={}, marketOpen={}, enforceStrictTiming={}",
            properties.getMode(),
            properties.dataSourceLabel(),
            marketSessionService.isMarketOpen(),
            properties.isEnforceStrictTiming()
        );
    }

    public void validateTradingParameters() {
        validateSystem();
        if (properties.getExecution().getSlippage().getTp1Max() > 3.0) {
            throw new TradingException("STRICT_MODE_VIOLATION: tp1-max slippage cannot exceed 3.0");
        }
        if (properties.getExecution().getLatency().getSoftBlockMs() > 1000) {
            throw new TradingException("STRICT_MODE_VIOLATION: soft-block-ms cannot exceed 1000");
        }
    }

    public boolean canExecuteNewTrade() {
        if (!properties.isStrictMode()) {
            return true;
        }

        refreshDailyCountersIfNeeded();
        if (dailyTradeCount.get() >= properties.getRisk().getMaxTradesPerDay()) {
            return false;
        }
        if (consecutiveLosses.get() >= properties.getRisk().getMaxConsecutiveLosses()) {
            return false;
        }
        if (riskEngine.snapshot().dailyLossLimitBreached()) {
            return false;
        }
        return true;
    }

    public void recordTradeExecution(double pnlR) {
        if (!properties.isStrictMode()) {
            return;
        }

        refreshDailyCountersIfNeeded();
        dailyTradeCount.incrementAndGet();
        dailyLossR += pnlR;
        if (pnlR < 0) {
            consecutiveLosses.incrementAndGet();
        } else {
            consecutiveLosses.set(0);
        }
        lastTradeDate = marketSessionService.sessionDate(marketSessionService.now());
    }

    public void validateSlippage(String tradeType, double actualSlippage) {
        if (!properties.isStrictMode()) {
            return;
        }

        double maxAllowed = switch (tradeType.toUpperCase()) {
            case "ENTRY" -> properties.getExecution().getSlippage().getEntryMax();
            case "TP1" -> properties.getExecution().getSlippage().getTp1Max();
            case "RUNNER" -> properties.getExecution().getSlippage().getRunnerMax();
            case "PANIC_EXIT" -> properties.getExecution().getSlippage().getPanicExitMax();
            default -> Double.MAX_VALUE;
        };

        if (actualSlippage > maxAllowed && properties.getExecution().isRejectOnHighSlippage()) {
            throw new TradingException(String.format(
                "STRICT_MODE_VIOLATION: %s slippage %.2f exceeds %.2f",
                tradeType, actualSlippage, maxAllowed
            ));
        }
    }

    public void validateLatency(long actualLatencyMs) {
        if (!properties.isStrictMode()) {
            return;
        }

        var latency = properties.getExecution().getLatency();
        if (actualLatencyMs > latency.getPanicMs()) {
            throw new TradingException(String.format(
                "STRICT_MODE_VIOLATION: latency %dms exceeds panic threshold %dms",
                actualLatencyMs, latency.getPanicMs()
            ));
        }
        if (actualLatencyMs > latency.getHardBlockMs() && properties.getExecution().isRejectOnLatencyBreach()) {
            throw new TradingException(String.format(
                "STRICT_MODE_VIOLATION: latency %dms exceeds hard block threshold %dms",
                actualLatencyMs, latency.getHardBlockMs()
            ));
        }
    }

    public ValidationResult validateFreshTick(MarketTick tick) {
        if (tick == null) {
            throw new MarketDataException("LIVE_TICK_MISSING");
        }
        if (tick.exchangeTimestamp() == null) {
            throw new MarketDataException("LIVE_TICK_TIMESTAMP_MISSING");
        }
        if (tick.price() <= 0.0) {
            throw new MarketDataException("LIVE_TICK_PRICE_INVALID");
        }

        Instant now = marketSessionService.now();
        boolean marketOpen = marketSessionService.isMarketOpen(now);
        long ageMs = Math.max(0L, now.toEpochMilli() - tick.exchangeTimestamp().toEpochMilli());
        long skewMs = Math.abs(now.toEpochMilli() - tick.exchangeTimestamp().toEpochMilli());
        log.info(
            "Tick validation seq={} price={} rawExchangeTime={} parsedExchangeTime={} systemTime={} ageMs={} marketOpen={}",
            tick.sequenceId(),
            tick.price(),
            tick.rawExchangeTime(),
            marketSessionService.toMarketTime(tick.exchangeTimestamp()),
            marketSessionService.toMarketTime(now),
            ageMs,
            marketOpen
        );

        if (marketOpen && skewMs > properties.getInfra().getFeed().getMaxClockSkewMs()) {
            log.warn(
                "LIVE_REJECTED_STALE seq={} price={} rawExchangeTime={} parsedExchangeTime={} systemTime={} ageMs={} clockSkewMs={} maxClockSkewMs={}",
                tick.sequenceId(),
                tick.price(),
                tick.rawExchangeTime(),
                marketSessionService.toMarketTime(tick.exchangeTimestamp()),
                marketSessionService.toMarketTime(now),
                ageMs,
                skewMs,
                properties.getInfra().getFeed().getMaxClockSkewMs()
            );
            throw new MarketDataException("LIVE_TICK_CLOCK_SKEW");
        }

        if (marketOpen
            && properties.isEnforceStrictTiming()
            && ageMs > properties.getInfra().getFeed().getMaxSourceAgeMs()) {
            log.warn(
                "LIVE_REJECTED_STALE seq={} price={} rawExchangeTime={} parsedExchangeTime={} systemTime={} ageMs={} maxAgeMs={}",
                tick.sequenceId(),
                tick.price(),
                tick.rawExchangeTime(),
                marketSessionService.toMarketTime(tick.exchangeTimestamp()),
                marketSessionService.toMarketTime(now),
                ageMs,
                properties.getInfra().getFeed().getMaxSourceAgeMs()
            );
            throw new MarketDataException(String.format(
                "LIVE_TICK_STALE: age=%dms max=%dms",
                ageMs,
                properties.getInfra().getFeed().getMaxSourceAgeMs()
            ));
        }

        if (!marketOpen) {
            log.info(
                "AFTER_HOURS_TICK seq={} price={} rawExchangeTime={} parsedExchangeTime={} systemTime={} ageMs={}",
                tick.sequenceId(),
                tick.price(),
                tick.rawExchangeTime(),
                marketSessionService.toMarketTime(tick.exchangeTimestamp()),
                marketSessionService.toMarketTime(now),
                ageMs
            );
        } else {
            log.info(
                "LIVE_VALIDATION_PASSED seq={} price={} rawExchangeTime={} parsedExchangeTime={} systemTime={} ageMs={} allowExecution={}",
                tick.sequenceId(),
                tick.price(),
                tick.rawExchangeTime(),
                marketSessionService.toMarketTime(tick.exchangeTimestamp()),
                marketSessionService.toMarketTime(now),
                ageMs,
                true
            );
        }
        // Audit fix: preserve the ORIGINAL tick.receivedAt set by the
        // WebSocket handler when the frame first arrived. Overwriting it with
        // `now` (the validation timestamp) made the tick appear arbitrarily
        // fresh, masking real I/O and processing latency, breaking age-based
        // staleness checks downstream, and corrupting end-to-end latency
        // metrics. Validation may take milliseconds; that delay is not a
        // property of the tick itself.
        MarketTick acceptedTick = MarketTick.of(
            tick.symbol(),
            tick.price(),
            tick.exchangeTimestamp(),
            tick.receivedAt(),
            tick.transport(),
            tick.sequenceId(),
            tick.rawExchangeTime(),
            !marketOpen
        );
        return new ValidationResult(true, marketOpen, acceptedTick);
    }

    public void validateEntryExecution(double expectedEntryPrice, double actualEntryPrice, long latencyMs) {
        validateLatency(latencyMs);
        validateSlippage("ENTRY", Math.abs(actualEntryPrice - expectedEntryPrice));
    }

    public void validateExitExecution(String phase, double expectedExitPrice, double actualExitPrice, long latencyMs) {
        validateLatency(latencyMs);
        validateSlippage(phase, Math.abs(actualExitPrice - expectedExitPrice));
    }

    public void validateRegime(String currentRegime) {
        if (!properties.isStrictMode()) {
            return;
        }

        String requiredRegime = properties.getFilters().getRegimeRequired();
        if (!isAllowedRegime(requiredRegime, currentRegime)) {
            throw new TradingException(String.format(
                "STRICT_MODE_VIOLATION: regime '%s' does not match required '%s'",
                currentRegime, requiredRegime
            ));
        }
    }

    public void validateTimePhase(LocalTime currentTime) {
        // Entry window gating lives in RiskGateEngine to keep a single time-based decision path.
    }

    public TradingMetrics getDailyMetrics() {
        refreshDailyCountersIfNeeded();
        return new TradingMetrics(
            dailyTradeCount.get(),
            consecutiveLosses.get(),
            dailyLossR,
            properties.getRisk().getMaxTradesPerDay(),
            properties.getRisk().getMaxDailyLossR(),
            properties.getRisk().getMaxConsecutiveLosses(),
            properties.isStrictMode()
        );
    }

    @Scheduled(cron = "0 15 9 * * *", zone = "Asia/Kolkata")
    public void sessionStartReset() {
        refreshDailyCountersIfNeeded();
        riskEngine.resetForSessionStart();
        log.info("Daily counters reset at session start");
    }

    private void refreshDailyCountersIfNeeded() {
        LocalDate today = marketSessionService.sessionDate(marketSessionService.now());
        if (lastTradeDate == null || !lastTradeDate.equals(today)) {
            dailyTradeCount.set(0);
            consecutiveLosses.set(0);
            dailyLossR = 0.0;
            lastTradeDate = today;
        }
    }

    private boolean isAllowedRegime(String requiredRegime, String currentRegime) {
        if (requiredRegime == null || requiredRegime.isBlank() || "ALL".equalsIgnoreCase(requiredRegime)) {
            return true;
        }
        if (currentRegime == null || currentRegime.isBlank()) {
            return false;
        }

        String normalizedRequired = requiredRegime.trim().toUpperCase(Locale.ENGLISH);
        String normalizedCurrent = currentRegime.trim().toUpperCase(Locale.ENGLISH);

        return switch (normalizedRequired) {
            case "TREND_ONLY" -> "TREND".equals(normalizedCurrent);
            case "CHOP_ONLY", "RANGE_ONLY" -> "CHOP".equals(normalizedCurrent);
            case "BLOCKED_ONLY" -> "BLOCKED".equals(normalizedCurrent);
            default -> Arrays.stream(normalizedRequired.split("[,|]"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .anyMatch(normalizedCurrent::equals);
        };
    }

    public record TradingMetrics(
        int dailyTradeCount,
        int consecutiveLosses,
        double dailyLossR,
        int maxAllowedTrades,
        double maxAllowedLossR,
        int maxAllowedConsecutiveLosses,
        boolean strictMode
    ) {}

    public record ValidationResult(
        boolean valid,
        boolean allowExecution,
        MarketTick tick
    ) {}
}
