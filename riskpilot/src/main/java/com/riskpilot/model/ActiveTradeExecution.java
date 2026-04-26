package com.riskpilot.model;

public record ActiveTradeExecution(
    double entryPrice,
    double stopLoss,
    double tp1Level,
    double initialRisk,
    boolean tp1Hit,
    boolean runnerActive,
    boolean stage2Active,
    boolean tailHalfLocked,
    double positionSize,
    double remainingSize,
    double realizedPnL,
    double mfe,
    double mae,
    double peakFavorableR,
    double trailingSL
) {

    public static ActiveTradeExecution fromTickTP1(ActiveTradeExecution trade, double currentPrice) {
        if (trade.tp1Hit()) {
            return trade;
        }

        boolean shortTrade = isShort(trade);
        boolean tp1Reached = shortTrade ? currentPrice <= trade.tp1Level() : currentPrice >= trade.tp1Level();
        if (!tp1Reached) {
            return trade;
        }

        double tp1Size = trade.positionSize() * 0.20;
        double remaining = Math.max(0.0, trade.positionSize() - tp1Size);
        double pnl = pnlPoints(trade, currentPrice) * tp1Size;

        return new ActiveTradeExecution(
            trade.entryPrice(),
            trade.entryPrice(),
            trade.tp1Level(),
            trade.initialRisk(),
            true,
            true,
            trade.stage2Active(),
            trade.tailHalfLocked(),
            trade.positionSize(),
            remaining,
            trade.realizedPnL() + pnl,
            trade.mfe(),
            trade.mae(),
            trade.peakFavorableR(),
            trade.entryPrice()
        );
    }

    public static ActiveTradeExecution fromCandleClose(ActiveTradeExecution trade, Candle candle) {
        if (!trade.runnerActive()) {
            return trade;
        }

        boolean shortTrade = isShort(trade);
        double candidateTrailingSl = shortTrade ? candle.high + 10.0 : candle.low - 10.0;
        double tightenedSl = shortTrade
            ? Math.min(trade.trailingSL(), candidateTrailingSl)
            : Math.max(trade.trailingSL(), candidateTrailingSl);

        return new ActiveTradeExecution(
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
            trade.initialRisk(),
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.stage2Active(),
            trade.tailHalfLocked(),
            trade.positionSize(),
            trade.remainingSize(),
            trade.realizedPnL(),
            trade.mfe(),
            trade.mae(),
            trade.peakFavorableR(),
            tightenedSl
        );
    }

    public static TradeExit checkStopLoss(ActiveTradeExecution trade, double currentPrice) {
        boolean shortTrade = isShort(trade);
        double effectiveStop = trade.tp1Hit() ? trade.trailingSL() : trade.stopLoss();
        boolean stopHit = shortTrade ? currentPrice >= effectiveStop : currentPrice <= effectiveStop;
        if (!stopHit) {
            return TradeExit.noExit();
        }

        double exitSize = trade.tp1Hit() ? trade.remainingSize() : trade.positionSize();
        double pnl = pnlPoints(trade, currentPrice) * exitSize;
        return new TradeExit(true, pnl, "STOP_LOSS", currentPrice);
    }

    public static ActiveTradeExecution updateExcursions(ActiveTradeExecution trade, double price) {
        double favorableMove = favorablePoints(trade, price);
        double adverseMove = adversePoints(trade, price);
        double risk = trade.initialRisk() <= 0.0 ? 1.0 : trade.initialRisk();

        return new ActiveTradeExecution(
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
            trade.initialRisk(),
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.stage2Active(),
            trade.tailHalfLocked(),
            trade.positionSize(),
            trade.remainingSize(),
            trade.realizedPnL(),
            Math.max(trade.mfe(), favorableMove),
            Math.max(trade.mae(), adverseMove),
            Math.max(trade.peakFavorableR(), favorableMove / risk),
            trade.trailingSL()
        );
    }

    private static boolean isShort(ActiveTradeExecution trade) {
        return trade.tp1Level() < trade.entryPrice();
    }

    private static double pnlPoints(ActiveTradeExecution trade, double price) {
        return isShort(trade) ? trade.entryPrice() - price : price - trade.entryPrice();
    }

    private static double favorablePoints(ActiveTradeExecution trade, double price) {
        return Math.max(0.0, pnlPoints(trade, price));
    }

    private static double adversePoints(ActiveTradeExecution trade, double price) {
        return Math.max(0.0, -pnlPoints(trade, price));
    }
}
