package com.riskpilot.model;

public record ActiveTradeExecution(
    String direction,
    double entryPrice,
    double stopLoss,
    double tp1Level,
    double initialRiskPoints,
    boolean tp1Hit,
    boolean runnerActive,
    boolean stage2Active,
    boolean tailHalfLocked,
    int quantity,
    int remainingQuantity,
    int lotSize,
    double pointValue,
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

        int tp1Lots = trade.quantity() >= 2 ? Math.max(1, (int) Math.round(trade.quantity() * 0.20)) : 0;
        int remainingLots = Math.max(0, trade.quantity() - tp1Lots);
        double pnlInr = pnlPoints(trade, currentPrice) * tp1Lots * trade.lotSize() * trade.pointValue();

        return new ActiveTradeExecution(
            trade.direction(),
            trade.entryPrice(),
            trade.entryPrice(),
            trade.tp1Level(),
            trade.initialRiskPoints(),
            true,
            true,
            trade.stage2Active(),
            trade.tailHalfLocked(),
            trade.quantity(),
            remainingLots,
            trade.lotSize(),
            trade.pointValue(),
            trade.realizedPnL() + pnlInr,
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
        double atr = candle.high - candle.low;
        double buffer = Math.max(10.0, atr * 0.4);
        double candidateTrailingSl = shortTrade ? candle.high + buffer : candle.low - buffer;
        double tightenedSl = shortTrade
            ? Math.min(trade.trailingSL(), candidateTrailingSl)
            : Math.max(trade.trailingSL(), candidateTrailingSl);

        return new ActiveTradeExecution(
            trade.direction(),
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
            trade.initialRiskPoints(),
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.stage2Active(),
            trade.tailHalfLocked(),
            trade.quantity(),
            trade.remainingQuantity(),
            trade.lotSize(),
            trade.pointValue(),
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

        double pnlInr = pnlPoints(trade, currentPrice)
            * trade.remainingQuantity()
            * trade.lotSize()
            * trade.pointValue();
        return new TradeExit(true, pnlInr, "STOP_LOSS", currentPrice, "REAL");
    }

    public static ActiveTradeExecution updateExcursions(ActiveTradeExecution trade, double price) {
        double favorableMove = favorablePoints(trade, price);
        double adverseMove = adversePoints(trade, price);
        double risk = trade.initialRiskPoints() <= 0.0 ? 1.0 : trade.initialRiskPoints();

        return new ActiveTradeExecution(
            trade.direction(),
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
            trade.initialRiskPoints(),
            trade.tp1Hit(),
            trade.runnerActive(),
            trade.stage2Active(),
            trade.tailHalfLocked(),
            trade.quantity(),
            trade.remainingQuantity(),
            trade.lotSize(),
            trade.pointValue(),
            trade.realizedPnL(),
            Math.max(trade.mfe(), favorableMove),
            Math.max(trade.mae(), adverseMove),
            Math.max(trade.peakFavorableR(), favorableMove / risk),
            trade.trailingSL()
        );
    }

    public int totalUnits() {
        return unitsForLots(quantity);
    }

    public int remainingUnits() {
        return unitsForLots(remainingQuantity);
    }

    public int unitsForLots(int lots) {
        return Math.max(0, lots) * Math.max(1, lotSize);
    }

    public double markToMarketPnl(double currentPrice) {
        return pnlPoints(this, currentPrice) * remainingQuantity * lotSize * pointValue;
    }

    private static boolean isShort(ActiveTradeExecution trade) {
        return "SHORT".equalsIgnoreCase(trade.direction());
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
