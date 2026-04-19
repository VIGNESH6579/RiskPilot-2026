package com.riskpilot.repository;

import com.riskpilot.model.SignalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SignalLogRepository extends JpaRepository<SignalLog, Long> {
}
