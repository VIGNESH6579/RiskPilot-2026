package com.riskpilot.repository;

import com.riskpilot.model.TradingSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
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
    
    @Query("""
        SELECT COUNT(s)
        FROM TradingSignal s
        WHERE s.symbol = :symbol
          AND s.status = :status
          AND s.signalTime >= :startDate
          AND s.signalTime < :endDate
        """)
    Long countSignalsByStatusBetween(
        @Param("symbol") String symbol,
        @Param("status") String status,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    @Query("SELECT s FROM TradingSignal s WHERE s.symbol = :symbol AND s.status = 'GENERATED' AND s.signalTime < :expiryTime")
    List<TradingSignal> findExpiredSignals(@Param("symbol") String symbol, @Param("expiryTime") LocalDateTime expiryTime);
    
    @Query("""
        SELECT AVG(s.executionLatencySeconds)
        FROM TradingSignal s
        WHERE s.symbol = :symbol
          AND s.executionLatencySeconds IS NOT NULL
          AND s.signalTime >= :startDate
          AND s.signalTime < :endDate
        """)
    Double getAverageExecutionLatencyBetween(
        @Param("symbol") String symbol,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    @Query("SELECT s FROM TradingSignal s WHERE s.symbol = :symbol AND s.regime = :regime ORDER BY s.signalTime DESC")
    List<TradingSignal> findSignalsByRegime(@Param("symbol") String symbol, @Param("regime") String regime);
    
    @Query("""
        SELECT COUNT(s)
        FROM TradingSignal s
        WHERE s.symbol = :symbol
          AND s.rejectionReason IS NOT NULL
          AND s.signalTime >= :startDate
          AND s.signalTime < :endDate
        """)
    Long countRejectedSignalsBetween(
        @Param("symbol") String symbol,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    @Query("SELECT s FROM TradingSignal s WHERE s.symbol = :symbol AND s.strategy = :strategy ORDER BY s.signalTime DESC")
    List<TradingSignal> findSignalsByStrategy(@Param("symbol") String symbol, @Param("strategy") String strategy);

    default Long countSignalsByStatusToday(String symbol, String status) {
        LocalDate today = LocalDate.now();
        return countSignalsByStatusBetween(symbol, status, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    default Double getAverageExecutionLatencyToday(String symbol) {
        LocalDate today = LocalDate.now();
        return getAverageExecutionLatencyBetween(symbol, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    default Long countRejectedSignalsToday(String symbol) {
        LocalDate today = LocalDate.now();
        return countRejectedSignalsBetween(symbol, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }
}
