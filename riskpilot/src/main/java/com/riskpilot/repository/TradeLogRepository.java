package com.riskpilot.repository;

import com.riskpilot.model.TradeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeLogRepository extends JpaRepository<TradeLog, Long> {
    List<TradeLog> findTop200ByOrderBySignalTimeDesc();

    List<TradeLog> findTop200ByGateDecisionOrderBySignalTimeDesc(String gateDecision);
}
