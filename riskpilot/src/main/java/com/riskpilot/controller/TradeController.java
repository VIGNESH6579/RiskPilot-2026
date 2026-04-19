package com.riskpilot.controller;

import com.riskpilot.model.Trade;
import com.riskpilot.service.TradeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/trades")
public class TradeController {

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    // Create trade
    @PostMapping
    public Trade createTrade(@RequestBody Trade trade) {
        return tradeService.createTrade(trade);
    }

    // Close trade
    @PutMapping("/{id}/close")
    public Trade closeTrade(@PathVariable Long id,
                            @RequestParam Double exitPrice) {
        return tradeService.closeTrade(id, exitPrice);
    }

    // Get all trades
    @GetMapping
    public List<Trade> getAllTrades() {
        return tradeService.getAllTrades();
    }
}