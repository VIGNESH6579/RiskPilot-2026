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

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final AtomicInteger dailyTradeCount = new AtomicInteger(0);
    private final AtomicInteger consecutiveLosses = new AtomicInteger(0);

    private volatile double dailyLossR = 0.0;
    private volatile LocalDate lastTradeDate;

    @PostConstruct
    public void validate() {
        validateSystem();
    }

    public void validateSystem() {
        if (!properties.isLiveMode()) {
            throw new IllegalStateException("RUNTIME_MODE_INVALID: mode must be LIVE");
        }
        if (properties.getRisk().getMaxTradesPerDay() > 2) {
            throw new IllegalStateException("MAX_TRADES_VIOLATION: Max trades per day cannot exceed 2");
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
        if (properties.isLiveMode()
            && properties.getInfra().getFeed().getTransport() != MarketDataTransport.WEBSOCKET) {
            throw new IllegalStateException("WEBSOCKET_REQUIRED: LIVE mode requires Angel websocket streaming");
        }
        log.info(
            "Runtime mode={} marketOpen={} enforceStrictTiming={}",
            properties.getMode(),
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
        if (dailyLossR <= -properties.getRisk().getMaxDailyLossR()) {
            return false;
        }
        return !isInLatePhase(marketSessionService.nowIst().toLocalTime());
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
        lastTradeDate = LocalDate.now();
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

    public TickValidationResult validateFreshTick(MarketTick tick) {
        if (tick == null) {
            throw new MarketDataException("LIVE_TICK_MISSING");
        }
        if (tick.exchangeTimestamp() == null) {
            throw new MarketDataException("LIVE_TICK_TIMESTAMP_MISSING");
        }
        if (tick.price() <= 0.0) {
            throw new MarketDataException("LIVE_TICK_PRICE_INVALID");
        }

        LocalDateTime now = marketSessionService.nowIst();
        boolean marketOpen = marketSessionService.isMarketOpen(now);
        long ageMs = Math.max(0L, java.time.Duration.between(tick.exchangeTimestamp(), now).toMillis());
        long skewMs = Math.abs(java.time.Duration.between(tick.exchangeTimestamp(), now).toMillis());
        log.info(
            "Tick validation seq={} price={} rawExchangeTime={} parsedExchangeTime={} systemTime={} ageMs={} marketOpen={}",
            tick.sequenceId(),
            tick.price(),
            tick.rawExchangeTime(),
            tick.exchangeTimestamp(),
            now,
            ageMs,
            marketOpen
        );

        if (skewMs > properties.getInfra().getFeed().getMaxClockSkewMs()) {
            log.warn(
                "LIVE_REJECTED_STALE seq={} price={} rawExchangeTime={} parsedExchangeTime={} systemTime={} ageMs={} clockSkewMs={} maxClockSkewMs={}",
                tick.sequenceId(),
                tick.price(),
                tick.rawExchangeTime(),
                tick.exchangeTimestamp(),
                now,
                ageMs,
                skewMs,
                properties.getInfra().getFeed().getMaxClockSkewMs()
            );
            throw new MarketDataException("LIVE_TICK_CLOCK_SKEW");
        }

        if (properties.isEnforceStrictTiming()
            && ageMs > properties.getInfra().getFeed().getMaxSourceAgeMs()) {
            log.warn(
                "LIVE_REJECTED_STALE seq={} price={} rawExchangeTime={} parsedExchangeTime={} systemTime={} ageMs={} maxAgeMs={}",
                tick.sequenceId(),
                tick.price(),
                tick.rawExchangeTime(),
                tick.exchangeTimestamp(),
                now,
                ageMs,
                properties.getInfra().getFeed().getMaxSourceAgeMs()
            );
            throw new MarketDataException(String.format(
                "LIVE_TICK_STALE: age=%dms max=%dms",
                ageMs,
                properties.getInfra().getFeed().getMaxSourceAgeMs()
            ));
        }

        log.info(
            "LIVE_VALIDATION_PASSED seq={} price={} rawExchangeTime={} parsedExchangeTime={} systemTime={} ageMs={} allowExecution={}",
            tick.sequenceId(),
            tick.price(),
            tick.rawExchangeTime(),
            tick.exchangeTimestamp(),
            now,
            ageMs,
            marketOpen
        );
        MarketTick acceptedTick = MarketTick.of(
            tick.symbol(),
            tick.price(),
            tick.exchangeTimestamp(),
            now,
            tick.transport(),
            tick.sequenceId(),
            tick.rawExchangeTime()
        );
        return new TickValidationResult(acceptedTick, marketOpen);
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
        if (!properties.isStrictMode()) {
            return;
        }
        if (isInLatePhase(currentTime) && !properties.getTimePhase().getLate().getAllowNewTrades()) {
            throw new TradingException("STRICT_MODE_VIOLATION: new trades are blocked in late phase");
        }
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

    @Scheduled(cron = "0 1 0 * * *", zone = "Asia/Kolkata")
    public void midnightReset() {
        refreshDailyCountersIfNeeded();
        log.info("Daily counters reset at midnight");
    }

    private void refreshDailyCountersIfNeeded() {
        LocalDate today = LocalDate.now();
        if (lastTradeDate == null || !lastTradeDate.equals(today)) {
            dailyTradeCount.set(0);
            consecutiveLosses.set(0);
            dailyLossR = 0.0;
            lastTradeDate = today;
        }
    }

    private boolean isInLatePhase(LocalTime time) {
        return !time.isBefore(LocalTime.parse(properties.getTimePhase().getLate().getStart()))
            && time.isBefore(LocalTime.parse(properties.getTimePhase().getLate().getEnd()));
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

    public record TickValidationResult(
        MarketTick tick,
        boolean allowExecution
    ) {}
}
