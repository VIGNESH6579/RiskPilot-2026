package com.riskpilot.service;

import com.riskpilot.exception.TradingBlockedException;
import com.riskpilot.model.Trade;
import com.riskpilot.repository.TradeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TradeService {

    private final TradeRepository tradeRepository;
    private final RiskService riskService; // ✅ ADD THIS

    private final VixService vixService;

    public TradeService(TradeRepository tradeRepository,
                        RiskService riskService,
                        VixService vixService) {
        this.tradeRepository = tradeRepository;
        this.riskService = riskService;
        this.vixService = vixService;
    }

    // Create Trade
    public Trade createTrade(Trade trade) {

        // 🔴 Step 1: Get today's trades
        LocalDateTime start = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime end = LocalDateTime.now();

        List<Trade> todayTrades = tradeRepository.findByEntryTimeBetween(start, end);

        // 🔴 Step 2: Calculate today's loss
        double todayLoss = riskService.calculateTodayLoss(todayTrades);

        // 🔴 Step 3: Kill switch
        if (riskService.isMaxLossReached(todayLoss)) {
            throw new RuntimeException("Max daily loss reached. Trading blocked.");
        }

        // 🔴 Step 4: Quantity calculation
        double vix = vixService.getCurrentVix();

        if (riskService.isTradingBlocked(vix)) {
            throw new TradingBlockedException("High VIX. Trading blocked.");
        }

        int qty = riskService.calculateQuantity(
                trade.getEntryPrice(),
                trade.getStopLoss(),
                vix
        );

        // 🔴 Step 5: Set fields
        trade.setQuantity(qty);
        trade.setEntryTime(LocalDateTime.now());
        trade.setStatus("OPEN");

        return tradeRepository.save(trade);
    }

    // Close Trade
    public Trade closeTrade(Long id, Double exitPrice) {
        Trade trade = tradeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Trade not found with id: " + id));

        trade.setExitPrice(exitPrice);
        trade.setExitTime(LocalDateTime.now());
        trade.setStatus("CLOSED");

        double pnl;
        if (trade.getDirection().equalsIgnoreCase("BUY")) {
            pnl = (exitPrice - trade.getEntryPrice()) * trade.getQuantity();
        } else {
            pnl = (trade.getEntryPrice() - exitPrice) * trade.getQuantity();
        }

        trade.setPnl(pnl);

        return tradeRepository.save(trade);
    }

    public List<Trade> getAllTrades() {
        return tradeRepository.findAll();
    }




}