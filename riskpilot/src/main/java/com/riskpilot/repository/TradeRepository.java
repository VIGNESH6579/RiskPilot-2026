package com.riskpilot.repository;

import com.riskpilot.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByEntryTimeBetween(LocalDateTime start, LocalDateTime end);

}