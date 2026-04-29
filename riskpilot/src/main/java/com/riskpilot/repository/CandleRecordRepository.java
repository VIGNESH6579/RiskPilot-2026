package com.riskpilot.repository;

import com.riskpilot.model.CandleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CandleRecordRepository extends JpaRepository<CandleRecord, Long> {

    List<CandleRecord> findTop50BySymbolAndTradeDateOrderByCandleTimeAsc(String symbol, LocalDate tradeDate);
}
