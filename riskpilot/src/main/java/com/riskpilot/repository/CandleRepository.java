package com.riskpilot.repository;

import com.riskpilot.model.CandleEntity;
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
public interface CandleRepository extends JpaRepository<CandleEntity, Long> {
    
    List<CandleEntity> findBySymbolAndDateOrderByTimestampAsc(String symbol, LocalDate date);
    
    List<CandleEntity> findBySymbolAndTimeframeAndDateOrderByTimestampAsc(String symbol, Integer timeframe, LocalDate date);
    
    Optional<CandleEntity> findFirstBySymbolAndDateOrderByTimestampDesc(String symbol, LocalDate date);
    
    @Query("SELECT c FROM CandleEntity c WHERE c.symbol = :symbol AND c.timestamp >= :startTime AND c.timestamp <= :endTime ORDER BY c.timestamp ASC")
    List<CandleEntity> findCandlesInTimeRange(@Param("symbol") String symbol, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
    
    @Query("SELECT c FROM CandleEntity c WHERE c.symbol = :symbol AND c.date >= :startDate ORDER BY c.timestamp ASC")
    List<CandleEntity> findCandlesSince(@Param("symbol") String symbol, @Param("startDate") LocalDate startDate);
    
    @Query("SELECT c FROM CandleEntity c WHERE c.symbol = :symbol AND c.date = :date AND c.timeframe = :timeframe ORDER BY c.timestamp ASC")
    List<CandleEntity> findCandlesByDateAndTimeframe(@Param("symbol") String symbol, @Param("date") LocalDate date, @Param("timeframe") Integer timeframe);
    
    @Query("SELECT COUNT(c) FROM CandleEntity c WHERE c.symbol = :symbol AND c.date = :date")
    Long countCandlesByDate(@Param("symbol") String symbol, @Param("date") LocalDate date);
    
    @Query("SELECT MAX(c.high) FROM CandleEntity c WHERE c.symbol = :symbol AND c.timestamp >= :startTime AND c.timestamp <= :endTime")
    BigDecimal getHighInTimeRange(@Param("symbol") String symbol, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
    
    @Query("SELECT MIN(c.low) FROM CandleEntity c WHERE c.symbol = :symbol AND c.timestamp >= :startTime AND c.timestamp <= :endTime")
    BigDecimal getLowInTimeRange(@Param("symbol") String symbol, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
    
    @Query("SELECT AVG(c.range) FROM CandleEntity c WHERE c.symbol = :symbol AND c.date = :date")
    Double getAverageRangeForDate(@Param("symbol") String symbol, @Param("date") LocalDate date);
    
    @Query("SELECT c FROM CandleEntity c WHERE c.symbol = :symbol AND c.date = :date AND c.isBullish = true ORDER BY c.timestamp ASC")
    List<CandleEntity> findBullishCandles(@Param("symbol") String symbol, @Param("date") LocalDate date);
    
    @Query("SELECT c FROM CandleEntity c WHERE c.symbol = :symbol AND c.date = :date AND c.range >= :minRange ORDER BY c.timestamp ASC")
    List<CandleEntity> findCandlesWithMinRange(@Param("symbol") String symbol, @Param("date") LocalDate date, @Param("minRange") BigDecimal minRange);
}
