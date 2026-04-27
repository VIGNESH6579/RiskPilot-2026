package com.riskpilot.service;

import com.riskpilot.model.Trade;
import com.riskpilot.model.TradingSignal;
import com.riskpilot.model.TradingSession;
import com.riskpilot.repository.TradeRepository;
import com.riskpilot.repository.TradingSignalRepository;
import com.riskpilot.repository.TradingSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradingSessionService {

    private final TradingSessionRepository sessionRepository;
    private final TradeRepository tradeRepository;
    private final TradingSignalRepository signalRepository;

    public TradingSession getCurrentSession(String symbol) {
        Optional<TradingSession> session = sessionRepository.findActiveSession(symbol);
        if (session.isEmpty()) {
            return createNewSession(symbol);
        }
        return session.get();
    }

    @Transactional
    public TradingSession createNewSession(String symbol) {
        LocalDate today = LocalDate.now();
        TradingSession session = sessionRepository
            .findBySymbolAndSessionDate(symbol, today)
            .orElseGet(() -> sessionRepository.save(TradingSession.builder()
                .sessionDate(today)
                .symbol(symbol)
                .sessionStart(LocalDateTime.now())
                .dailyOpen(BigDecimal.ZERO)
                .orHigh(BigDecimal.ZERO)
                .orLow(BigDecimal.ZERO)
                .orExpansion(BigDecimal.ZERO)
                .fastCandleExists(false)
                .regime("UNKNOWN")
                .regimeLocked(false)
                .tradesGenerated(0)
                .tradesExecuted(0)
                .tradesRejected(0)
                .totalPnL(BigDecimal.ZERO)
                .maxDrawdown(BigDecimal.ZERO)
                .maxProfit(BigDecimal.ZERO)
                .sessionActive(true)
                .dayBlockedByFirstTradeFailure(false)
                .status("ACTIVE")
                .build()));

        log.info("Resolved trading session for symbol: {} on date: {}", symbol, today);
        return session;
    }

    public List<Trade> getActiveTrades(String symbol) {
        return tradeRepository.findActiveTradesBySymbol(symbol);
    }

    public List<TradingSignal> getRecentSignals(String symbol, int limit) {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<TradingSignal> signals = signalRepository.findExecutableSignalsSince(symbol, since);
        return signals.stream().limit(limit).toList();
    }

    public List<Trade> getTradeHistory(String symbol, LocalDateTime startDate, LocalDateTime endDate) {
        return tradeRepository.findBySymbolAndEntryTimeBetween(symbol, startDate, endDate);
    }

    @Transactional
    public void processManualSignal(TradingSignal signal) {
        LocalDateTime now = LocalDateTime.now();
        signal.setStatus("MANUAL");
        signal.setExecutionTime(now);
        if (signal.getSignalTime() == null) {
            signal.setSignalTime(now);
        }
        if (signal.getRegime() == null || signal.getRegime().isBlank()) {
            signal.setRegime("TREND");
        }
        if (signal.getTimePhase() == null || signal.getTimePhase().isBlank()) {
            signal.setTimePhase(resolveCurrentTimePhase());
        }
        if (signal.getRiskAmount() == null && signal.getExpectedEntry() != null && signal.getStopLoss() != null) {
            signal.setRiskAmount(signal.getExpectedEntry().subtract(signal.getStopLoss()).abs().setScale(2, RoundingMode.HALF_UP));
        }
        if (signal.getRewardAmount() == null && signal.getExpectedEntry() != null && signal.getTargetPrice() != null) {
            signal.setRewardAmount(signal.getTargetPrice().subtract(signal.getExpectedEntry()).abs().setScale(2, RoundingMode.HALF_UP));
        }
        if (signal.getRiskRewardRatio() == null) {
            BigDecimal riskAmount = signal.getRiskAmount();
            BigDecimal rewardAmount = signal.getRewardAmount();
            if (riskAmount != null && rewardAmount != null && riskAmount.compareTo(BigDecimal.ZERO) > 0) {
                signal.setRiskRewardRatio(rewardAmount.divide(riskAmount, 2, RoundingMode.HALF_UP));
            } else {
                signal.setRiskRewardRatio(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            }
        }
        signalRepository.save(signal);
        
        log.info("Processed manual signal: {} for symbol: {}", signal.getId(), signal.getSymbol());
    }

    @Transactional
    public void closeTrade(Long tradeId, String reason) {
        Optional<Trade> tradeOpt = tradeRepository.findById(tradeId);
        if (tradeOpt.isEmpty()) {
            throw new RuntimeException("Trade not found with ID: " + tradeId);
        }
        
        Trade trade = tradeOpt.get();
        trade.setStatus("CLOSED");
        trade.setExitTime(LocalDateTime.now());
        trade.setExitReason(reason != null ? reason : "MANUAL_CLOSE");
        
        tradeRepository.save(trade);
        
        // Update session metrics
        updateSessionMetrics(trade.getSymbol());
        
        log.info("Closed trade {} with reason: {}", tradeId, reason);
    }

    public Map<String, Object> getPerformanceMetrics(String symbol, int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        
        List<Trade> trades = tradeRepository.findBySymbolAndEntryTimeBetween(symbol, startDate, LocalDateTime.now());
        
        if (trades.isEmpty()) {
            return Map.of(
                "totalTrades", 0,
                "totalPnL", BigDecimal.ZERO,
                "winRate", 0.0,
                "avgTrade", BigDecimal.ZERO
            );
        }
        
        int totalTrades = trades.size();
        BigDecimal totalPnL = trades.stream()
                .map(Trade::getTotalPnL)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        long winningTrades = trades.stream()
                .mapToLong(t -> t.getTotalPnL().compareTo(BigDecimal.ZERO) > 0 ? 1 : 0)
                .sum();
        
        double winRate = (double) winningTrades / totalTrades * 100;
        BigDecimal avgTrade = totalPnL.divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP);
        
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalTrades", totalTrades);
        metrics.put("totalPnL", totalPnL);
        metrics.put("winRate", winRate);
        metrics.put("avgTrade", avgTrade);
        metrics.put("winningTrades", winningTrades);
        metrics.put("losingTrades", totalTrades - winningTrades);
        metrics.put("period", days + " days");
        metrics.put("symbol", symbol);
        
        return metrics;
    }

    @Transactional
    private void updateSessionMetrics(String symbol) {
        Optional<TradingSession> sessionOpt = sessionRepository.findActiveSession(symbol);
        if (sessionOpt.isEmpty()) {
            return;
        }
        
        TradingSession session = sessionOpt.get();
        BigDecimal todayPnL = tradeRepository.getTodayPnL(symbol);
        
        if (todayPnL != null) {
            session.setTotalPnL(todayPnL);
            
            // Update max profit/drawdown
            if (todayPnL.compareTo(session.getMaxProfit()) > 0) {
                session.setMaxProfit(todayPnL);
            }
            
            BigDecimal drawdown = BigDecimal.ZERO.subtract(todayPnL);
            if (drawdown.compareTo(session.getMaxDrawdown()) > 0) {
                session.setMaxDrawdown(drawdown);
            }
        }
        
        sessionRepository.save(session);
    }

    @Transactional
    public void endSession(String symbol) {
        Optional<TradingSession> sessionOpt = sessionRepository.findActiveSession(symbol);
        if (sessionOpt.isEmpty()) {
            return;
        }
        
        TradingSession session = sessionOpt.get();
        session.setSessionActive(false);
        session.setSessionEnd(LocalDateTime.now());
        session.setStatus("CLOSED");
        
        sessionRepository.save(session);
        log.info("Ended trading session for symbol: {}", symbol);
    }

    private String resolveCurrentTimePhase() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(12, 0))) {
            return "EARLY";
        }
        if (now.isBefore(LocalTime.of(13, 30))) {
            return "MID";
        }
        return "LATE";
    }
}
