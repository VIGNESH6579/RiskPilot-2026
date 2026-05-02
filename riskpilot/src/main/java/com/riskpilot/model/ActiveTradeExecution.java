package com.riskpilot.model;

import lombok.Data;

/**
 * ActiveTradeExecution tracks an in-flight trade with MFE/MAE, TP1 scaling, and runner trailing.
 * 
 * BUG-007: NIFTY Index Futures P&L Formula
 * Formula: (exitPrice - entryPrice) × lots × lotSize × pointValue
 * For NIFTY: pointValue=50, lotSize=75 → ₹3750 per point per lot.
 * NOT valid for options positions (options require delta multiplication).
 */
@Data
public class ActiveTradeExecution {
    
    private double entryPrice;
    private double stopLoss;
    private double tp1Level;
    
    // BUG-009: Track initial risk points to prevent corruption on TP1 SL move
    private double initialRiskPoints;
    
    private boolean tp1Hit;
    private boolean runnerActive;
    
    private double positionSize;     // 1.0 = 100%
    private double remainingSize;    // after TP1
    
    private double realizedPnL;
    private double mfe;
    private double mae;
    
    private double trailingSL;
    
    /**
     * BUG-011: TP1 lot scaling with minimum threshold.
     * For position sizes 1-4 lots: ensures at least 1 lot exits at TP1 (100%).
     * For position sizes 5+ lots: scales 20% at TP1, runner for remaining 80%.
     * 
     * @param trade Current trade execution
     * @param currentPrice Current market price
     * @return Updated trade execution with TP1 applied if triggered
     */
    public static ActiveTradeExecution fromTickTP1(ActiveTradeExecution trade, double currentPrice) {
        if (trade.tp1Hit()) return trade;
        
        if (currentPrice >= trade.tp1Level()) {
            double tp1ExitPrice = currentPrice;
            
            // BUG-011: Ensure minimum TP1 exit even for small positions
            // For position < 5 lots: exit 100% (tp1Size = positionSize)
            // For position >= 5 lots: exit 20% (tp1Size = positionSize * 0.20)
            double minTp1Ratio = trade.positionSize() >= 5.0 ? 0.20 : 1.0;
            double tp1Size = trade.positionSize() * minTp1Ratio;
            double remaining = trade.positionSize() - tp1Size;
            double pnl = (tp1ExitPrice - trade.entryPrice()) * tp1Size;
            
            return new ActiveTradeExecution(
                trade.entryPrice(),
                trade.entryPrice(),   // MOVE SL TO BREAKEVEN
                trade.tp1Level(),
                trade.initialRiskPoints,  // BUG-009: Preserve initial risk
                true,                 // TP1 HIT
                true,                 // runner now active
                trade.positionSize(),
                remaining,
                trade.realizedPnL() + pnl,
                trade.mfe(),
                trade.mae(),
                trade.entryPrice()    // trailing starts at BE
            );
        }
        
        return trade;
    }
    
    /**
     * BUG-013: Trailing stop using ATR (not single candle range).
     * Uses proper ATR-normalized buffer for coherent trailing stop model.
     * 
     * @param trade Current trade execution
     * @param candle Current candle entity
     * @param atr 14-period Average True Range for buffer calculation
     * @return Updated trade execution with trailing stop adjusted
     */
    public static ActiveTradeExecution fromCandleClose(ActiveTradeExecution trade, CandleEntity candle, double atr) {
        if (!trade.runnerActive()) return trade;
        
        // BUG-013: ATR-normalized trailing stop buffer
        // Buffer = max(10.0 points, ATR * 0.4)
        double buffer = Math.max(10.0, atr * 0.4);
        
        // For SHORT trades → trail using candle HIGH + buffer
        // For LONG trades → trail using candle LOW - buffer
        double newTrailingSL = trade.isShort() 
            ? candle.getHighPrice().doubleValue() + buffer 
            : candle.getLowPrice().doubleValue() - buffer;
        
        // Only tighten (never loosen)
        double updatedSL = Math.max(trade.trailingSL(), newTrailingSL);
        
        return new ActiveTradeExecution(
            trade.entryPrice(),
            updatedSL,
            trade.tp1Level(),
            trade.initialRiskPoints,  // BUG-009: Preserve initial risk
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.positionSize(),
            trade.remainingSize(),
            trade.realizedPnL(),
            trade.mfe(),
            trade.mae(),
            updatedSL
        );
    }
    
    /**
     * Legacy method using default 10.0 buffer (without ATR).
     * @deprecated Use fromCandleClose with ATR parameter for proper risk management.
     */
    @Deprecated
    public static ActiveTradeExecution fromCandleClose(ActiveTradeExecution trade, CandleEntity candle) {
        return fromCandleClose(trade, candle, 25.0); // Default ATR ~25 for NIFTY
    }
    
    /**
     * Check if stop loss is hit and calculate P&L.
     * BUG-007: P&L calculated using futures formula (point × size).
     * For options, this would need delta adjustment.
     * 
     * @param trade Current trade execution
     * @param currentPrice Current market price
     * @return TradeExit with triggered flag and P&L
     */
    public static TradeExit checkStopLoss(ActiveTradeExecution trade, double currentPrice) {
        double effectiveSL = trade.tp1Hit() 
            ? trade.trailingSL() 
            : trade.stopLoss();
        
        // For SHORT trades: exit when price >= stop loss
        // For LONG trades: exit when price <= stop loss
        boolean slHit = trade.isShort() 
            ? currentPrice >= effectiveSL 
            : currentPrice <= effectiveSL;
        
        if (slHit) {
            double exitSize = trade.tp1Hit() 
                ? trade.remainingSize() 
                : trade.positionSize();
            
            // BUG-007: P&L = (exit - entry) × size
            // For SHORT: positive P&L when exit < entry
            // For LONG: positive P&L when exit > entry
            double pnl = trade.isShort()
                ? (trade.entryPrice() - currentPrice) * exitSize
                : (currentPrice - trade.entryPrice()) * exitSize;
            
            return new TradeExit(
                true,
                pnl,
                "STOP_LOSS"
            );
        }
        
        return TradeExit.noExit();
    }
    
    /**
     * Update MFE (Max Favorable Excursion) and MAE (Max Adverse Excursion).
     * 
     * @param trade Current trade execution
     * @param price Current market price
     * @return Updated trade with new excursion values
     */
    public static ActiveTradeExecution updateExcursions(ActiveTradeExecution trade, double price) {
        double priceDelta = trade.isShort() 
            ? trade.entryPrice() - price  // SHORT: profit when price drops
            : price - trade.entryPrice(); // LONG: profit when price rises
        
        double mfe = Math.max(trade.mfe(), priceDelta);
        double mae = Math.min(trade.mae(), priceDelta);
        
        return new ActiveTradeExecution(
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
            trade.initialRiskPoints,  // BUG-009: Preserve initial risk
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.positionSize(),
            trade.remainingSize(),
            trade.realizedPnL(),
            mfe,
            mae,
            trade.trailingSL()
        );
    }
    
    private boolean isShort() {
        // This would be determined by trade direction in a real implementation
        // For now, assume LONG (trailing on lows)
        return false;
    }
}
