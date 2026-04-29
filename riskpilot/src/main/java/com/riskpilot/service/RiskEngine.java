package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.ActiveTradeExecution;
import com.riskpilot.repository.TradeRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RiskEngine {

    private final RiskPilotProperties properties;
    private final TradeRepository tradeRepository;
    private final MarketSessionService marketSessionService;

    private final AtomicReference<EquitySnapshot> equityRef = new AtomicReference<>();
    private final AtomicInteger consecutiveLosses = new AtomicInteger(0);

    public RiskEngine(
        RiskPilotProperties properties,
        TradeRepository tradeRepository,
        MarketSessionService marketSessionService
    ) {
        this.properties = properties;
        this.tradeRepository = tradeRepository;
        this.marketSessionService = marketSessionService;
    }

    @PostConstruct
    public void initialize() {
        refresh(null, null);
    }

    public synchronized EquitySnapshot refresh(ActiveTradeExecution activeTrade, Double currentPrice) {
        LocalDate sessionDate = marketSessionService.sessionDate(marketSessionService.now());
        BigDecimal realizedToday = tradeRepository.getRealizedPnLBetween(
            properties.getInstrument().getSymbol(),
            sessionDate.atStartOfDay(),
            sessionDate.plusDays(1).atStartOfDay()
        );
        double realizedPnl = realizedToday == null ? 0.0 : realizedToday.doubleValue();
        double unrealizedPnl = activeTrade == null || currentPrice == null
            ? 0.0
            : activeTrade.markToMarketPnl(currentPrice);
        double equity = properties.getAccount().getInitialCapital() + realizedPnl + unrealizedPnl;
        EquitySnapshot snapshot = new EquitySnapshot(
            properties.getAccount().getInitialCapital(),
            equity,
            realizedPnl,
            unrealizedPnl,
            realizedPnl <= -(properties.getAccount().getInitialCapital() * properties.getAccount().getDailyLossLimitPct() / 100.0),
            consecutiveLosses.get()
        );
        equityRef.set(snapshot);
        return snapshot;
    }

    public EquitySnapshot snapshot() {
        EquitySnapshot snapshot = equityRef.get();
        return snapshot == null ? refresh(null, null) : snapshot;
    }

    public synchronized void recordClosedTrade(double pnlInr) {
        if (pnlInr < 0.0) {
            consecutiveLosses.incrementAndGet();
        } else {
            consecutiveLosses.set(0);
        }
        refresh(null, null);
    }

    public synchronized void resetForSessionStart() {
        consecutiveLosses.set(0);
        refresh(null, null);
    }

    public record EquitySnapshot(
        double initialCapital,
        double currentEquity,
        double realizedPnlInr,
        double unrealizedPnlInr,
        boolean dailyLossLimitBreached,
        int consecutiveLosses
    ) {}
}
