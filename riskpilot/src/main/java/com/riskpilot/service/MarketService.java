package com.riskpilot.service;

import com.riskpilot.exception.MarketDataException;
import com.riskpilot.model.CandleEntity;
import com.riskpilot.util.RsiCalculator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MarketService {

    private final AngelOneMarketDataService angelOneMarketDataService;
    private final RealTimeTickAggregator realTimeTickAggregator;

    public MarketService(
        AngelOneMarketDataService angelOneMarketDataService,
        OptionChainService optionChainService,
        RealTimeTickAggregator realTimeTickAggregator
    ) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.realTimeTickAggregator = realTimeTickAggregator;
    }

    public double getPrice(String symbol) {
        Optional<Double> ltp;
        if ("BANKNIFTY".equalsIgnoreCase(symbol)) {
            ltp = angelOneMarketDataService.getLtp("NSE", "99926009");
        } else {
            ltp = angelOneMarketDataService.getNiftyLtp();
        }
        return ltp.orElseThrow(() ->
            new MarketDataException("LIVE_PRICE_UNAVAILABLE: " + symbol + " - all data sources exhausted"));
    }

    public double getOptionPrice(String symbol) {
        throw new MarketDataException(
            "OPTION_PRICE_UNAVAILABLE: option token lookup is not implemented for " + symbol);
    }

    public double getRsi(String symbol) {
        List<CandleEntity> history = realTimeTickAggregator.getCandleHistory(20);
        List<Double> prices = history.stream()
            .map(c -> c.getClosePrice().doubleValue())
            .collect(Collectors.toList());

        if (prices.size() < 15) {
            throw new MarketDataException(
                "RSI_UNAVAILABLE: insufficient real-time candle history for " + symbol +
                " (have " + prices.size() + ", need 15)");
        }
        return RsiCalculator.calculateRSI(prices, 14);
    }
}
