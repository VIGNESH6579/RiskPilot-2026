package com.riskpilot.repository;

import com.riskpilot.model.TradingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TradingSessionRepository extends JpaRepository<TradingSession, Long> {
    
    Optional<TradingSession> findBySymbolAndSessionDate(String symbol, LocalDate date);
    
    List<TradingSession> findBySymbolAndSessionDateBetween(String symbol, LocalDate startDate, LocalDate endDate);
    
    Optional<TradingSession> findFirstBySymbolOrderBySessionDateDesc(String symbol);
    
    @Query("SELECT s FROM TradingSession s WHERE s.symbol = :symbol AND s.sessionActive = true")
    Optional<TradingSession> findActiveSession(@Param("symbol") String symbol);
    
    @Query("SELECT COUNT(s) FROM TradingSession s WHERE s.symbol = :symbol AND s.sessionDate >= :startDate")
    Long countSessionsSince(@Param("symbol") String symbol, @Param("startDate") LocalDate startDate);
    
    @Query("SELECT SUM(s.totalPnL) FROM TradingSession s WHERE s.symbol = :symbol AND s.sessionDate >= :startDate")
    BigDecimal getTotalPnLSince(@Param("symbol") String symbol, @Param("startDate") LocalDate startDate);
    
    @Query("SELECT MAX(s.maxDrawdown) FROM TradingSession s WHERE s.symbol = :symbol AND s.sessionDate >= :startDate")
    BigDecimal getMaxDrawdownSince(@Param("symbol") String symbol, @Param("startDate") LocalDate startDate);
    
    @Query("SELECT AVG(s.tradesGenerated) FROM TradingSession s WHERE s.symbol = :symbol AND s.sessionDate >= :startDate")
    Double getAverageTradesPerSession(@Param("symbol") String symbol, @Param("startDate") LocalDate startDate);
    
    @Query("SELECT s FROM TradingSession s WHERE s.symbol = :symbol AND s.dayBlockedByFirstTradeFailure = true ORDER BY s.sessionDate DESC")
    List<TradingSession> findBlockedSessions(@Param("symbol") String symbol);
    
    @Query("SELECT COUNT(s) FROM TradingSession s WHERE s.symbol = :symbol AND s.regime = :regime AND s.sessionDate >= :startDate")
    Long countSessionsByRegime(@Param("symbol") String symbol, @Param("regime") String regime, @Param("startDate") LocalDate startDate);
}
