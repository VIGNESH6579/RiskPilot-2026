package com.riskpilot.repository;

import com.riskpilot.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    
    @Query("""
        SELECT COALESCE(SUM(t.realizedPnL + t.unrealizedPnL), 0)
        FROM Trade t
        WHERE t.symbol = :symbol
          AND t.entryTime >= :startDate
          AND t.entryTime < :endDate
        """)
    BigDecimal getPnLBetween(
        @Param("symbol") String symbol,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    @Query("SELECT MAX(t.maxAdverseExcursion) FROM Trade t WHERE t.symbol = :symbol AND t.entryTime >= :startDate")
    BigDecimal getMaxAdverseExcursionSince(@Param("symbol") String symbol, @Param("startDate") LocalDateTime startDate);
    
    @Query("SELECT t FROM Trade t WHERE t.exitReason = :reason AND t.symbol = :symbol ORDER BY t.entryTime DESC")
    List<Trade> findTradesByExitReason(@Param("reason") String reason, @Param("symbol") String symbol);
    
    @Query("""
        SELECT COUNT(t)
        FROM Trade t
        WHERE t.symbol = :symbol
          AND t.tp1Hit = true
          AND t.entryTime >= :startDate
          AND t.entryTime < :endDate
        """)
    Long countTp1HitsBetween(
        @Param("symbol") String symbol,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    @Query("""
        SELECT COUNT(t)
        FROM Trade t
        WHERE t.symbol = :symbol
          AND t.runnerActive = true
          AND t.entryTime >= :startDate
          AND t.entryTime < :endDate
        """)
    Long countRunnersBetween(
        @Param("symbol") String symbol,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    @Query("SELECT t FROM Trade t WHERE t.status = 'CLOSED' AND t.symbol = :symbol ORDER BY t.entryTime DESC")
    List<Trade> getClosedTradesBySymbol(@Param("symbol") String symbol);

    default BigDecimal getTodayPnL(String symbol) {
        LocalDate today = LocalDate.now();
        return getPnLBetween(symbol, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    default Long countTp1HitsToday(String symbol) {
        LocalDate today = LocalDate.now();
        return countTp1HitsBetween(symbol, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    default Long countRunnersToday(String symbol) {
        LocalDate today = LocalDate.now();
        return countRunnersBetween(symbol, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }
}
