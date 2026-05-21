package com.riskpilot.controller;

import com.riskpilot.service.OptionChainService;
import com.riskpilot.service.VixService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path csvPath = Path.of(System.getenv().getOrDefault("RISKPILOT_CSV_PATH", "shadow_live_forward_logs.csv"));
        if (!Files.exists(csvPath)) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            boolean isHeader = true;
            while ((line = br.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                if (line.isBlank()) continue;
                List<String> parts = parseCsvLine(line);
                if (parts.size() < 22) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("signalTime", parts.get(0));
                row.put("executionTime", parts.get(1));
                row.put("direction", parts.get(2));
                row.put("latencySec", toDouble(parts.get(3)));
                row.put("expectedEntry", toDouble(parts.get(4)));
                row.put("actualEntry", toDouble(parts.get(5)));
                row.put("entrySlippage", toDouble(parts.get(6)));
                row.put("expectedExit", toDouble(parts.get(7)));
                row.put("actualExit", toDouble(parts.get(8)));
                row.put("exitSlippage", toDouble(parts.get(9)));
                row.put("tp1Hit", Boolean.parseBoolean(parts.get(10)));
                row.put("runnerCaptured", Boolean.parseBoolean(parts.get(11)));
                row.put("mfe", toDouble(parts.get(12)));
                row.put("mae", toDouble(parts.get(13)));
                row.put("realizedR", toDouble(parts.get(14)));
                row.put("gateDecision", parts.get(15));
                row.put("rejectReason", parts.get(16));
                row.put("regime", parts.get(17));
                row.put("timePhase", parts.get(18));
                row.put("feedStable", parts.get(19));
                row.put("exitReason", parts.get(20));
                row.put("exitTime", parts.get(21));
                rows.add(row);
            }
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        Collections.reverse(rows);

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
