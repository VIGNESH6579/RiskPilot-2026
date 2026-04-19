package com.riskpilot.service;

import com.riskpilot.model.SystemState;
import com.riskpilot.repository.SystemStateRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SystemStateService {

    private final SystemStateRepository repository;

    public SystemStateService(SystemStateRepository repository) {
        this.repository = repository;
    }

    public SystemState getState() {
        return repository.findById(1L).orElseGet(() -> {
            SystemState s = new SystemState();
            s.setTradingEnabled(true);
            s.setLastSignal("");
            s.setLastTradeTime(0);
            return repository.save(s);
        });
    }

    public void update(SystemState state) {
        repository.save(state);
    }
}