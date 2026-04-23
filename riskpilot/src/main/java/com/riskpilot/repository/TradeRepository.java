package com.riskpilot.repository;

import com.riskpilot.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
    
    List<Trade> findBySymbolAndStatus(String symbol, String status);
    
    List<Trade> findBySymbolAndEntryTimeBetween(String symbol, LocalDateTime start, LocalDateTime end);
    
    Optional<Trade> findFirstBySymbolAndStatusOrderByEntryTimeDesc(String symbol, String status);
    
    @Query("SELECT t FROM Trade t WHERE t.status = 'ACTIVE' AND t.symbol = :symbol")
    List<Trade> findActiveTradesBySymbol(@Param("symbol") String symbol);
    
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.symbol = :symbol AND t.entryTime >= :startDate")
    Long countTradesSince(@Param("symbol") String symbol, @Param("startDate") LocalDateTime startDate);
    
    @Query("SELECT SUM(t.realizedPnL + t.unrealizedPnL) FROM Trade t WHERE t.symbol = :symbol AND DATE(t.entryTime) = CURRENT_DATE")
    BigDecimal getTodayPnL(@Param("symbol") String symbol);
    
    @Query("SELECT MAX(t.maxAdverseExcursion) FROM Trade t WHERE t.symbol = :symbol AND t.entryTime >= :startDate")
    BigDecimal getMaxAdverseExcursionSince(@Param("symbol") String symbol, @Param("startDate") LocalDateTime startDate);
    
    @Query("SELECT t FROM Trade t WHERE t.exitReason = :reason AND t.symbol = :symbol ORDER BY t.entryTime DESC")
    List<Trade> findTradesByExitReason(@Param("reason") String reason, @Param("symbol") String symbol);
    
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.symbol = :symbol AND t.tp1Hit = true AND DATE(t.entryTime) = CURRENT_DATE")
    Long countTp1HitsToday(@Param("symbol") String symbol);
    
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.symbol = :symbol AND t.runnerActive = true AND DATE(t.entryTime) = CURRENT_DATE")
    Long countRunnersToday(@Param("symbol") String symbol);
    
    @Query("SELECT t FROM Trade t WHERE t.status = 'CLOSED' AND t.symbol = :symbol ORDER BY t.entryTime DESC")
    List<Trade> getClosedTradesBySymbol(@Param("symbol") String symbol);
}
