# No Trades Diagnosis

RiskPilot now treats Angel One as the only live NIFTY market-data source.

The live trading path is:

1. `AngelTickStreamClient` polls Angel One LTP once per second.
2. `OptionChainService` returns `ANGELONE_LTP` only when Angel One provides a fresh live price.
3. `CandleAggregator` receives only live Angel ticks during market hours.
4. `ShadowExecutionEngine` evaluates the latest closed candle once per candle and never replays stale cache as a live tick.

If Angel One credentials or tokens are unavailable, the API reports `ANGELONE_UNAVAILABLE` with `spot=0.0`; the engine marks the feed unstable instead of generating trades from fallback prices.

For live India VIX gating, configure `ANGEL_INDIA_VIX_TOKEN`. Without that token, VIX-dependent decisions are blocked instead of using a fallback value.
