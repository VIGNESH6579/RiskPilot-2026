package com.riskpilot.controller;

import com.riskpilot.service.MarketService;
import com.riskpilot.service.OptionChainService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MarketController {

    private final MarketService marketService;
    private final OptionChainService optionChainService;

    public MarketController(MarketService marketService, OptionChainService optionChainService) {
        this.marketService = marketService;
        this.optionChainService = optionChainService;
    }

    @GetMapping("/market")
    public ResponseEntity<Map<String, Object>> getMarketData() {
        try {
            OptionChainService.OptionChainSnapshot chain = optionChainService.fetchNiftyChain();
            double price = chain != null ? chain.spot() : 0.0;
            if (chain == null || chain.spot() <= 0.0) {
                price = marketService.getPrice("NIFTY");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("symbol", "NIFTY");
            response.put("price", price);
            response.put("source", chain != null ? chain.source() : "ANGELONE_LTP_DIRECT");
            response.put("expiry", chain != null ? chain.expiry() : null);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "MARKET_DATA_UNAVAILABLE",
                "symbol", "NIFTY",
                "message", e.getMessage()
            ));
        }
    }
}
