package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.exception.TradingException;
import com.riskpilot.model.ActiveTradeExecution;
import com.riskpilot.model.CandleEntity;
import com.riskpilot.model.TradeExit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionService {

    private final RiskPilotProperties properties;
    private final StrictValidationService strictValidationService;

    public ActiveTradeExecution executeTick(ActiveTradeExecution trade, double currentPrice, LocalDateTime tickTime) {
        log.debug("🔍 TICK EXECUTION: Price={}, Trade={}", currentPrice, trade.getTp1Hit() ? "RUNNER" : "ACTIVE");

        // 🔒 STEP 1: Update MFE/MAE (mandatory, every tick)
        trade = ActiveTradeExecution.updateExcursions(trade, currentPrice);

        // 🔒 STEP 2: Handle TP1 (tick-level, immediate)
        trade = ActiveTradeExecution.fromTickTP1(trade, currentPrice);

        // 🔒 STEP 3: Check Stop Loss (tick-level, immediate)
        TradeExit exit = ActiveTradeExecution.checkStopLoss(trade, currentPrice);
        
        if (exit.triggered()) {
            log.info("🛑 STOP LOSS: Price={}, PnL={}, Reason={}", 
                    currentPrice, exit.pnl(), exit.exitReason());
            return finalizeTrade(trade, exit);
        }

        return trade;
    }

    public ActiveTradeExecution executeCandleClose(ActiveTradeExecution trade, CandleEntity candle) {
        if (!trade.getRunnerActive()) {
            log.debug("🕯 CANDLE CLOSE: Not a runner, no action needed");
            return trade;
        }

        log.debug("🕯 CANDLE CLOSE: Runner trailing update, Candle={}", candle.getTimestamp());

        // 🔒 STEP 4: Handle Trailing (candle-level ONLY for runners)
        trade = ActiveTradeExecution.fromCandleClose(trade, candle);

        return trade;
    }

    public boolean canExecuteNewTrade(LocalDateTime currentTime) {
        return strictValidationService.canExecuteNewTrade();
    }

    public void recordTradeExecution(double pnl) {
        strictValidationService.recordTradeExecution(pnl);
    }

    public void validateSlippage(String tradeType, double actualSlippage) {
        strictValidationService.validateSlippage(tradeType, actualSlippage);
    }

    public void validateLatency(long actualLatencyMs) {
        strictValidationService.validateLatency(actualLatencyMs);
    }

    public void validateRegime(String currentRegime) {
        strictValidationService.validateRegime(currentRegime);
    }

    public void validateTimePhase(LocalTime currentTime) {
        strictValidationService.validateTimePhase(currentTime);
    }

    private ActiveTradeExecution finalizeTrade(ActiveTradeExecution trade, TradeExit exit) {
        log.info("🔚 TRADE FINALIZED: Entry={}, Exit={}, PnL={}, Reason={}", 
                trade.getEntryPrice(), exit.exitPrice(), exit.pnl(), exit.exitReason());

        // Record the execution for strict validation
        recordTradeExecution(exit.pnl());

        // Log detailed metrics for audit
        logMetrics(trade, exit);

        return trade; // Return final state for logging
    }

    private void logMetrics(ActiveTradeExecution trade, TradeExit exit) {
        var metrics = strictValidationService.getDailyMetrics();
        
        log.info("📊 DAILY METRICS: Trades={}, Losses={}, DailyPnL={}/{}R", 
                metrics.dailyTradeCount(), metrics.consecutiveLosses(), 
                metrics.dailyLossR(), metrics.maxAllowedLossR());
        
        log.info("🎯 TRADE METRICS: MFE={}, MAE={}, RR={:.2f}", 
                trade.getMfe(), trade.getMae(), 
                trade.getMfe() > 0 ? trade.getMfe() / Math.abs(trade.getMae()) : 0.0);
    }

    public void validateExecutionPreconditions() {
        log.info("🔒 EXECUTION PRECONDITIONS CHECK");
        
        // Validate all trading parameters before allowing any execution
        strictValidationService.validateTradingParameters();
        
        // Log current state for audit
        var metrics = strictValidationService.getDailyMetrics();
        log.info("📊 CURRENT STATE: {}/{} trades, {} consecutive losses, {}R daily PnL", 
                metrics.dailyTradeCount(), metrics.maxAllowedTrades(), 
                metrics.consecutiveLosses(), metrics.dailyLossR());
    }
}
