package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CentralizedMarketDataService unifies market data fetching to "use the token wisely".
 * Instead of multiple services polling Angel One separately, this service fetches
 * NIFTY and BANKNIFTY in a single batch call (if possible) or at a synchronized interval.
 */
@Service
public class CentralizedMarketDataService {
    private static final Logger log = LoggerFactory.getLogger(CentralizedMarketDataService.class);

    private final AngelOneMarketDataService angelOneMarketDataService;
    private final MarketSessionService marketSessionService;

    private final AtomicReference<Double> niftyLtp = new AtomicReference<>(0.0);
    private final AtomicReference<Double> bankNiftyLtp = new AtomicReference<>(0.0);
    private final AtomicReference<Long> lastUpdateEpochMs = new AtomicReference<>(0L);

    public CentralizedMarketDataService(
            AngelOneMarketDataService angelOneMarketDataService,
            MarketSessionService marketSessionService) {
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.marketSessionService = marketSessionService;
    }

    /**
     * Polls market data every 1 second for low-latency updates.
     */
    @Scheduled(fixedRate = 1000)
    public void refreshMarketData() {
        if (!marketSessionService.isMarketOpen()) {
            return;
        }

        try {
            // Fetch NIFTY
            Optional<Double> nifty = angelOneMarketDataService.getNiftyLtp();
            nifty.ifPresent(niftyLtp::set);

            // Fetch BANKNIFTY (optional, but good for completeness)
            // Optional<Double> bankNifty = angelOneMarketDataService.getLtp("NSE", "99926009");
            // bankNifty.ifPresent(bankNiftyLtp::set);

            lastUpdateEpochMs.set(System.currentTimeMillis());
            log.debug("Centralized market data refreshed: NIFTY={}", niftyLtp.get());
        } catch (Exception e) {
            log.warn("Centralized market data refresh failed: {}", e.getMessage());
        }
    }

    public double getNiftyLtp() {
        return niftyLtp.get();
    }

    public double getBankNiftyLtp() {
        return bankNiftyLtp.get();
    }

    public long getLastUpdateEpochMs() {
        return lastUpdateEpochMs.get();
    }
    
    public boolean isDataFresh() {
        return (System.currentTimeMillis() - lastUpdateEpochMs.get()) < 10000;
    }
}
