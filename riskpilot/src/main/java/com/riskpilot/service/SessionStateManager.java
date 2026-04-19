package com.riskpilot.service;

import com.riskpilot.model.TradingSessionSnapshot;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class SessionStateManager {

    private final AtomicReference<TradingSessionSnapshot> currentSnapshot = new AtomicReference<>(TradingSessionSnapshot.initial());

    public TradingSessionSnapshot getSnapshot() {
        return currentSnapshot.get();
    }

    public synchronized void updateSnapshot(TradingSessionSnapshot newSnapshot) {
        currentSnapshot.set(newSnapshot);
    }
    
    public synchronized void resetDaily() {
        currentSnapshot.set(TradingSessionSnapshot.initial());
    }
}
