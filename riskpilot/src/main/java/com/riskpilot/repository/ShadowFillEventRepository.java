package com.riskpilot.repository;

import com.riskpilot.model.ShadowFillEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShadowFillEventRepository extends JpaRepository<ShadowFillEvent, Long> {

    List<ShadowFillEvent> findByTradeIdOrderByLegNumberAsc(Long tradeId);

    long countByTradeId(Long tradeId);
}
