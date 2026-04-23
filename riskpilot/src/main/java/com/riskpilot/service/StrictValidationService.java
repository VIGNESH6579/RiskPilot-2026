package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.TradingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private volatile LocalDateTime lastTradeDate;

    public void validateTradingParameters() {
        log.info("🔒 STRICT MODE: Validating trading parameters against doctrine");
        
        // Trade count validation
        if (properties.getRisk().getMaxTradesPerDay() > 2) {
            throw new TradingException("STRICT_MODE_VIOLATION: max-trades-per-day cannot exceed 2. Current: " + 
                properties.getRisk().getMaxTradesPerDay());
        }
        
        // Slippage validation
        var slippage = properties.getExecution().getSlippage();
        if (slippage.getEntryMax() > 2.0) {
            throw new TradingException("STRICT_MODE_VIOLATION: entry-max slippage cannot exceed 2.0. Current: " + 
                slippage.getEntryMax());
        }
        
        if (slippage.getTp1Max() > 3.0) {
            throw new TradingException("STRICT_MODE_VIOLATION: tp1-max slippage cannot exceed 3.0. Current: " + 
                slippage.getTp1Max());
        }
        
        // Latency validation
        var latency = properties.getExecution().getLatency();
        if (latency.getSoftBlockMs() > 1000) {
            throw new TradingException("STRICT_MODE_VIOLATION: soft-block-ms cannot exceed 1000. Current: " + 
                latency.getSoftBlockMs());
        }
        
        log.info("✅ Trading parameters validation passed");
    }

    public boolean canExecuteNewTrade() {
        if (!properties.getStrictMode()) {
            return true; // Allow if not in strict mode
        }

        LocalDateTime now = LocalDateTime.now();
        
        // Reset daily counters at start of new trading day
        if (lastTradeDate == null || now.toLocalDate().isAfter(lastTradeDate.toLocalDate())) {
            dailyTradeCount.set(0);
            consecutiveLosses.set(0);
            dailyLossR = 0.0;
            lastTradeDate = now;
            log.info("📅 Daily trading counters reset for new day");
        }

        // Check daily trade limit
        if (dailyTradeCount.get() >= properties.getRisk().getMaxTradesPerDay()) {
            log.warn("🚫 TRADE REJECTED: Daily trade limit reached ({}/{})", 
                dailyTradeCount.get(), properties.getRisk().getMaxTradesPerDay());
            return false;
        }

        // Check consecutive losses
        if (consecutiveLosses.get() >= properties.getRisk().getMaxConsecutiveLosses()) {
            log.warn("🚫 TRADE REJECTED: Consecutive loss limit reached ({}/{})", 
                consecutiveLosses.get(), properties.getRisk().getMaxConsecutiveLosses());
            return false;
        }

        // Check daily loss limit
        if (dailyLossR <= -properties.getRisk().getMaxDailyLossR()) {
            log.warn("🚫 TRADE REJECTED: Daily loss limit reached ({}R/{})", 
                dailyLossR, properties.getRisk().getMaxDailyLossR());
            return false;
        }

        // Check time phase restrictions
        LocalTime currentTime = now.toLocalTime();
        var timePhase = properties.getTimePhase();
        
        if (isInLatePhase(currentTime, timePhase) && !timePhase.getLate().getAllowNewTrades()) {
            log.warn("🚫 TRADE REJECTED: New trades not allowed in late phase");
            return false;
        }

        return true;
    }

    public void recordTradeExecution(double pnl) {
        if (!properties.getStrictMode()) {
            return;
        }

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
        if (!properties.getStrictMode()) {
            return;
        }

        var slippageConfig = properties.getExecution().getSlippage();
        double maxAllowed = switch (tradeType.toUpperCase()) {
            case "ENTRY" -> slippageConfig.getEntryMax();
            case "TP1" -> slippageConfig.getTp1Max();
            case "RUNNER" -> slippageConfig.getRunnerMax();
            case "PANIC_EXIT" -> slippageConfig.getPanicExitMax();
            default -> 999.0;
        };

        if (actualSlippage > maxAllowed) {
            if (properties.getExecution().getRejectOnHighSlippage()) {
                throw new TradingException(String.format(
                    "STRICT_MODE_VIOLATION: %s slippage %.2f exceeds maximum %.2f", 
                    tradeType, actualSlippage, maxAllowed));
            } else {
                log.warn("⚠️ HIGH SLIPPAGE: {} slippage %.2f exceeds maximum %.2f", 
                    tradeType, actualSlippage, maxAllowed);
            }
        }
    }

    public void validateLatency(long actualLatencyMs) {
        if (!properties.getStrictMode()) {
            return;
        }

        var latencyConfig = properties.getExecution().getLatency();
        
        if (actualLatencyMs > latencyConfig.getPanicMs()) {
            throw new TradingException(String.format(
                "STRICT_MODE_VIOLATION: Latency %dms exceeds panic threshold %dms", 
                actualLatencyMs, latencyConfig.getPanicMs()));
        }

        if (actualLatencyMs > latencyConfig.getHardBlockMs()) {
            if (properties.getExecution().getRejectOnLatencyBreach()) {
                throw new TradingException(String.format(
                    "STRICT_MODE_VIOLATION: Latency %dms exceeds hard block threshold %dms", 
                    actualLatencyMs, latencyConfig.getHardBlockMs()));
            } else {
                log.warn("⚠️ HIGH LATENCY: %dms exceeds hard block threshold %dms", 
                    actualLatencyMs, latencyConfig.getHardBlockMs());
            }
        }

        if (actualLatencyMs > latencyConfig.getSoftBlockMs()) {
            log.warn("⚠️ ELEVATED LATENCY: %dms exceeds soft block threshold %dms", 
                actualLatencyMs, latencyConfig.getSoftBlockMs());
        }
    }

    public void validateRegime(String currentRegime) {
        if (!properties.getStrictMode()) {
            return;
        }

        String requiredRegime = properties.getFilters().getRegimeRequired();
        
        if (!"ALL".equals(requiredRegime) && !requiredRegime.equals(currentRegime)) {
            throw new TradingException(String.format(
                "STRICT_MODE_VIOLATION: Current regime '%s' does not match required '%s'", 
                currentRegime, requiredRegime));
        }
    }

    public void validateTimePhase(LocalTime currentTime) {
        if (!properties.getStrictMode()) {
            return;
        }

        var timePhase = properties.getTimePhase();
        
        if (isInEarlyPhase(currentTime, timePhase)) {
            log.debug("🕐 Early phase: Position scale = {}", timePhase.getEarly().getPositionScale());
        } else if (isInMidPhase(currentTime, timePhase)) {
            log.debug("🕐 Mid phase: Position scale = {}", timePhase.getMid().getPositionScale());
        } else if (isInLatePhase(currentTime, timePhase)) {
            log.debug("🕐 Late phase: Force exit = {}", timePhase.getLate().getForceExit());
        }
    }

    private boolean isInEarlyPhase(LocalTime time, RiskPilotProperties.TimePhaseConfig config) {
        return !time.isBefore(LocalTime.parse(config.getEarly().getStart())) && 
               time.isBefore(LocalTime.parse(config.getEarly().getEnd()));
    }

    private boolean isInMidPhase(LocalTime time, RiskPilotProperties.TimePhaseConfig config) {
        return !time.isBefore(LocalTime.parse(config.getMid().getStart())) && 
               time.isBefore(LocalTime.parse(config.getMid().getEnd()));
    }

    private boolean isInLatePhase(LocalTime time, RiskPilotProperties.TimePhaseConfig config) {
        return !time.isBefore(LocalTime.parse(config.getLate().getStart())) && 
               time.isBefore(LocalTime.parse(config.getLate().getEnd()));
    }

    public TradingMetrics getDailyMetrics() {
        return TradingMetrics.builder()
                .dailyTradeCount(dailyTradeCount.get())
                .consecutiveLosses(consecutiveLosses.get())
                .dailyLossR(dailyLossR)
                .maxAllowedTrades(properties.getRisk().getMaxTradesPerDay())
                .maxAllowedLossR(properties.getRisk().getMaxDailyLossR())
                .maxAllowedConsecutiveLosses(properties.getRisk().getMaxConsecutiveLosses())
                .strictMode(properties.getStrictMode())
                .build();
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
