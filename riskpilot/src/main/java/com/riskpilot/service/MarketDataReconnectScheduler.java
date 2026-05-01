package com.riskpilot.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataReconnectScheduler {

    private final ObjectProvider<AngelTickStreamClient> tickStreamClientProvider;
    private final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "market-data-reconnect");
            thread.setDaemon(true);
            return thread;
        });
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    public void scheduleReconnect(Duration delay) {
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }

        long delayMs = Math.max(0L, delay.toMillis());
        executor.schedule(() -> {
            reconnectScheduled.set(false);
            try {
                tickStreamClientProvider.getObject().reconnectAfterAuthentication();
            } catch (Exception e) {
                log.warn("Unable to schedule market data reconnect: {}", e.getMessage());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
