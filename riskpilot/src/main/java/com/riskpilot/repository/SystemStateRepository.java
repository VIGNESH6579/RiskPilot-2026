package com.riskpilot.repository;

import com.riskpilot.model.SystemState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemStateRepository extends JpaRepository<SystemState, Long> {
}