package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CentralizedMarketDataService unifies market data fetching to "use the token wisely".
 * Instead of multiple services polling Angel One separately, this service fetches
 * the configured trading symbol at a synchronized 1-second interval.
 *
 * BUG-FIX: getBankNiftyLtp() was hardcoded to return 0.0, meaning every tick fell
 * through to a direct Angel One API call when TRADING_SYMBOL=BANKNIFTY — bypassing
 * the cache and risking rate limits. This service now polls whichever symbol is
 * configured and caches it properly.
 */
@Service
public class CentralizedMarketDataService {
    private static final Logger log = LoggerFactory.getLogger(CentralizedMarketDataService.class);

    // NIFTY index token = 99926000, BANKNIFTY index token = 99926009
    private static final String NIFTY_TOKEN      = "99926000";
    private static final String BANKNIFTY_TOKEN  = "99926009";

    private final AngelOneMarketDataService angelOneMarketDataService;
    private final MarketSessionService marketSessionService;

    @Value("${TRADING_SYMBOL:NIFTY}")
    private String tradingSymbol;

    private final AtomicReference<Double> niftyLtp      = new AtomicReference<>(0.0);
    private final AtomicReference<Double> bankNiftyLtp  = new AtomicReference<>(0.0);
    private final AtomicReference<Long>   lastUpdateEpochMs = new AtomicReference<>(0L);

    public CentralizedMarketDataService(
            AngelOneMarketDataService angelOneMarketDataService,
            MarketSessionService marketSessionService) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.marketSessionService = marketSessionService;
    }

    /**
     * Polls the configured trading symbol every 1 second.
     * Always polls NIFTY; additionally polls BANKNIFTY when that is the trading symbol.
     */
    @Scheduled(fixedRate = 1000)
    public void refreshMarketData() {
        if (!marketSessionService.isMarketOpen()) {
            return;
        }

        try {
            boolean updated = false;

            // Always fetch NIFTY (used for VIX context and fallback)
            Optional<Double> nifty = angelOneMarketDataService.getNiftyLtp();
            if (nifty.isPresent() && nifty.get() > 0.0) {
                niftyLtp.set(nifty.get());
                updated = true;
                log.debug("Centralized data refreshed: NIFTY={}", niftyLtp.get());
            }

            // Fetch BANKNIFTY when it is the configured trading symbol
            boolean isBankNifty = "BANKNIFTY".equalsIgnoreCase(
                tradingSymbol != null ? tradingSymbol.trim() : "");
            if (isBankNifty) {
                Optional<Double> bankNifty = angelOneMarketDataService.getLtp("NSE", BANKNIFTY_TOKEN);
                if (bankNifty.isPresent() && bankNifty.get() > 0.0) {
                    bankNiftyLtp.set(bankNifty.get());
                    updated = true;
                    log.debug("Centralized data refreshed: BANKNIFTY={}", bankNiftyLtp.get());
                } else {
                    log.warn("BANKNIFTY LTP not available from Angel One");
                }
            }

            if (updated) {
                lastUpdateEpochMs.set(System.currentTimeMillis());
            } else {
                log.warn("No LTP available from Angel One, skipping lastUpdateEpochMs update");
            }

        } catch (Exception e) {
            log.warn("Centralized market data refresh failed: {}", e.getMessage());
        }
    }

    public double getNiftyLtp() {
        return niftyLtp.get();
    }

    /**
     * BUG-FIX: Was hardcoded to return 0.0. Now returns the cached BANKNIFTY LTP
     * that is polled every second when TRADING_SYMBOL=BANKNIFTY.
     */
    public double getBankNiftyLtp() {
        return bankNiftyLtp.get();
    }

    /**
     * Returns the LTP for the currently configured trading symbol.
     * Use this in AngelTickStreamClient instead of calling getNiftyLtp() unconditionally.
     */
    public double getTradingSymbolLtp() {
        boolean isBankNifty = "BANKNIFTY".equalsIgnoreCase(
            tradingSymbol != null ? tradingSymbol.trim() : "");
        double ltp = isBankNifty ? bankNiftyLtp.get() : niftyLtp.get();
        // If the symbol-specific cache is still 0, fall back to NIFTY as a safety net
        return ltp > 0.0 ? ltp : niftyLtp.get();
    }

    public long getLastUpdateEpochMs() {
        return lastUpdateEpochMs.get();
    }

    public boolean isDataFresh() {
        // Data must be < 5s old to be considered fresh
        return (System.currentTimeMillis() - lastUpdateEpochMs.get()) < 5000;
    }
}
