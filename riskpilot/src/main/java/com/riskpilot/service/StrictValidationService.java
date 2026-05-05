package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.TradingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrictValidationService {

    private final RiskPilotProperties properties;
    private final Environment environment;
    private final AtomicInteger dailyTradeCount = new AtomicInteger(0);
    private final AtomicInteger consecutiveLosses = new AtomicInteger(0);
    private volatile double dailyLossR = 0.0;
    private volatile LocalDateTime lastTradeDate;

    public void validateSystem() {
        validate();
    }

    @PostConstruct
    public void validate() {
        boolean productionProfile = isProductionProfile();
        if (!productionProfile) {
            log.warn("STRICT_STARTUP_REALTIME_CHECKS_SKIPPED: active profile is non-production");
            return;
        }
        log.info("🔒 STRICT VALIDATION STARTUP");
        
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
        
        if (!"angelone-live".equals(properties.getInfra().getFeed().getDataSource())) {
            throw new IllegalStateException("LIVE_FEED_REQUIRED: Only Angel One live feed allowed");
        }
        
        if (!properties.getInfra().getMarketData().isFallbackDisabled()) {
            throw new IllegalStateException("FALLBACKS_DISABLED: Fallback data sources must be disabled");
        }
        
        if (!properties.getInfra().getMarketData().isMockDisabled()) {
            throw new IllegalStateException("MOCKS_DISABLED: Mock data sources must be disabled");
        }
        
        log.info("✅ STRICT VALIDATION PASSED - REAL-TIME ONLY MODE");
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
    }

    public void validateTradingParameters() {
        log.info("🔒 STRICT MODE: Validating trading parameters against doctrine");
        
        if (properties.getRisk().getMaxTradesPerDay() > 2) {
            throw new TradingException("STRICT_MODE_VIOLATION: max-trades-per-day cannot exceed 2. Current: " + 
                properties.getRisk().getMaxTradesPerDay());
        }
        
        var slippage = properties.getExecution().getSlippage();
        if (slippage.getEntryMax() > 2.0) {
            throw new TradingException("STRICT_MODE_VIOLATION: entry-max slippage cannot exceed 2.0. Current: " + 
                slippage.getEntryMax());
        }
        
        if (slippage.getTp1Max() > 3.0) {
            throw new TradingException("STRICT_MODE_VIOLATION: tp1-max slippage cannot exceed 3.0. Current: " + 
                slippage.getTp1Max());
        }
        
        var latency = properties.getExecution().getLatency();
        if (latency.getSoftBlockMs() > 1000) {
            throw new TradingException("STRICT_MODE_VIOLATION: soft-block-ms cannot exceed 1000. Current: " + 
                latency.getSoftBlockMs());
        }
        
        log.info("✅ Trading parameters validation passed");
    }

    public boolean canExecuteNewTrade() {
        if (!properties.isStrictMode()) return true;

        LocalDateTime now = LocalDateTime.now();
        
        if (lastTradeDate == null || now.toLocalDate().isAfter(lastTradeDate.toLocalDate())) {
            dailyTradeCount.set(0);
            consecutiveLosses.set(0);
            dailyLossR = 0.0;
            lastTradeDate = now;
            log.info("📅 Daily trading counters reset for new day");
        }

        if (dailyTradeCount.get() >= properties.getRisk().getMaxTradesPerDay()) {
            // BUG-FIX: Use {} placeholders throughout — never mix {} with %.2f/%d
            log.warn("🚫 TRADE REJECTED: Daily trade limit reached ({}/{})", 
                dailyTradeCount.get(), properties.getRisk().getMaxTradesPerDay());
            return false;
        }

        if (consecutiveLosses.get() >= properties.getRisk().getMaxConsecutiveLosses()) {
            log.warn("🚫 TRADE REJECTED: Consecutive loss limit reached ({}/{})", 
                consecutiveLosses.get(), properties.getRisk().getMaxConsecutiveLosses());
            return false;
        }

        if (dailyLossR <= -properties.getRisk().getMaxDailyLossR()) {
            log.warn("🚫 TRADE REJECTED: Daily loss limit reached ({}R / max {}R)", 
                dailyLossR, properties.getRisk().getMaxDailyLossR());
            return false;
        }

        LocalTime currentTime = now.toLocalTime();
        var timePhase = properties.getTimePhase();
        
        if (isInLatePhase(currentTime, timePhase) && !timePhase.getLate().getAllowNewTrades()) {
            log.warn("🚫 TRADE REJECTED: New trades not allowed in late phase");
            return false;
        }

        return true;
    }

    public void recordTradeExecution(double pnl) {
        if (!properties.isStrictMode()) return;

        dailyTradeCount.incrementAndGet();
        
        if (pnl < 0) {
            consecutiveLosses.incrementAndGet();
            dailyLossR += pnl;
            log.warn("📉 LOSS RECORDED: {}R, consecutive losses: {}, daily loss: {}R", 
                pnl, consecutiveLosses.get(), dailyLossR);
        } else {
            consecutiveLosses.set(0);
            dailyLossR += pnl;
            log.info("📈 PROFIT RECORDED: {}R, consecutive losses reset to 0", pnl);
        }
    }

    public void validateSlippage(String tradeType, double actualSlippage) {
        if (!properties.isStrictMode()) return;

        var slippageConfig = properties.getExecution().getSlippage();
        double maxAllowed = switch (tradeType.toUpperCase()) {
            case "ENTRY" -> slippageConfig.getEntryMax();
            case "TP1" -> slippageConfig.getTp1Max();
            case "RUNNER" -> slippageConfig.getRunnerMax();
            case "PANIC_EXIT" -> slippageConfig.getPanicExitMax();
            default -> 999.0;
        };

        if (actualSlippage > maxAllowed) {
            if (properties.getExecution().isRejectOnHighSlippage()) {
                throw new TradingException(String.format(
                    "STRICT_MODE_VIOLATION: %s slippage %.2f exceeds maximum %.2f", 
                    tradeType, actualSlippage, maxAllowed));
            } else {
                // BUG-FIX: Use {} placeholders consistently — String.format used only in exception messages
                log.warn("⚠️ HIGH SLIPPAGE: {} slippage {} exceeds maximum {}", 
                    tradeType, actualSlippage, maxAllowed);
            }
        }
    }

    public void validateLatency(long actualLatencyMs) {
        if (!properties.isStrictMode()) return;

        var latencyConfig = properties.getExecution().getLatency();
        
        if (actualLatencyMs > latencyConfig.getPanicMs()) {
            throw new TradingException(String.format(
                "STRICT_MODE_VIOLATION: Latency %dms exceeds panic threshold %dms", 
                actualLatencyMs, latencyConfig.getPanicMs()));
        }

        if (actualLatencyMs > latencyConfig.getHardBlockMs()) {
            if (properties.getExecution().isRejectOnLatencyBreach()) {
                throw new TradingException(String.format(
                    "STRICT_MODE_VIOLATION: Latency %dms exceeds hard block threshold %dms", 
                    actualLatencyMs, latencyConfig.getHardBlockMs()));
            } else {
                // BUG-FIX: {} placeholders instead of %d
                log.warn("⚠️ HIGH LATENCY: {}ms exceeds hard block threshold {}ms", 
                    actualLatencyMs, latencyConfig.getHardBlockMs());
            }
        }

        if (actualLatencyMs > latencyConfig.getSoftBlockMs()) {
            // BUG-FIX: {} placeholders instead of %d
            log.warn("⚠️ ELEVATED LATENCY: {}ms exceeds soft block threshold {}ms", 
                actualLatencyMs, latencyConfig.getSoftBlockMs());
        }
    }

    public void validateRegime(String currentRegime) {
        if (!properties.isStrictMode()) return;

        String requiredRegime = properties.getFilters().getRegimeRequired();
        
        if (!"ALL".equals(requiredRegime) && !requiredRegime.equals(currentRegime)) {
            throw new TradingException(String.format(
                "STRICT_MODE_VIOLATION: Current regime '%s' does not match required '%s'", 
                currentRegime, requiredRegime));
        }
    }

    public void validateTimePhase(LocalTime currentTime) {
        if (!properties.isStrictMode()) return;

        var timePhase = properties.getTimePhase();
        
        if (isInEarlyPhase(currentTime, timePhase)) {
            log.debug("🕐 Early phase: Position scale = {}", timePhase.getEarly().getPositionScale());
        } else if (isInMidPhase(currentTime, timePhase)) {
            log.debug("🕐 Mid phase: Position scale = {}", timePhase.getMid().getPositionScale());
        } else if (isInLatePhase(currentTime, timePhase)) {
            log.debug("🕐 Late phase: Force exit = {}", timePhase.getLate().getForceExit());
        }
    }

    private boolean isInEarlyPhase(LocalTime time, RiskPilotProperties.TimePhase config) {
        return !time.isBefore(LocalTime.parse(config.getEarly().getStart())) && 
               time.isBefore(LocalTime.parse(config.getEarly().getEnd()));
    }

    private boolean isInMidPhase(LocalTime time, RiskPilotProperties.TimePhase config) {
        return !time.isBefore(LocalTime.parse(config.getMid().getStart())) && 
               time.isBefore(LocalTime.parse(config.getMid().getEnd()));
    }

    private boolean isInLatePhase(LocalTime time, RiskPilotProperties.TimePhase config) {
        return !time.isBefore(LocalTime.parse(config.getLate().getStart())) && 
               time.isBefore(LocalTime.parse(config.getLate().getEnd()));
    }

    public TradingMetrics getDailyMetrics() {
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
