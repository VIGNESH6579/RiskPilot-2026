package com.riskpilot.controller;

import com.riskpilot.service.MarketService;
import com.riskpilot.service.OptionChainService;
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
    public Map<String, Object> getMarketData() {

        OptionChainService.OptionChainSnapshot chain = optionChainService.fetchNiftyChain();
        double price = chain.spot() > 0.0 ? chain.spot() : marketService.getPrice("NIFTY");

        Map<String, Object> response = new HashMap<>();
        response.put("symbol", "NIFTY");
        response.put("price", price);
        response.put("source", chain.source());
        response.put("expiry", chain.expiry());

        return response;
    }
}