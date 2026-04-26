package com.riskpilot.controller;

import com.riskpilot.service.OptionChainService;
import com.riskpilot.service.VixService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/data")
@CrossOrigin
@RequiredArgsConstructor
public class DataController {

    private final VixService vixService;
    private final OptionChainService optionChainService;

    @GetMapping("/vix")
    public Map<String, Object> getVix() {
        double vix = vixService.getIndiaVix();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("value", Double.isFinite(vix) && vix > 0.0 ? vix : null);
        payload.put("available", Double.isFinite(vix) && vix > 0.0);
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    @GetMapping("/option-chain")
    public OptionChainService.OptionChainSnapshot getOptionChain() {
        return optionChainService.fetchNiftyChain();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        OptionChainService.OptionChainSnapshot chain = optionChainService.fetchNiftyChain();
        double vix = vixService.getIndiaVix();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionChainSource", chain.source());
        payload.put("source", chain.source());
        payload.put("spot", chain.spot() > 0.0 ? chain.spot() : null);
        payload.put("previousClose", chain.previousClose() > 0.0 ? chain.previousClose() : null);
        payload.put("expiry", chain.expiry());
        payload.put("expiryDate", chain.expiry());
        payload.put("vix", Double.isFinite(vix) && vix > 0.0 ? vix : null);
        payload.put("healthy", chain.spot() > 0.0 && chain.expiry() != null && !chain.expiry().isBlank());
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    @GetMapping("/trade-history")
    public List<Map<String, Object>> tradeHistory(@RequestParam(defaultValue = "25") int limit) {
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
                if (parts.size() < 21) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("signalTime", parts.get(0));
                row.put("executionTime", parts.get(1));
                row.put("latencySec", toDouble(parts.get(2)));
                row.put("expectedEntry", toDouble(parts.get(3)));
                row.put("actualEntry", toDouble(parts.get(4)));
                row.put("entrySlippage", toDouble(parts.get(5)));
                row.put("expectedExit", toDouble(parts.get(6)));
                row.put("actualExit", toDouble(parts.get(7)));
                row.put("exitSlippage", toDouble(parts.get(8)));
                row.put("tp1Hit", Boolean.parseBoolean(parts.get(9)));
                row.put("runnerCaptured", Boolean.parseBoolean(parts.get(10)));
                row.put("mfe", toDouble(parts.get(11)));
                row.put("mae", toDouble(parts.get(12)));
                row.put("realizedR", toDouble(parts.get(13)));
                row.put("gateDecision", parts.get(14));
                row.put("rejectReason", parts.get(15));
                row.put("regime", parts.get(16));
                row.put("timePhase", parts.get(17));
                row.put("feedStable", parts.get(18));
                row.put("exitReason", parts.get(19));
                row.put("exitTime", parts.get(20));
                rows.add(row);
            }
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        Collections.reverse(rows);
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
