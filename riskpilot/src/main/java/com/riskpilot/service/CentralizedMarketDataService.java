package com.riskpilot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * CentralizedMarketDataService — thin read-only cache.
 *
 * Previously owned a 3-second REST polling scheduler. That scheduler has been
 * removed now that AngelSmartStreamClient provides a live WebSocket feed.
 *
 * The LTP values here are populated by MarketDataStateService (updated by the
 * WebSocket listener on every tick) and read by any service that needs the
 * latest price without wiring to MarketDataStateService directly.
 *
 * isDataFresh() now delegates to MarketDataStateService so both staleness checks
 * share the same clock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CentralizedMarketDataService {

    private final MarketDataStateService marketDataStateService;

    @Value("${TRADING_SYMBOL:NIFTY}")
    private String tradingSymbol;

    /**
     * Current NIFTY LTP — sourced from WebSocket ticks via MarketDataStateService.
     * Returns 0.0 if no tick has been received yet (pre-market or feed not connected).
     */
    public double getNiftyLtp() {
        return marketDataStateService.getLtp();
    }

    /**
     * For callers that used getBankNiftyLtp() — returns the same underlying spot
     * because MarketDataStateService stores whichever symbol AngelSmartStreamClient
     * is subscribed to. When TRADING_SYMBOL=BANKNIFTY the WebSocket is subscribed
     * to the BANKNIFTY token and all updates go through the same NiftySpot slot.
     */
    public double getBankNiftyLtp() {
        return getNiftyLtp();
    }

    /**
     * LTP for whatever symbol is configured — always the same slot now that
     * the WebSocket only subscribes to one symbol at a time.
     */
    public double getTradingSymbolLtp() {
        return getNiftyLtp();
    }

    /**
     * Freshness check — delegates to MarketDataStateService which tracks the
     * timestamp of the last WebSocket tick. Threshold: 10 seconds.
     */
    public boolean isDataFresh() {
        return marketDataStateService.isNiftyAvailable();
    }

    /** Epoch-ms of the last received tick. */
    public long getLastUpdateEpochMs() {
        return System.currentTimeMillis() - marketDataStateService.getLastTickAgeMs();
    }
}
