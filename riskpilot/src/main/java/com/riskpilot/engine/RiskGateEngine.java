package com.riskpilot.engine;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.GateDecision;
import com.riskpilot.model.TradingSessionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;

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
        
        if (config.getRisk().getMaxTradesPerDay() > 2) {
            throw new IllegalStateException("MAX_TRADES_VIOLATION: Max trades per day cannot exceed 2. Current: " + 
                config.getRisk().getMaxTradesPerDay());
        }

        if (config.getExecution().getSlippage().getEntryMax() > 3.0) {
            throw new IllegalStateException("SLIPPAGE_VIOLATION: Entry slippage too high for viable edge. Current: " + 
                config.getExecution().getSlippage().getEntryMax());
        }

        if (!config.isStrictMode()) {
            throw new IllegalStateException("STRICT_MODE_VIOLATION: Strict mode must be enabled in production");
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
                                      long latencyMs) {

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
        List<RegimeConfidenceEngine.CandleData> candleData = convertToCandleData(state);
        RegimeConfidenceEngine.RegimeScore regimeScore = regimeConfidenceEngine.evaluate(state, candleData);
        
        if (!regimeScore.tradingAllowed()) {
            log.error("🚫 REGIME_CONFIDENCE_BLOCKED: Score={}, Reason={}", 
                    regimeScore.getTotalScore(), regimeScore.getReason());
            return reject("LOW_CONFIDENCE_DAY");
        }

        // -------------------------
        // 🔴 REDUCED MODE LIMIT (1 trade max)
        // -------------------------
        if (regimeScore.reducedMode() && state.tradesTaken() >= 1) {
            log.error("🚫 REDUCED_MODE_LIMIT: Score={}, TradesTaken={}", 
                    regimeScore.getTotalScore(), state.tradesTaken());
            return reject("REDUCED_MODE_LIMIT");
        }

        // -------------------------
        // 🔴 ADAPTIVE REGIME FILTER (SECONDARY FILTER)
        // -------------------------
        AdaptiveRegimeEngine.AdaptiveConfig adaptiveConfig = adaptiveRegimeEngine.getCurrentConfig();
        RegimeFilter.RegimeMetrics regime = regimeFilter.getCurrentRegime();
        
        // Check adaptive thresholds
        if (regime.getRegimeScore() < adaptiveConfig.getMinRegimeScore()) {
            log.error("🚫 ADAPTIVE_REGIME_BLOCKED: Score={} < {}, Reasons={}", 
                    regime.getRegimeScore(), adaptiveConfig.getMinRegimeScore(), 
                    String.join(", ", regime.getBlockingReasons()));
            return reject("ADAPTIVE_REGIME_WEAK");
        }
        
        if (regime.getOrRange() < adaptiveConfig.getMinORRange()) {
            log.error("🚫 ADAPTIVE_OR_BLOCKED: OR={:.1f} < {:.1f}", 
                    regime.getOrRange(), adaptiveConfig.getMinORRange());
            return reject("ADAPTIVE_OR_TOO_SMALL");
        }
        
        if (regime.getAtrRatio() < adaptiveConfig.getMinATRRatio()) {
            log.error("🚫 ADAPTIVE_ATR_BLOCKED: ATR={:.2f} < {:.2f}", 
                    regime.getAtrRatio(), adaptiveConfig.getMinATRRatio());
            return reject("ADAPTIVE_ATR_WEAK");
        }
        
        if (regime.getTrendEfficiency() < adaptiveConfig.getMinEfficiency()) {
            log.error("🚫 ADAPTIVE_EFFICIENCY_BLOCKED: Eff={:.2f} < {:.2f}", 
                    regime.getTrendEfficiency(), adaptiveConfig.getMinEfficiency());
            return reject("ADAPTIVE_CHOPPY");
        }
        
        if (regime.getBreakoutHoldRate() < adaptiveConfig.getMinBreakoutHoldRate()) {
            log.error("🚫 ADAPTIVE_BREAKOUT_BLOCKED: Hold={:.2f} < {:.2f}", 
                    regime.getBreakoutHoldRate(), adaptiveConfig.getMinBreakoutHoldRate());
            return reject("ADAPTIVE_BREAKOUT_WEAK");
        }

        // -------------------------
        // 🔴 EDGE HEALTH CHECK
        // -------------------------
        if (!edgeTracker.isEdgeHealthy()) {
            RealTimeEdgeTracker.EdgeMetrics metrics = edgeTracker.getCurrentMetrics();
            log.error("🚫 EDGE_UNHEALTHY: DecayScore={}, Expectancy={:.3f}", 
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
        if (orRange < config.getFilters().getMinOrRange()) {
            log.warn("🚫 LOW VOLATILITY: OR range {} < {}", orRange, config.getFilters().getMinOrRange());
            return reject("LOW_VOLATILITY");
        }

        // -------------------------
        // 🔴 REGIME GATE
        // -------------------------
        if (!"TREND".equalsIgnoreCase(s.getRegime())) {
            log.warn("🚫 NON-TREND REGIME: {}", s.getRegime());
            return reject("NON_TREND");
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
        if (s.getCumulativeDailyLossR() <= -config.getRisk().getMaxDailyLossR()) {
            log.warn("🚫 DAILY LOSS LIMIT: {}R < {}R", s.getCumulativeDailyLossR(), config.getRisk().getMaxDailyLossR());
            return reject("DAILY_LOSS_LIMIT");
        }

        if (s.getConsecutiveLosses() >= config.getRisk().getMaxConsecutiveLosses()) {
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

    /**
     * Convert session state to candle data for regime confidence evaluation
     * Note: In practice, this should be passed from ShadowExecutionEngine
     * For now, we'll use a simplified approach with conservative defaults
     */
    private List<RegimeConfidenceEngine.CandleData> convertToCandleData(TradingSessionSnapshot state) {
        // Create minimal candle data based on session state
        List<RegimeConfidenceEngine.CandleData> candles = new ArrayList<>();
        
        // Add opening range candle
        if (state.orHigh() > 0 && state.orLow() < Double.MAX_VALUE) {
            double orMid = (state.orHigh() + state.orLow()) / 2.0;
            double orRange = state.orHigh() - state.orLow();
            
            candles.add(new RegimeConfidenceEngine.CandleData(
                orMid, state.orHigh(), state.orLow(), orMid, 
                java.time.LocalDateTime.now().with(java.time.LocalTime.of(9, 30))
            ));
        }
        
        return candles;
    }

    public void logDecision(TradingSessionSnapshot state, double orRange, long latencyMs, 
                           double entrySlippage, GateDecision decision) {
        log.info("� GATE_DECISION: OR={:.1f}, Latency={}ms, Slippage={:.2f} → {}", 
                orRange, latencyMs, entrySlippage, decision.reason());
    }

    private GateDecision reject(String reason) {
        log.warn("🚫 GATE REJECTION: {}", reason);
        return GateDecision.reject(reason);
    }
}
