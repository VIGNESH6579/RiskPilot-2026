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

    private String direction = "SHORT";
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
    private double peakFavorableR;
    private double trailingSL;

    public ActiveTradeExecution() {
    }

    public ActiveTradeExecution(
        double entryPrice,
        double stopLoss,
        double tp1Level,
        double initialRiskPoints,
        boolean tp1Hit,
        boolean runnerActive,
        double positionSize,
        double remainingSize,
        double realizedPnL,
        double mfe,
        double mae,
        double trailingSL
    ) {
        this("SHORT", entryPrice, stopLoss, tp1Level, initialRiskPoints, tp1Hit, runnerActive,
            positionSize, remainingSize, realizedPnL, mfe, mae, 0.0, trailingSL);
    }

    public ActiveTradeExecution(
        double entryPrice,
        double stopLoss,
        double tp1Level,
        double initialRiskPoints,
        boolean tp1Hit,
        boolean runnerActive,
        double positionSize,
        double remainingSize,
        double realizedPnL,
        double mfe,
        double mae,
        double peakFavorableR,
        double trailingSL
    ) {
        this("SHORT", entryPrice, stopLoss, tp1Level, initialRiskPoints, tp1Hit, runnerActive,
            positionSize, remainingSize, realizedPnL, mfe, mae, peakFavorableR, trailingSL);
    }

    public ActiveTradeExecution(
        String direction,
        double entryPrice,
        double stopLoss,
        double tp1Level,
        double initialRiskPoints,
        boolean tp1Hit,
        boolean runnerActive,
        double positionSize,
        double remainingSize,
        double realizedPnL,
        double mfe,
        double mae,
        double peakFavorableR,
        double trailingSL
    ) {
        this.direction = direction == null || direction.isBlank() ? "SHORT" : direction;
        this.entryPrice = entryPrice;
        this.stopLoss = stopLoss;
        this.tp1Level = tp1Level;
        this.initialRiskPoints = initialRiskPoints;
        this.tp1Hit = tp1Hit;
        this.runnerActive = runnerActive;
        this.positionSize = positionSize;
        this.remainingSize = remainingSize;
        this.realizedPnL = realizedPnL;
        this.mfe = mfe;
        this.mae = mae;
        this.peakFavorableR = peakFavorableR;
        this.trailingSL = trailingSL;
    }
    
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
        
        boolean tp1Reached = trade.isShort()
            ? currentPrice <= trade.tp1Level()
            : currentPrice >= trade.tp1Level();

        if (tp1Reached) {
            double tp1ExitPrice = currentPrice;
            
            // BUG-011: Ensure minimum TP1 exit even for small positions
            // For position < 5 lots: exit 100% (tp1Size = positionSize)
            // For position >= 5 lots: exit 20% (tp1Size = positionSize * 0.20)
            double minTp1Ratio = trade.positionSize() >= 5.0 ? 0.20 : 1.0;
            double tp1Size = trade.positionSize() * minTp1Ratio;
            double remaining = trade.positionSize() - tp1Size;
            double pnl = trade.isShort()
                ? (trade.entryPrice() - tp1ExitPrice) * tp1Size
                : (tp1ExitPrice - trade.entryPrice()) * tp1Size;
            
            return new ActiveTradeExecution(
                trade.direction(),
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
                trade.peakFavorableR(),
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
        return fromCandleClose(trade, candle.toCandle(), atr);
    }

    public static ActiveTradeExecution fromCandleClose(ActiveTradeExecution trade, Candle candle, double atr) {
        if (!trade.runnerActive()) return trade;
        
        // BUG-013: ATR-normalized trailing stop buffer
        // Buffer = max(10.0 points, ATR * 0.4)
        double buffer = Math.max(10.0, atr * 0.4);
        
        // For SHORT trades → trail using candle HIGH + buffer
        // For LONG trades → trail using candle LOW - buffer
        double newTrailingSL = trade.isShort() 
            ? candle.high + buffer
            : candle.low - buffer;
        
        // Only tighten (never loosen)
        double updatedSL = trade.isShort()
            ? Math.min(trade.trailingSL(), newTrailingSL)
            : Math.max(trade.trailingSL(), newTrailingSL);
        
        return new ActiveTradeExecution(
            trade.direction(),
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
            trade.peakFavorableR(),
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

    @Deprecated
    public static ActiveTradeExecution fromCandleClose(ActiveTradeExecution trade, Candle candle) {
        return fromCandleClose(trade, candle, 25.0);
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
                "STOP_LOSS",
                currentPrice
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
            trade.direction(),
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
            Math.max(trade.peakFavorableR(), trade.initialRiskPoints() > 0.0 ? mfe / trade.initialRiskPoints() : 0.0),
            trade.trailingSL()
        );
    }
    
    private boolean isShort() {
        return "SHORT".equalsIgnoreCase(direction);
    }

    public String direction() { return direction; }
    public double entryPrice() { return entryPrice; }
    public double stopLoss() { return stopLoss; }
    public double tp1Level() { return tp1Level; }
    public double initialRiskPoints() { return initialRiskPoints; }
    public boolean tp1Hit() { return tp1Hit; }
    public boolean runnerActive() { return runnerActive; }
    public boolean getTp1Hit() { return tp1Hit; }
    public boolean getRunnerActive() { return runnerActive; }
    public double positionSize() { return positionSize; }
    public double remainingSize() { return remainingSize; }
    public double realizedPnL() { return realizedPnL; }
    public double mfe() { return mfe; }
    public double mae() { return mae; }
    public double peakFavorableR() { return peakFavorableR; }
    public double trailingSL() { return trailingSL; }
}
