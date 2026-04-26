package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.TradingException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrictValidationService {

    private final RiskPilotProperties properties;
    private final AtomicInteger dailyTradeCount = new AtomicInteger(0);
    private final AtomicInteger consecutiveLosses = new AtomicInteger(0);

    private volatile double dailyLossR = 0.0;
    private volatile LocalDate lastTradeDate;

    @PostConstruct
    public void validate() {
        validateSystem();
    }

    public void validateSystem() {
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
        if (!properties.getInfra().getMarketData().isFallbackDisabled()) {
            throw new IllegalStateException("FALLBACKS_DISABLED: Fallback data sources must be disabled");
        }
        if (!properties.getInfra().getMarketData().isMockDisabled()) {
            throw new IllegalStateException("MOCKS_DISABLED: Mock data sources must be disabled");
        }
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
        return !isInLatePhase(LocalTime.now());
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

    public void validateRegime(String currentRegime) {
        if (!properties.isStrictMode()) {
            return;
        }

        String requiredRegime = properties.getFilters().getRegimeRequired();
        if (!"ALL".equalsIgnoreCase(requiredRegime) && !requiredRegime.contains(currentRegime)) {
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

    public record TradingMetrics(
        int dailyTradeCount,
        int consecutiveLosses,
        double dailyLossR,
        int maxAllowedTrades,
        double maxAllowedLossR,
        int maxAllowedConsecutiveLosses,
        boolean strictMode
    ) {}
}
