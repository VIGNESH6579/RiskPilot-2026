package com.riskpilot.service;

import com.riskpilot.model.Candle;
import com.riskpilot.model.Signal;

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BacktestEngine {

    private final TrapEngine trapEngine;

    public BacktestEngine(TrapEngine trapEngine) {
        this.trapEngine = trapEngine;
    }

    private ActiveSimTrade activeTrade = null;
    private LocalDateTime lastTradeTime = null;
    private int tradesToday = 0;
    private LocalDate currentDay = null;
    
    private double dailyOpen = 0.0;
    private double orHigh = Double.MIN_VALUE;
    private double orLow = Double.MAX_VALUE;
    private boolean fastCandleExists = false;
    private boolean dailyRegimeLocked = false;
    private String currentRegime = "DEAD";
    private double dayOrExpansion = 0.0;

    private final List<Candle> history = new ArrayList<>();
    
    public List<TradeResult> tradeLog = new ArrayList<>();

    public BacktestResult runFromCsv(String filePath, String monthFilter, double slippageEntry, double slippageExit, String regimeFilter, 
                                     double tp1Offset, double tp1Fraction, double earlyKillMaeLimit, double earlyKillFraction) {
        List<Candle> candles = parseCsv(filePath);
        
        tradeLog.clear();
        activeTrade = null; lastTradeTime = null;
        history.clear();
        
        run(candles, monthFilter, slippageEntry, slippageExit, regimeFilter, tp1Offset, tp1Fraction, earlyKillMaeLimit, earlyKillFraction);
        
        double totalPLSum = 0;
        for (TradeResult t : tradeLog) totalPLSum += t.pl;
        int totalTrades = tradeLog.size();
        
        double avgTradePL = totalTrades > 0 ? totalPLSum / totalTrades : 0.0;
        double approxAvgRisk = 85.0; 
        double rTrade = totalTrades == 0 ? 0.0 : avgTradePL / approxAvgRisk;

        return new BacktestResult(
            totalTrades, 
            avgTradePL,
            rTrade,
            new ArrayList<>(tradeLog)
        );
    }

    private List<Candle> parseCsv(String filePath) {
        List<Candle> candles = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;  br.readLine(); 
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] values = line.split(",");
                if (values.length < 5) continue;
                String[] dateParts = values[0].split(" "); 
                if (dateParts.length < 2) continue;
                candles.add(new Candle(dateParts[0].trim(), dateParts[1].substring(0, 5).trim(),
                        Double.parseDouble(values[1].trim()), Double.parseDouble(values[2].trim()),
                        Double.parseDouble(values[3].trim()), Double.parseDouble(values[4].trim())));
            }
        } catch (Exception e) {}
        candles.sort(Comparator.comparing(c -> LocalDateTime.parse(c.date + "T" + c.time)));
        return candles;
    }

    public void run(List<Candle> candles, String monthFilter, double slippageEntry, double slippageExit, String regimeFilter, 
                    double tp1Offset, double tp1Fraction, double earlyKillMaeLimit, double earlyKillFraction) {
        for (Candle c : candles) {
            history.add(c);
            LocalDateTime candleTime = LocalDateTime.parse(c.date + "T" + c.time);
            LocalTime time = candleTime.toLocalTime();
            
            if (currentDay == null || !candleTime.toLocalDate().equals(currentDay)) {
                currentDay = candleTime.toLocalDate();
                tradesToday = 0;
                dailyOpen = c.open;
                orHigh = Double.MIN_VALUE;
                orLow = Double.MAX_VALUE;
                fastCandleExists = false;
                dailyRegimeLocked = false;
                currentRegime = "DEAD";
                dayOrExpansion = 0.0;
            }

            if (!time.isAfter(LocalTime.of(9, 45))) {
                if (c.high > orHigh) orHigh = c.high;
                if (c.low < orLow) orLow = c.low;
            }
            if (!time.isAfter(LocalTime.of(10, 15))) {
                if (Math.abs(c.high - c.low) >= 20.0) fastCandleExists = true;
            }
            
            if (!dailyRegimeLocked && !time.isBefore(LocalTime.of(10, 20))) {
                dayOrExpansion = orHigh - orLow;
                boolean activeMarket = (dayOrExpansion >= 40.0) && (Math.abs(c.close - dailyOpen) >= 50.0) && fastCandleExists;
                currentRegime = activeMarket ? "TREND" : "CHOP";
                dailyRegimeLocked = true;
            }

            if (activeTrade != null && activeTrade.active) {
                if (c.low < activeTrade.maxLow) activeTrade.maxLow = c.low;
                if (c.high > activeTrade.maxHighEx) activeTrade.maxHighEx = c.high;
            }

            if (time.isAfter(LocalTime.of(15, 10))) {
                 if (activeTrade != null && activeTrade.active) {
                     forceCloseRemaining(c, slippageExit);
                 }
                 continue;
            }

            if (activeTrade != null && activeTrade.active) {
                activeTrade.candlesElapsed++;
                checkExit(c, slippageExit, tp1Fraction, earlyKillMaeLimit, earlyKillFraction);
                continue; 
            }

            if (time.isBefore(LocalTime.of(10, 15)) || time.isAfter(LocalTime.of(13, 30))) continue;
            if (activeTrade != null && activeTrade.active) continue;
            if (tradesToday >= 2) continue;
            if (lastTradeTime != null && Duration.between(lastTradeTime, candleTime).toMinutes() < 15) continue;

            Signal signal = generateSignal(c);
            if (signal == null) continue;

            if (regimeFilter.equals("TREND") && currentRegime.equals("CHOP")) continue; 
            
            if (dayOrExpansion < 120.0) continue; // HARD VOLATILITY GATE (Non-Negotiable)

            double tradeMultiplier = time.isBefore(LocalTime.of(12, 0)) ? 1.0 : 0.35;
            
            double exactEntryExpected = signal.getEntry();
            double slippedEntry = exactEntryExpected - slippageEntry;

            activeTrade = new ActiveSimTrade(
                    false, 
                    slippedEntry,
                    signal.getStopLoss(),
                    exactEntryExpected - tp1Offset
            );
            
            activeTrade.structuralSl = activeTrade.sl - exactEntryExpected;
            activeTrade.maxLow = slippedEntry;
            activeTrade.maxHighEx = slippedEntry;
            activeTrade.orExpansion = dayOrExpansion; 
            activeTrade.entryTime = time;
            activeTrade.initialExposure = tradeMultiplier;
            activeTrade.currentExposure = tradeMultiplier;
            
            tradesToday++;
            lastTradeTime = candleTime;
        }
    }
    
    private void forceCloseRemaining(Candle c, double slippageExit) {
        if (!activeTrade.active) return;
        
        double exitPrice = c.close + slippageExit; 
        double distanceCaptured = (activeTrade.entry - exitPrice);
        
        activeTrade.realizedPL += distanceCaptured * activeTrade.currentExposure;
        activeTrade.currentExposure = 0.0;
        activeTrade.active = false;
        
        tradeLog.add(new TradeResult(activeTrade.realizedPL, activeTrade.orExpansion, activeTrade.tp1Hit && distanceCaptured > 0, activeTrade.entryTime)); 
    }

    private void checkExit(Candle c, double slippageExit, double tp1Fraction, double earlyKillMaeLimit, double earlyKillFraction) {
        double mae = activeTrade.maxHighEx - activeTrade.entry;

        if (!activeTrade.earlyKillTriggered && activeTrade.candlesElapsed <= 2 && mae > earlyKillMaeLimit) {
            double exitPrice = c.close + slippageExit; 
            double distanceCaptured = activeTrade.entry - exitPrice;
            
            double killExposure = Math.min(activeTrade.currentExposure, earlyKillFraction * activeTrade.initialExposure);
            activeTrade.realizedPL += distanceCaptured * killExposure;
            activeTrade.currentExposure -= killExposure;
            activeTrade.earlyKillTriggered = true;
            
            if (activeTrade.currentExposure <= 0.001) {
                activeTrade.active = false;
                tradeLog.add(new TradeResult(activeTrade.realizedPL, activeTrade.orExpansion, false, activeTrade.entryTime));
                return;
            }
        }

        if (c.high >= activeTrade.sl) {
            double slippedSl = activeTrade.sl + slippageExit;
            double distanceCaptured = (activeTrade.entry - slippedSl);
            
            activeTrade.realizedPL += distanceCaptured * activeTrade.currentExposure;
            activeTrade.currentExposure = 0.0;
            
            activeTrade.active = false;
            tradeLog.add(new TradeResult(activeTrade.realizedPL, activeTrade.orExpansion, activeTrade.tp1Hit && distanceCaptured > 0, activeTrade.entryTime));
        } 
        else if (!activeTrade.tp1Hit && c.low <= activeTrade.tp1Target) {
            activeTrade.tp1Hit = true;
            double distanceCaptured = (activeTrade.entry - activeTrade.tp1Target) - 2.0; 
            
            double exitExposure = Math.min(activeTrade.currentExposure, tp1Fraction * activeTrade.initialExposure);
            activeTrade.realizedPL += distanceCaptured * exitExposure;
            activeTrade.currentExposure -= exitExposure;
            
            activeTrade.sl = activeTrade.entry;
            
            if (activeTrade.currentExposure <= 0.001) {
                activeTrade.active = false;
                tradeLog.add(new TradeResult(activeTrade.realizedPL, activeTrade.orExpansion, false, activeTrade.entryTime));
                return;
            }
        }
        
        if (activeTrade.tp1Hit) {
            double trailingAnchor = c.high + 10.0;
            if (trailingAnchor < activeTrade.sl) {
                activeTrade.sl = trailingAnchor;
            }
        }
    }
    
    private Signal generateSignal(Candle c) {
        if (history.size() < 20) return null;
        List<Candle> priorStructure = history.subList(history.size() - 13, history.size() - 2);
        double maxHigh = priorStructure.stream().mapToDouble(x -> x.high).max().orElse(c.close);
        double minLow = priorStructure.stream().mapToDouble(x -> x.low).min().orElse(c.close);
        double range = maxHigh - minLow;
        if (range < (c.close * 0.002)) return null; 
        return trapEngine.detectTrap(history, minLow, maxHigh, 16.5);
    }

    public record TradeResult(double pl, double orExpansion, boolean isRunnerWin, LocalTime entryTime) {}
    
    public record BacktestResult(int totalTrades, double avgTradePL, double rTrade, List<TradeResult> logs) {}

    private static class ActiveSimTrade {
        boolean active = true;
        boolean isLong;
        double entry;
        LocalTime entryTime;
        double sl;
        double structuralSl;
        double tp1Target;
        int candlesElapsed = 0; 
        boolean tp1Hit = false;
        
        double maxLow;
        double maxHighEx;
        double orExpansion;
        
        double initialExposure = 1.0;
        double currentExposure = 1.0;
        double realizedPL = 0.0;
        boolean earlyKillTriggered = false;

        ActiveSimTrade(boolean isLong, double entry, double sl, double target) {
            this.isLong = isLong;
            this.entry = entry;
            this.sl = sl;
            this.tp1Target = target;
        }
    }
}
