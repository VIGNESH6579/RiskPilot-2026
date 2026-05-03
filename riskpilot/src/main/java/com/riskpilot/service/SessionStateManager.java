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

    public TradingSessionSnapshot update(UnaryOperator<TradingSessionSnapshot> updater) {
        // Snapshots are immutable records; AtomicReference provides visibility and atomic swaps.
        return currentSnapshot.updateAndGet(updater);
    }

    public void resetDaily() {
        currentSnapshot.set(TradingSessionSnapshot.initial());
    }
}
