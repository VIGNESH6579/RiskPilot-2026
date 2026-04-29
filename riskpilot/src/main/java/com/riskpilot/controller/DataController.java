package com.riskpilot.controller;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.ActiveTradeExecution;
import com.riskpilot.model.MarketTick;
import com.riskpilot.model.TradeLog;
import com.riskpilot.model.TradeView;
import com.riskpilot.model.TradingSessionSnapshot;
import com.riskpilot.repository.TradeLogRepository;
import com.riskpilot.service.MarketDataStateService;
import com.riskpilot.service.MarketSessionService;
import com.riskpilot.service.RiskEngine;
import com.riskpilot.service.SessionStateManager;
import com.riskpilot.service.ShadowExecutionEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/data")
@CrossOrigin
@RequiredArgsConstructor
public class DataController {

    private static final DateTimeFormatter EXPIRY_FORMAT =
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final MarketDataStateService marketDataStateService;
    private final TradeLogRepository tradeLogRepository;
    private final MarketSessionService marketSessionService;
    private final ShadowExecutionEngine shadowExecutionEngine;
    private final RiskPilotProperties riskPilotProperties;
    private final RiskEngine riskEngine;
    private final SessionStateManager sessionStateManager;

    @GetMapping("/health")
    public Map<String, Object> health() {
        MarketDataStateService.MarketDataSnapshot snapshot = marketDataStateService.snapshot();
        boolean marketOpen = marketSessionService.isMarketOpen();
        String priceSource = marketDataStateService.resolvePriceSource(
            marketOpen,
            riskPilotProperties.getInfra().getHeartbeat().getMaxSilenceMs()
        );
        boolean isLive = "LIVE".equals(priceSource);
        MarketTick lastTick = snapshot.lastTick();
        Double liveSpot = (isLive && lastTick != null && lastTick.price() > 0.0)
            ? lastTick.price()
            : null;
        // Always expose the most recent observed tick price so the UI can
        // display the last close when the market is closed or the feed is
        // stale, even though `spot` itself is only set when LIVE.
        Double lastPrice = (lastTick != null && lastTick.price() > 0.0) ? lastTick.price() : null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", snapshot.transport() != null ? snapshot.transport().name() : null);
        payload.put("price", liveSpot);
        payload.put("spot", liveSpot);
        payload.put("lastPrice", lastPrice);
        payload.put("lastPriceAt", snapshot.lastAcceptedAt());
        payload.put("sourceAgeMs", lastTick != null ? lastTick.sourceAgeMs() : null);
        payload.put("lastFreshTickAt", snapshot.lastAcceptedAt());
        payload.put("healthy", marketOpen && snapshot.connected() && snapshot.subscribed() && snapshot.ready() && !snapshot.feedBlocked() && lastTick != null);
        payload.put("sessionActive", marketOpen);
        payload.put("feedBlocked", snapshot.feedBlocked());
        payload.put("feedBlockReason", snapshot.blockReason());
        payload.put("ready", snapshot.ready());
        payload.put("parseFailureCount", snapshot.parseFailureCount());
        payload.put("marketStatus", marketOpen ? "OPEN" : "CLOSED");
        payload.put("priceSource", priceSource);
        payload.put("expiryDate", nextExpiryDate());
        payload.put("symbol", riskPilotProperties.getInstrument().getSymbol());
        payload.put("currentEquity", riskEngine.snapshot().currentEquity());
        payload.put("realizedPnlInr", riskEngine.snapshot().realizedPnlInr());
        payload.put("unrealizedPnlInr", riskEngine.snapshot().unrealizedPnlInr());
        payload.put("activeTrade", buildActiveTradeView(lastPrice));
        payload.put("rejectReasonCounts", shadowExecutionEngine.getTopRejectReasons());
        payload.put("operationalStatus", shadowExecutionEngine.isOperationallyBlocked() ? "OPERATIONALLY_BLOCKED" : "ACTIVE");
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    @GetMapping("/trade-history")
    public List<Map<String, Object>> tradeHistory(@RequestParam(defaultValue = "25") int limit) {
        int capped = Math.max(1, Math.min(limit, 200));

        try {
            List<TradeLog> logs = tradeLogRepository.findTop200ByGateDecisionOrderBySignalTimeDesc("ALLOW");
            return logs.stream()
                .limit(capped)
                .map(log -> TradeView.fromTradeLog(log).toMap())
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String nextExpiryDate() {
        DayOfWeek expiryDow = parseExpiryDay(riskPilotProperties.getInstrument().getExpiryDayOfWeek());
        LocalDate today = marketSessionService.nowIst().toLocalDate();
        int daysAhead = (expiryDow.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        // If today is the expiry day and the market is already closed, roll to the next week.
        if (daysAhead == 0 && !marketSessionService.isMarketOpen()) {
            daysAhead = 7;
        }
        return today.plusDays(daysAhead).format(EXPIRY_FORMAT);
    }

    private DayOfWeek parseExpiryDay(String configured) {
        if (configured == null || configured.isBlank()) {
            return DayOfWeek.THURSDAY;
        }
        try {
            return DayOfWeek.valueOf(configured.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ex) {
            return DayOfWeek.THURSDAY;
        }
    }

    private Map<String, Object> buildActiveTradeView(Double currentPrice) {
        TradingSessionSnapshot state = sessionStateManager.getSnapshot();
        ActiveTradeExecution trade = state.activeTradeReference();
        Map<String, Object> view = new LinkedHashMap<>();
        if (!state.tradeActive() || trade == null) {
            view.put("active", false);
            return view;
        }
        view.put("active", true);
        view.put("direction", trade.direction());
        view.put("entryPrice", trade.entryPrice());
        view.put("stopLoss", trade.stopLoss());
        view.put("trailingStopLoss", trade.trailingSL());
        view.put("tp1Level", trade.tp1Level());
        view.put("quantityLots", trade.quantity());
        view.put("remainingLots", trade.remainingQuantity());
        view.put("lotSize", trade.lotSize());
        view.put("tp1Hit", trade.tp1Hit());
        view.put("runnerActive", trade.runnerActive());
        view.put("realizedPnl", trade.realizedPnL());
        view.put("mfe", trade.mfe());
        view.put("mae", trade.mae());
        if (currentPrice != null && currentPrice > 0.0) {
            view.put("currentPrice", currentPrice);
            view.put("unrealizedPnl", trade.markToMarketPnl(currentPrice));
        }
        return view;
    }
}
