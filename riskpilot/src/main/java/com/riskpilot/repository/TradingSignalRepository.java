package com.riskpilot.repository;

import com.riskpilot.model.TradingSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TradingSignalRepository extends JpaRepository<TradingSignal, Long> {
    
    List<TradingSignal> findBySymbolAndStatus(String symbol, String status);
    
    List<TradingSignal> findBySymbolAndSignalTimeBetween(String symbol, LocalDateTime start, LocalDateTime end);
    
    Optional<TradingSignal> findFirstBySymbolAndStatusOrderBySignalTimeDesc(String symbol, String status);
    
    @Query("SELECT s FROM TradingSignal s WHERE s.status = 'GENERATED' AND s.symbol = :symbol AND s.signalTime >= :since")
    List<TradingSignal> findExecutableSignalsSince(@Param("symbol") String symbol, @Param("since") LocalDateTime since);
    
    @Query("SELECT COUNT(s) FROM TradingSignal s WHERE s.symbol = :symbol AND DATE(s.signalTime) = CURRENT_DATE AND s.status = :status")
    Long countSignalsByStatusToday(@Param("symbol") String symbol, @Param("status") String status);
    
    @Query("SELECT s FROM TradingSignal s WHERE s.symbol = :symbol AND s.status = 'GENERATED' AND s.signalTime < :expiryTime")
    List<TradingSignal> findExpiredSignals(@Param("symbol") String symbol, @Param("expiryTime") LocalDateTime expiryTime);
    
    @Query("SELECT AVG(s.executionLatencySeconds) FROM TradingSignal s WHERE s.symbol = :symbol AND s.executionLatencySeconds IS NOT NULL AND DATE(s.signalTime) = CURRENT_DATE")
    Double getAverageExecutionLatencyToday(@Param("symbol") String symbol);
    
    @Query("SELECT s FROM TradingSignal s WHERE s.symbol = :symbol AND s.regime = :regime ORDER BY s.signalTime DESC")
    List<TradingSignal> findSignalsByRegime(@Param("symbol") String symbol, @Param("regime") String regime);
    
    @Query("SELECT COUNT(s) FROM TradingSignal s WHERE s.symbol = :symbol AND s.rejectionReason IS NOT NULL AND DATE(s.signalTime) = CURRENT_DATE")
    Long countRejectedSignalsToday(@Param("symbol") String symbol);
    
    @Query("SELECT s FROM TradingSignal s WHERE s.symbol = :symbol AND s.strategy = :strategy ORDER BY s.signalTime DESC")
    List<TradingSignal> findSignalsByStrategy(@Param("symbol") String symbol, @Param("strategy") String strategy);
}
