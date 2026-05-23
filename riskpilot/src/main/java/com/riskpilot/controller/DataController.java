package com.riskpilot.controller;

import com.riskpilot.model.Trade;
import com.riskpilot.repository.TradeRepository;
import com.riskpilot.service.OptionChainService;
import com.riskpilot.service.VixService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// FIX: Removed @CrossOrigin — it overrides the strict origin whitelist in SecurityConfig.
@RestController
@RequestMapping("/api/v1/data")
@RequiredArgsConstructor
public class DataController {

    private static final long TRADE_HISTORY_CACHE_TTL_MS = 5_000L;

    private final VixService vixService;
    private final OptionChainService optionChainService;
    // FIX: Read trade history from DB instead of the missing CSV file
    private final TradeRepository tradeRepository;

    private final AtomicReference<List<Map<String, Object>>> tradeHistoryCache = new AtomicReference<>(null);
    private final AtomicLong tradeHistoryCacheTime = new AtomicLong(0L);

    @GetMapping("/vix")
    public double getVix() {
        return vixService.getIndiaVix();
    }

    @GetMapping("/option-chain")
    public OptionChainService.OptionChainSnapshot getOptionChain() {
        return optionChainService.fetchNiftyChain();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        OptionChainService.OptionChainSnapshot chain = optionChainService.fetchNiftyChain();
        boolean marketOpen = optionChainService.isMarketOpen();
        boolean live = chain.live() && chain.spot() > 0.0;
        String marketDataStatus = live ? "LIVE" : (marketOpen ? "UNAVAILABLE" : "MARKET_CLOSED");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionChainSource", chain.source());
        payload.put("spot", chain.spot());
        payload.put("expiry", chain.expiry());
        payload.put("previousClose", chain.previousClose());
        payload.put("support", chain.support());
        payload.put("resistance", chain.resistance());
        payload.put("live", chain.live());
        payload.put("marketOpen", marketOpen);
        payload.put("marketDataStatus", marketDataStatus);
        payload.put("marketDataHealthy", live || !marketOpen);
        payload.put("updatedEpochMs", chain.updatedEpochMs());
        payload.put("ageMs", chain.updatedEpochMs() > 0L ? System.currentTimeMillis() - chain.updatedEpochMs() : null);
        payload.put("vix", vixService.getIndiaVix());
        payload.put("healthy", live || !marketOpen);
        return payload;
    }

    @GetMapping("/trade-history")
    public List<Map<String, Object>> tradeHistory(@RequestParam(defaultValue = "25") int limit) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> cached = tradeHistoryCache.get();
        if (cached != null && (now - tradeHistoryCacheTime.get()) < TRADE_HISTORY_CACHE_TTL_MS) {
            int capped = Math.max(1, Math.min(limit, 200));
            return cached.size() > capped ? new ArrayList<>(cached.subList(0, capped)) : cached;
        }

        int capped = Math.max(1, Math.min(limit, 200));

        // FIX: Read trade history from DB (TradeRepository) instead of a CSV file.
        // The CSV never existed on Render — this was always showing empty history.
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            // getClosedTradesBySymbol returns DESC by entry_time already
            // Try both possible symbols
            List<Trade> dbTrades = new ArrayList<>();
            for (String sym : new String[]{"NIFTY", "BANKNIFTY"}) {
                List<Trade> found = tradeRepository.getClosedTradesBySymbol(sym);
                dbTrades.addAll(found);
            }
            // Sort by entry time descending
            dbTrades.sort((a, b) -> {
                if (a.getEntryTime() == null && b.getEntryTime() == null) return 0;
                if (a.getEntryTime() == null) return 1;
                if (b.getEntryTime() == null) return -1;
                return b.getEntryTime().compareTo(a.getEntryTime());
            });

            for (Trade t : dbTrades) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("signalTime", t.getEntryTime() != null ? t.getEntryTime().toString() : null);
                row.put("executionTime", t.getEntryTime() != null ? t.getEntryTime().toString() : null);
                row.put("direction", t.getDirection());
                row.put("latencySec", 0.0);
                row.put("expectedEntry", t.getEntryPrice());
                row.put("actualEntry", t.getEntryPrice());
                row.put("entrySlippage", 0.0);
                row.put("expectedExit", t.getStopLoss());
                row.put("actualExit", t.getStopLoss());
                row.put("exitSlippage", 0.0);
                row.put("tp1Hit", t.getTp1Hit() != null && t.getTp1Hit());
                row.put("runnerCaptured", t.getRunnerActive() != null && t.getRunnerActive());
                row.put("mfe", t.getMaxFavorableExcursion());
                row.put("mae", t.getMaxAdverseExcursion());
                row.put("realizedR", t.getRealizedR());
                row.put("gateDecision", "ALLOW");
                row.put("rejectReason", "");
                row.put("regime", "TREND");
                row.put("timePhase", "EARLY");
                row.put("feedStable", "true");
                row.put("exitReason", t.getExitReason());
                row.put("exitTime", t.getExitTime() != null ? t.getExitTime().toString() : null);
                row.put("pnl", t.getRealizedPnL());
                rows.add(row);
            }
        } catch (Exception e) {
            // Fall back to empty list — do not crash the health endpoint
            return Collections.emptyList();
        }

        tradeHistoryCache.set(rows);
        tradeHistoryCacheTime.set(System.currentTimeMillis());

        if (rows.size() > capped) {
            return new ArrayList<>(rows.subList(0, capped));
        }
        return rows;
    }

    private static Double toDouble(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Double.parseDouble(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
