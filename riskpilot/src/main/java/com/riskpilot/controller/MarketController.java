package com.riskpilot.controller;

import com.riskpilot.service.MarketService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MarketController {

    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    @GetMapping("/market")
    public Map<String, Object> getMarketData() {

        double price = marketService.getPrice("NIFTY");

        Map<String, Object> response = new HashMap<>();
        response.put("symbol", "NIFTY");
        response.put("price", price);

        return response;
    }
}