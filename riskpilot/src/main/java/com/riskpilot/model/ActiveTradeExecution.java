package com.riskpilot.model;

import lombok.Data;

@Data
public class ActiveTradeExecution {
    
    private double entryPrice;
    private double stopLoss;
    private double tp1Level;
    
    private boolean tp1Hit;
    private boolean runnerActive;
    
    private double positionSize;     // 1.0 = 100%
    private double remainingSize;    // after TP1
    
    private double realizedPnL;
    private double mfe;
    private double mae;
    
    private double trailingSL;
    
    public static ActiveTradeExecution fromTickTP1(ActiveTradeExecution trade, double currentPrice) {
        if (trade.tp1Hit()) return trade;
        
        if (currentPrice >= trade.tp1Level()) {
            double tp1ExitPrice = currentPrice;
            double tp1Size = trade.positionSize() * 0.20;   // 20% exit
            double remaining = trade.positionSize() - tp1Size;
            double pnl = (tp1ExitPrice - trade.entryPrice()) * tp1Size;
            
            return new ActiveTradeExecution(
                trade.entryPrice(),
                trade.entryPrice(),   // MOVE SL TO BREAKEVEN
                trade.tp1Level(),
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
    
    public static ActiveTradeExecution fromCandleClose(ActiveTradeExecution trade, CandleEntity candle) {
        if (!trade.runnerActive()) return trade;
        
        // For SHORT trades → trail using candle HIGH
        // For LONG trades → trail using candle LOW
        double newTrailingSL = trade.isShort() ? candle.getHighPrice() + 10.0 : candle.getLowPrice() - 10.0;
        
        // Only tighten (never loosen)
        double updatedSL = Math.min(trade.trailingSL(), newTrailingSL);
        
        return new ActiveTradeExecution(
            trade.entryPrice(),
            updatedSL,
            trade.tp1Level(),
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
    
    public static TradeExit checkStopLoss(ActiveTradeExecution trade, double currentPrice) {
        double effectiveSL = trade.tp1Hit() 
            ? trade.trailingSL() 
            : trade.stopLoss();
        
        if (currentPrice <= effectiveSL) {
            double exitSize = trade.tp1Hit() 
                ? trade.remainingSize() 
                : trade.positionSize();
            
            double pnl = (currentPrice - trade.entryPrice()) * exitSize;
            
            return new TradeExit(
                true,
                pnl,
                "STOP_LOSS"
            );
        }
        
        return TradeExit.noExit();
    }
    
    public static ActiveTradeExecution updateExcursions(ActiveTradeExecution trade, double price) {
        double mfe = Math.max(trade.mfe(), price - trade.entryPrice());
        double mae = Math.min(trade.mae(), price - trade.entryPrice());
        
        return new ActiveTradeExecution(
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
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
