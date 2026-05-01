package com.riskpilot.service;

import com.riskpilot.model.PnlDTO;
import com.riskpilot.model.Trade;
import com.riskpilot.model.TradeLog;
import com.riskpilot.repository.TradeLogRepository;
import com.riskpilot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PnlService {

    private final TradeRepository tradeRepository;
    private final TradeLogRepository tradeLogRepository;

    public PnlDTO calculateDayPnl(String symbol, LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<Trade> trades = tradeRepository.findBySymbolAndEntryTimeBetween(symbol, start, end);
        List<TradeLog> tradeLogs = tradeLogRepository.findByTradingDay(date);

        double realizedPnl = trades.stream()
            .filter(trade -> "CLOSED".equalsIgnoreCase(trade.getStatus()))
            .map(Trade::getRealizedPnL)
            .mapToDouble(PnlService::toDouble)
            .sum();

        double openPnl = trades.stream()
            .filter(trade -> "ACTIVE".equalsIgnoreCase(trade.getStatus()))
            .map(Trade::getUnrealizedPnL)
            .mapToDouble(PnlService::toDouble)
            .sum();

        long rejectedCount = tradeLogs.stream()
            .filter(log -> "REJECT".equalsIgnoreCase(log.getGateDecision()))
            .count();

        int totalAttempts = Math.toIntExact(Math.min(Integer.MAX_VALUE, trades.size() + rejectedCount));
        return new PnlDTO(
            realizedPnl,
            openPnl,
            realizedPnl + openPnl,
            totalAttempts,
            rejectedCount,
            getPnlNote(totalAttempts, rejectedCount)
        );
    }

    private String getPnlNote(int totalTrades, long rejectedCount) {
        if (totalTrades == 0) {
            return "No trades attempted today";
        }
        if (rejectedCount > 0 && totalTrades == rejectedCount) {
            return "All " + rejectedCount + " trade attempts were rejected";
        }
        return "";
    }

    private static double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }
}
