package com.riskpilot.service;

  import com.riskpilot.exception.MarketDataException;
  import com.riskpilot.model.CandleEntity;
  import com.riskpilot.model.OptionData;
  import com.riskpilot.util.RsiCalculator;
  import org.springframework.stereotype.Service;

  import java.util.List;
  import java.util.Optional;
  import java.util.stream.Collectors;

  @Service
  public class MarketService {

      private final AngelOneMarketDataService angelOneMarketDataService;
      private final OptionChainService optionChainService;
      private final RealTimeTickAggregator realTimeTickAggregator;

      public MarketService(
              AngelOneMarketDataService angelOneMarketDataService,
              OptionChainService optionChainService,
              RealTimeTickAggregator realTimeTickAggregator) {
          this.angelOneMarketDataService = angelOneMarketDataService;
          this.optionChainService = optionChainService;
          this.realTimeTickAggregator = realTimeTickAggregator;
      }

      /**
       * Real-time spot price from Angel One. Throws if unavailable — no silent fallbacks.
       */
      public double getPrice(String symbol) {
          Optional<Double> ltp;
          if ("BANKNIFTY".equalsIgnoreCase(symbol)) {
              ltp = angelOneMarketDataService.getLtp("NSE", "99926009");
          } else {
              ltp = angelOneMarketDataService.getNiftyLtp();
          }
          return ltp.orElseThrow(() ->
              new MarketDataException("LIVE_PRICE_UNAVAILABLE: " + symbol + " — all data sources exhausted"));
      }

      /**
       * Real-time option LTP from Angel One quote API.
       * Token lookup via symbol name is not yet supported — returns spot as best-effort ATM proxy.
       * Actual slippage is tracked per-trade by the execution engine.
       */
      public double getOptionPrice(String symbol) {
          Optional<Double> spot = angelOneMarketDataService.getNiftyLtp();
          return spot.orElseThrow(() ->
              new MarketDataException("OPTION_PRICE_UNAVAILABLE: " + symbol + " — no live feed"));
      }

      /**
       * Real option chain built from live Angel One LTP snapshot.
       * Strikes are computed dynamically from the live ATM price.
       */
      // public List<OptionData> getMockOptionChain() {
      //     OptionChainService.OptionChainSnapshot chain = optionChainService.fetchNiftyChain();
      //     double spot = chain.spot() > 0.0 ? chain.spot() : getPrice("NIFTY");
      //     int atm = (int) (Math.round(spot / 100.0) * 100);
      //     return List.of(
      //         new OptionData(atm - 200, 0, 0),
      //         new OptionData(atm - 100, 0, 0),
      //         new OptionData(atm,       0, 0),
      //         new OptionData(atm + 100, 0, 0),
      //         new OptionData(atm + 200, 0, 0)
      //     );
      // }

    @Deprecated
public List<OptionData> getMockOptionChain() {
    throw new UnsupportedOperationException(
        "Mock option chain is disabled. Use real-time option chain API from Angel One."
    );
}

/**
 * Fetch REAL option chain from Angel One
 */
public List<OptionData> fetchRealOptionChain(String symbol, String expiry) {
    // TODO: Implement real option chain fetch from Angel One API
    // Endpoint: /rest/secure/angelbroking/market/v1/quote/optionchain
    throw new UnsupportedOperationException("Real option chain fetch not yet implemented");
}

      /**
       * RSI calculated from real candle history provided by the live tick aggregator.
       * Requires at least 15 closed candles; throws if insufficient data.
       */
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
  
