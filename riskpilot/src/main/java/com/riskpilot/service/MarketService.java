package com.riskpilot.service;

import com.riskpilot.model.OptionData;
import org.springframework.stereotype.Service;
import com.riskpilot.util.RsiCalculator;
import java.util.List;

@Service
public class MarketService {

    // 🔹 Spot price
    public double getPrice(String symbol) {
        return 22050; // mock value
    }

    // 🔹 Option premium
    public double getOptionPrice(String symbol) {

        if (symbol.contains("CE")) {
            return 120;
        } else if (symbol.contains("PE")) {
            return 110;
        }

        return 100;
    }

    // 🔹 Mock option chain
    public List<OptionData> getMockOptionChain() {
        return List.of(
                new OptionData(21800, 12000, 8000),
                new OptionData(21900, 15000, 10000),
                new OptionData(22000, 25000, 30000),
                new OptionData(22100, 40000, 15000),
                new OptionData(22200, 20000, 5000)
        );
    }




    public double getRsi(String symbol) {

        // 🔴 TEMP: mock price history (replace with API later)
        List<Double> prices = List.of(
                22000.0, 22020.0, 22010.0, 22030.0, 22050.0,
                22040.0, 22060.0, 22080.0, 22070.0, 22090.0,
                22100.0, 22080.0, 22060.0, 22040.0, 22020.0
        );

        return RsiCalculator.calculateRSI(prices, 14);
    }
}