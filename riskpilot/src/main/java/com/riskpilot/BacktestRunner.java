package com.riskpilot;

import com.riskpilot.service.BacktestEngine;
import com.riskpilot.service.TrapEngine;

import java.util.List;

public class BacktestRunner {

    public static void main(String[] args) {
        TrapEngine trapEngine = new TrapEngine(15.0, 18.0);
        BacktestEngine engine = new BacktestEngine(trapEngine);
        
        System.out.println("====================================================");
        System.out.println("   PHASE 19: FINAL SIMULATION - HIGH VOLATILITY ENGINE ");
        System.out.println("====================================================");
        
        // Exact specifications from user mandate:
        // OR > 120
        // Time Filter: <12:00 = 100%, 12:00-13:30 = 35% scaling natively embedded in engine!
        // 20% TP1 (+18 offset), 80% Tail, Early Kill Limit > 25 MAE -> 50% Reduction.
        
        BacktestEngine.BacktestResult rBase = engine.runFromCsv(
                "banknifty_5m.csv", "ALL", 5.0, 5.0, "TREND", 18.0, 0.2, 999.0, 1.0
        );
        
        System.out.println(">>> FINAL SYSTEM VERIFICATION ARCHITECTURE");
        
        List<BacktestEngine.TradeResult> logs = rBase.logs();
        double totalPL = 0;
        int activeTradeCount = logs.size();
        
        for (BacktestEngine.TradeResult t : logs) totalPL += t.pl();
        
        // Let's determine Max Drawdown natively
        double peakPL = 0;
        double currentDrawdown = 0;
        double maxDrawdown = 0;
        double runningPL = 0;
        
        for (BacktestEngine.TradeResult t : logs) {
            runningPL += t.pl();
            if (runningPL > peakPL) {
                peakPL = runningPL;
            }
            currentDrawdown = peakPL - runningPL;
            if (currentDrawdown > maxDrawdown) {
                maxDrawdown = currentDrawdown;
            }
        }
        
        System.out.printf("    Total Valid Executions    : %d trades\n", activeTradeCount);
        double rTrade = activeTradeCount > 0 ? (totalPL / activeTradeCount) / 85.0 : 0.0;
        System.out.printf("    NEW Scaled Expected R     : %.2f R\n", rTrade);
        System.out.printf("    Maximum Drawdown tracking : %.2f pts\n", maxDrawdown);
    }
}
