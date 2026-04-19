package com.riskpilot.service;

import com.riskpilot.dto.OptionLevels;
import com.riskpilot.exception.TradingBlockedException;
import com.riskpilot.model.Signal;
import org.springframework.stereotype.Service;

@Service
public class SignalService {

    private final RiskService riskService;
    private final VixService vixService;
    private final MarketService marketService;
    private final OptionChainService optionChainService;

    public SignalService(RiskService riskService,
                         VixService vixService,
                         MarketService marketService,
                         OptionChainService optionChainService) {
        this.riskService = riskService;
        this.vixService = vixService;
        this.marketService = marketService;
        this.optionChainService = optionChainService;
    }

    public Signal generateSignal() {

        double vix = vixService.getCurrentVix();

        if (riskService.isTradingBlocked(vix)) {
            throw new TradingBlockedException("High VIX. No trade.");
        }

        double price = marketService.getPrice("NIFTY");
        double rsi = marketService.getRsi("NIFTY");

        OptionLevels levels = optionChainService.analyzeOptionChain("NIFTY");

        double support = levels.getSupport();
        double resistance = levels.getResistance();
        double pcr = levels.getPcr();

        // 🔥 proximity check
        boolean nearSupport = Math.abs(price - support) <= 100;
        boolean nearResistance = Math.abs(price - resistance) <= 100;

        // 🔥 RSI conditions
        boolean rsiBuy = rsi < 35;
        boolean rsiSell = rsi > 65;

        String direction = "BUY";

        // 🔥 FINAL SIGNAL LOGIC (RSI + OI + PCR)
        if (nearSupport && pcr > 1.0 && rsiBuy) {
            direction = "BUY";   // BUY CE
        } else if (nearResistance && pcr < 1.0 && rsiSell) {
            direction = "SELL";  // BUY PE
        } else {
            direction = "HOLD";
        }

        // ❌ No trade case
        if (direction.equals("HOLD")) {
            Signal s = new Signal();
            s.setSymbol("NIFTY");
            s.setDirection("NO TRADE");
            s.setConfidence(3);
            return s;
        }

        // 🔥 STRIKE SELECTION
        int atm = (int) (Math.round(price / 50) * 50);
        String optionType = direction.equals("BUY") ? "CE" : "PE";
        String symbol = "NIFTY " + atm + " " + optionType;

        double optionPrice = marketService.getOptionPrice(symbol);

        // 🔥 STOP LOSS (VIX based)
        double slPercent = (vix > 18) ? 0.30 : 0.25;
        double stopLoss = optionPrice * (1 - slPercent);

        // 🔥 TARGET (1.5 RR)
        double risk = optionPrice - stopLoss;
        double target = optionPrice + (risk * 1.5);

        // 🔥 CONFIDENCE
        int confidence = calculateConfidence(
                nearSupport,
                nearResistance,
                pcr,
                vix,
                rsi
        );

        // ❌ filter weak trades
        if (confidence < 7) {
            Signal s = new Signal();
            s.setSymbol("NIFTY");
            s.setDirection("NO TRADE");
            s.setConfidence(confidence);
            return s;
        }

        Signal signal = new Signal();
        signal.setSymbol(symbol);
        signal.setDirection(direction);
        signal.setEntry(optionPrice);
        signal.setTarget(target);
        signal.setStopLoss(stopLoss);
        signal.setConfidence(confidence);

        return signal;
    }

    private int calculateConfidence(boolean nearSupport,
                                    boolean nearResistance,
                                    double pcr,
                                    double vix,
                                    double rsi) {

        int score = 0;

        // Structure
        if (nearSupport || nearResistance) score += 2;

        // PCR strength
        if (pcr > 1.2 || pcr < 0.8) score += 2;

        // VIX quality
        if (vix >= 15 && vix <= 18) score += 3;
        else if (vix < 22) score += 2;

        // RSI confirmation
        if (rsi < 35 || rsi > 65) score += 3;

        return Math.min(score, 10);
    }
}