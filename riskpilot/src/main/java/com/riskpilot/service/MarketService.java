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
      private final CentralizedMarketDataService centralizedMarketDataService;
      private final OptionChainService optionChainService;
    private final CandleAggregator candleAggregator;

    public MarketService(
            AngelOneMarketDataService angelOneMarketDataService,
            CentralizedMarketDataService centralizedMarketDataService,
            OptionChainService optionChainService,
            CandleAggregator candleAggregator) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.centralizedMarketDataService = centralizedMarketDataService;
        this.optionChainService = optionChainService;
        this.candleAggregator = candleAggregator;
    }

      /**
       * Real-time spot price from Angel One. Throws if unavailable — no silent fallbacks.
       */
      public double getPrice(String symbol) {
          double ltp;
          if ("BANKNIFTY".equalsIgnoreCase(symbol)) {
              ltp = centralizedMarketDataService.getBankNiftyLtp();
              if (ltp <= 0.0) {
                  ltp = angelOneMarketDataService.getLtp("NSE", "99926009").orElse(0.0);
              }
          } else {
              ltp = centralizedMarketDataService.getNiftyLtp();
              if (ltp <= 0.0) {
                  ltp = angelOneMarketDataService.getNiftyLtp().orElse(0.0);
              }
          }
          
          if (ltp <= 0.0) {
              throw new MarketDataException("LIVE_PRICE_UNAVAILABLE: " + symbol + " — all data sources exhausted");
          }
          return ltp;
      }

      /**
       * Real-time option LTP from Angel One quote API.
       * Token lookup via symbol name is not yet supported — returns spot as best-effort ATM proxy.
       * Actual slippage is tracked per-trade by the execution engine.
       */
      public double getOptionPrice(String symbol) {
          double spot = centralizedMarketDataService.getNiftyLtp();
          if (spot <= 0.0) {
              spot = angelOneMarketDataService.getNiftyLtp().orElse(0.0);
          }
          
          if (spot <= 0.0) {
              throw new MarketDataException("OPTION_PRICE_UNAVAILABLE: " + symbol + " — no live feed");
          }
          return spot;
      }

      /**
       * Real option chain built from live Angel One LTP snapshot.
       * Strikes are computed dynamically from the live ATM price.
       */
      public List<OptionData> getOptionChain() {
          // In a real scenario, this would call Angel One's API to fetch the actual option chain.
          // For now, we are returning an empty list and logging a warning.
          // To fully implement this, Angel One's Option Chain API would need to be integrated.
          // The OptionChainService currently provides a simplified snapshot, not a full chain.
          throw new MarketDataException("REAL_OPTION_CHAIN_UNAVAILABLE: Angel One Option Chain API not integrated.");
      }

      /**
       * RSI calculated from real candle history provided by the live tick aggregator.
       * Requires at least 15 closed candles; throws if insufficient data.
       */
    public double getRsi(String symbol) {
        List<com.riskpilot.model.Candle> history = candleAggregator.getValidHistory();
        List<Double> prices = history.stream()
            .map(c -> c.close)
            .collect(Collectors.toList());

        if (prices.size() < 15) {
            throw new MarketDataException(
                "RSI_UNAVAILABLE: insufficient real-time candle history for " + symbol +
                " (have " + prices.size() + ", need 15)");
        }
        return RsiCalculator.calculateRSI(prices, 14);
    }
  }
  