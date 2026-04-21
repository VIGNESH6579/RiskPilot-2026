package com.riskpilot.service;

import com.riskpilot.model.TradingSessionSnapshot;
import org.springframework.stereotype.Service;

import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SessionStateManager {

    private final AtomicReference<TradingSessionSnapshot> currentSnapshot = new AtomicReference<>(TradingSessionSnapshot.initial());

    public TradingSessionSnapshot getSnapshot() {
        return currentSnapshot.get();
    }

    public synchronized TradingSessionSnapshot update(UnaryOperator<TradingSessionSnapshot> updater) {
        TradingSessionSnapshot updated = updater.apply(currentSnapshot.get());
        currentSnapshot.set(updated);
        return updated;
    }

    public synchronized void resetDaily() {
        currentSnapshot.set(TradingSessionSnapshot.initial());
    }
}
