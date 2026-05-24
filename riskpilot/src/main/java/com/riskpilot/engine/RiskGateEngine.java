package com.riskpilot.engine;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.GateDecision;
import com.riskpilot.model.TradingSessionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskGateEngine {

    private final RiskPilotProperties config;
    private final KillSwitchEngine killSwitchEngine;
    private final RegimeConfidenceEngine regimeConfidenceEngine;
    private final RegimeFilter regimeFilter;
    private final RealTimeEdgeTracker edgeTracker;
    private final AdaptiveRegimeEngine adaptiveRegimeEngine;

    @PostConstruct
    public void validate() {
        log.info("🔴 RISKGATE STARTUP VALIDATION");

        // FIX: old hard cap of 10 blocked paper-mode sessions that want more than 10 trades.
        // The real safety limit is: LIVE mode → hard cap 5; PAPER/SHADOW mode → cap 20.
        boolean isPaper = config.isPaperMode() || "SHADOW".equalsIgnoreCase(config.getMode());
        int absoluteMax = isPaper ? 20 : 5;
        if (config.getRisk().getMaxTradesPerDay() > absoluteMax) {
            throw new IllegalStateException(
                "MAX_TRADES_VIOLATION: maxTradesPerDay=" + config.getRisk().getMaxTradesPerDay()
                + " exceeds safety limit of " + absoluteMax
                + " for mode=" + config.getMode()
                + ". Set riskpilot.risk.max-trades-per-day <= " + absoluteMax + ".");
        }
        if (config.getRisk().getMaxTradesPerDay() > 3) {
            log.warn("HIGH_TRADE_COUNT: maxTradesPerDay={} - ensure this is intentional",
                config.getRisk().getMaxTradesPerDay());
        }

        if (config.getExecution().getSlippage().getEntryMax() > 3.0) {
            throw new IllegalStateException("SLIPPAGE_VIOLATION: Entry slippage too high for viable edge. Current: " +
                config.getExecution().getSlippage().getEntryMax());
        }

        if (!config.isStrictMode() && !config.isPaperMode()) {
            throw new IllegalStateException("STRICT_MODE_VIOLATION: Strict mode must be enabled in production");
        }
        if (config.isPaperMode()) {
            log.warn("⚠️ PAPER_MODE ACTIVE — live-money guards relaxed. Do NOT use with real funds.");
        }

        if (!"SHADOW".equalsIgnoreCase(config.getMode()) &&
            !"LIVE".equalsIgnoreCase(config.getMode()) &&
            !"REPLAY".equalsIgnoreCase(config.getMode())) {
            throw new IllegalStateException("MODE_VIOLATION: Invalid mode: " + config.getMode());
        }

        log.info("✅ RISKGATE VALIDATION PASSED: All parameters within doctrine");
    }

    public GateDecision evaluateEntry(TradingSessionSnapshot s,
                                      double orRange,
                                      double entrySlippage,
                                      long latencyMs,
                                      List<RegimeConfidenceEngine.CandleData> candleData) {

        log.debug("🔍 GATE EVALUATION: OR={}, Slippage={}, Latency={}ms",
                orRange, entrySlippage, latencyMs);

        // -------------------------
        // 🔴 KILL-SWITCH CHECK (FIRST PRIORITY)
        // -------------------------
        if (killSwitchEngine.isKillSwitchTriggered()) {
            log.error("🚫 KILL_SWITCH_HALTED: System disabled by kill-switch");
            return reject("KILL_SWITCH_HALTED");
        }

        // -------------------------
        // 🔴 REGIME CONFIDENCE SCORE (PRE-TRADE HARD BLOCK)
        // -------------------------
        // This sits ABOVE all other logic - if score < 55, NO TRADING AT ALL
        List<RegimeConfidenceEngine.CandleData> confidenceCandles =
            candleData == null ? List.of() : candleData;
        RegimeConfidenceEngine.RegimeScore regimeScore = regimeConfidenceEngine.evaluate(s, confidenceCandles);

        // BUG-FIX: Early session bypass (before 9:50 AM IST)
        // If we are very early in the session and have a valid OR range, we allow trading
        // even if technical confidence scores are still building up.
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        boolean earlySessionBypass = now.isBefore(java.time.LocalTime.of(9, 50)) && 
                                   Double.isFinite(orRange) && orRange >= config.getFilters().getMinOrRange();

        if (!regimeScore.isTradingAllowed() && !earlySessionBypass) {
            log.error("🚫 REGIME_CONFIDENCE_BLOCKED: Score={}, Reason={}",
                    regimeScore.getTotalScore(), regimeScore.getReason());
            return reject("LOW_CONFIDENCE_DAY");
        } else if (earlySessionBypass && !regimeScore.isTradingAllowed()) {
            log.info("⚠️ REGIME_CONFIDENCE_LOW ({}) but EARLY_SESSION_BYPASS active - allowing trade", 
                    regimeScore.getTotalScore());
        }

        // -------------------------
        // 🔴 REDUCED MODE LIMIT (1 trade max)
        // -------------------------
        if (regimeScore.isReducedMode() && s.tradesTaken() >= 1) {
            log.error("🚫 REDUCED_MODE_LIMIT: Score={}, TradesTaken={}",
                    regimeScore.getTotalScore(), s.tradesTaken());
            return reject("REDUCED_MODE_LIMIT");
        }

        // -------------------------
        // 🔴 ADAPTIVE REGIME FILTER (SECONDARY FILTER)
        // -------------------------
        AdaptiveRegimeEngine.AdaptiveConfig adaptiveConfig = adaptiveRegimeEngine.getCurrentConfig();
        
        // BUG-FIX: Defensive null check for adaptiveConfig
        if (adaptiveConfig == null) {
            log.warn("⚠️ ADAPTIVE_CONFIG_NULL: Falling back to default configuration");
            adaptiveConfig = new AdaptiveRegimeEngine.AdaptiveConfig();
        }

        RegimeFilter.RegimeMetrics regime = regimeFilter.getCurrentRegime();
        if (regime == null) {
            if (!earlySessionBypass) {
                log.warn("🚫 REGIME_NOT_INITIALIZED: waiting for candles to build regime");
                return reject("REGIME_NOT_INITIALIZED");
            }
            log.info("⚠️ REGIME_NOT_INITIALIZED but EARLY_SESSION_BYPASS active — skipping regime metric checks");
        } else {
            // Check adaptive thresholds (only when regime is initialized)
            if (regime.getRegimeScore() < adaptiveConfig.getMinRegimeScore()) {
                log.error("🚫 ADAPTIVE_REGIME_BLOCKED: Score={} < {}, Reasons={}",
                        regime.getRegimeScore(), adaptiveConfig.getMinRegimeScore(),
                        String.join(", ", regime.getBlockingReasons()));
                return reject("ADAPTIVE_REGIME_WEAK");
            }

            if (regime.getOrRange() < adaptiveConfig.getMinORRange()) {
                log.error("🚫 ADAPTIVE_OR_BLOCKED: OR={} < {}",
                        regime.getOrRange(), adaptiveConfig.getMinORRange());
                return reject("ADAPTIVE_OR_TOO_SMALL");
            }

            if (regime.getAtrRatio() < adaptiveConfig.getMinATRRatio()) {
                log.error("🚫 ADAPTIVE_ATR_BLOCKED: ATR={} < {}",
                        regime.getAtrRatio(), adaptiveConfig.getMinATRRatio());
                return reject("ADAPTIVE_ATR_WEAK");
            }

            if (regime.getTrendEfficiency() < adaptiveConfig.getMinEfficiency()) {
                log.error("🚫 ADAPTIVE_EFFICIENCY_BLOCKED: Eff={} < {}",
                        regime.getTrendEfficiency(), adaptiveConfig.getMinEfficiency());
                return reject("ADAPTIVE_CHOPPY");
            }

            if (regime.getBreakoutHoldRate() < adaptiveConfig.getMinBreakoutHoldRate()) {
                log.error("🚫 ADAPTIVE_BREAKOUT_BLOCKED: Hold={} < {}",
                        regime.getBreakoutHoldRate(), adaptiveConfig.getMinBreakoutHoldRate());
                return reject("ADAPTIVE_BREAKOUT_WEAK");
            }
        }

        // -------------------------
        // 🔴 EDGE HEALTH CHECK
        // -------------------------
        if (!edgeTracker.isEdgeHealthy()) {
            RealTimeEdgeTracker.EdgeMetrics metrics = edgeTracker.getCurrentMetrics();
            log.error("🚫 EDGE_UNHEALTHY: DecayScore={}, Expectancy={}",
                    metrics.getDecayScore(), metrics.getExpectancy());
            return reject("EDGE_DECAY");
        }

        // -------------------------
        // 🔴 MODE HARD BLOCK
        // -------------------------
        if (!"SHADOW".equalsIgnoreCase(config.getMode()) &&
            !"LIVE".equalsIgnoreCase(config.getMode())) {
            return reject("INVALID_MODE");
        }

        // -------------------------
        // 🔴 INFRA GATES
        // -------------------------
        if (config.getInfra().getFeed().isRequireStable() && !s.isFeedStable()) {
            log.warn("🚫 FEED UNSTABLE: Blocking entry");
            return reject("FEED_UNSTABLE");
        }

        if (config.getInfra().getHeartbeat().isEnabled() && !s.isHeartbeatAlive()) {
            log.warn("🚫 HEARTBEAT DEAD: Blocking entry");
            return reject("HEARTBEAT_DEAD");
        }

        // -------------------------
        // 🔴 LATENCY GATE
        // -------------------------
        if (config.getExecution().isRejectOnLatencyBreach()) {
            long hard = config.getExecution().getLatency().getHardBlockMs();
            if (latencyMs > hard) {
                log.warn("🚫 LATENCY TOO HIGH: {}ms > {}ms", latencyMs, hard);
                return reject("LATENCY_TOO_HIGH");
            }
        }

        // -------------------------
        // 🔴 SLIPPAGE GATE
        // -------------------------
        if (config.getExecution().isRejectOnHighSlippage()) {
            double max = config.getExecution().getSlippage().getEntryMax();
            if (entrySlippage > max) {
                log.warn("🚫 SLIPPAGE TOO HIGH: {} > {}", entrySlippage, max);
                return reject("SLIPPAGE_TOO_HIGH");
            }
        }

        // -------------------------
        // 🔴 VOLATILITY GATE
        // -------------------------
        double evaluatedOrRange = Double.isFinite(orRange)
            ? orRange
            : (Double.isFinite(s.orHigh()) && Double.isFinite(s.orLow()) ? s.orHigh() - s.orLow() : 0.0);
        if (!Double.isFinite(evaluatedOrRange) || evaluatedOrRange < config.getFilters().getMinOrRange()) {
            log.warn("🚫 LOW VOLATILITY: OR range {} < {}",
                evaluatedOrRange, config.getFilters().getMinOrRange());
            return reject("LOW_VOLATILITY");
        }

        // -------------------------
        // 🔴 REGIME GATE
        // -------------------------
        String requiredRegime = config.getFilters().getRegimeRequired();

        if ("TREND_ONLY".equalsIgnoreCase(requiredRegime)) {
            if (!"TREND".equalsIgnoreCase(s.getRegime())) {
                log.warn("🚫 NON-TREND REGIME: {} (Required: TREND_ONLY)", s.getRegime());
                return reject("NON_TREND");
            }
        } else if ("CHOP_ALLOWED".equalsIgnoreCase(requiredRegime)) {
            if ("BLOCKED".equalsIgnoreCase(s.getRegime())) {
                log.warn("🚫 BLOCKED REGIME: {} (Configured: CHOP_ALLOWED)", s.getRegime());
                return reject("BLOCKED_REGIME");
            } else if ("CHOP".equalsIgnoreCase(s.getRegime()) || "UNKNOWN".equalsIgnoreCase(s.getRegime())) {
                log.info("✅ CHOP/UNKNOWN REGIME ALLOWED: {} (Configured: CHOP_ALLOWED)", s.getRegime());
            }
        } else if ("ANY".equalsIgnoreCase(requiredRegime)) {
            if ("BLOCKED".equalsIgnoreCase(s.getRegime())) {
                log.warn("🚫 BLOCKED REGIME: {} (Configured: ANY)", s.getRegime());
                return reject("BLOCKED_REGIME");
            } else {
                log.info("✅ ANY REGIME ALLOWED: {} (Configured: ANY)", s.getRegime());
            }
        } else {
            log.warn("🚫 INVALID REGIME_REQUIRED CONFIGURATION: {}", requiredRegime);
            return reject("INVALID_REGIME_CONFIG");
        }

        // -------------------------
        // 🔴 TRADE LIMITS
        // -------------------------
        if (s.getTradesTaken() >= config.getRisk().getMaxTradesPerDay()) {
            log.warn("🚫 MAX TRADES REACHED: {}/{}", s.getTradesTaken(), config.getRisk().getMaxTradesPerDay());
            return reject("MAX_TRADES_REACHED");
        }

        if (config.getRisk().isOneTradeAtATime() && s.isTradeActive()) {
            log.warn("🚫 ACTIVE TRADE EXISTS: One-trade-at-a-time rule violated");
            return reject("ACTIVE_TRADE_EXISTS");
        }

        // -------------------------
        // 🔴 RISK LIMITS
        // -------------------------
        double effectiveMaxDailyLossR = config.isPaperMode()
            ? Math.max(config.getRisk().getMaxDailyLossR(), 4.0)
            : config.getRisk().getMaxDailyLossR();
        int effectiveMaxConsecutiveLosses = config.isPaperMode()
            ? Math.max(config.getRisk().getMaxConsecutiveLosses(), 6)
            : config.getRisk().getMaxConsecutiveLosses();

        if (s.getCumulativeDailyLossR() <= -effectiveMaxDailyLossR) {
            log.warn("🚫 DAILY LOSS LIMIT: {}R < {}R", s.getCumulativeDailyLossR(), effectiveMaxDailyLossR);
            return reject("DAILY_LOSS_LIMIT");
        }

        if (s.getConsecutiveLosses() >= effectiveMaxConsecutiveLosses) {
            log.warn("🚫 LOSS STREAK LIMIT: {} consecutive losses", s.getConsecutiveLosses());
            return reject("LOSS_STREAK_LIMIT");
        }

        // -------------------------
        // 🔴 TIME PHASE GATE
        // -------------------------
        if ("LATE".equalsIgnoreCase(s.getTimePhase())) {
            if (!config.getTimePhase().getLate().getAllowNewTrades()) {
                log.warn("🚫 LATE SESSION BLOCK: New trades not allowed in late phase");
                return reject("LATE_SESSION_BLOCK");
            }
        }

        log.debug("✅ GATE PASSED: All checks cleared");
        return GateDecision.allow();
    }

    public boolean shouldForceLateSessionExit(TradingSessionSnapshot s) {
        return "LATE".equalsIgnoreCase(s.getTimePhase()) &&
               config.getTimePhase().getLate().getForceExit() &&
               s.isTradeActive();
    }

    public void logDecision(TradingSessionSnapshot state, double orRange, long latencyMs,
                           double entrySlippage, GateDecision decision) {
        log.info("� GATE_DECISION: OR={}, Latency={}ms, Slippage={} → {}",
                orRange, latencyMs, entrySlippage, decision.reason());
    }

    private GateDecision reject(String reason) {
        log.warn("🚫 GATE REJECTION: {}", reason);
        return GateDecision.reject(reason);
    }
}
