package com.riskpilot.service;

import com.riskpilot.model.Signal;
import com.riskpilot.model.SystemState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;

@Service
public class SignalScheduler {

    private final SignalService signalService;
    private final NotificationService notificationService;
    private final WebSocketService webSocketService;
    private final SystemStateService systemStateService;

    @Value("${app.test-mode:false}")
    private boolean testMode;

    public SignalScheduler(SignalService signalService,
                           NotificationService notificationService,
                           WebSocketService webSocketService,
                           SystemStateService systemStateService) {
        this.signalService = signalService;
        this.notificationService = notificationService;
        this.webSocketService = webSocketService;
        this.systemStateService = systemStateService;
    }

    // ⏱ runs every 30 seconds
    @Scheduled(fixedRate = 30000)
    public void runSignalEngine() {

        try {
            // ✅ MARKET CHECK (bypass in test mode)
            if (!isMarketOpen()) {
                System.out.println("Market closed");
                return;
            }

            SystemState state = systemStateService.getState();

            // ❌ system OFF
            if (!state.isTradingEnabled()) {
                System.out.println("Trading disabled");
                return;
            }

            // ❌ cooldown (2 minutes)
            long now = System.currentTimeMillis();
            if (now - state.getLastTradeTime() < 120000) {
                System.out.println("Cooldown active");
                return;
            }

            Signal signal = signalService.generateSignal();

            // ❌ ignore no trade
            if ("NO TRADE".equals(signal.getDirection())) {
                System.out.println("No trade condition");
                return;
            }

            String currentSignal = signal.getSymbol() + "_" + signal.getDirection();

            // ❌ avoid duplicate signals
            if (currentSignal.equals(state.getLastSignal())) {
                System.out.println("Duplicate signal ignored");
                return;
            }

            // ✅ log
            System.out.println("NEW SIGNAL: " + signal);

            // 🔔 notification
            notificationService.send(signal);

            // 📡 websocket push
            webSocketService.sendSignal(signal);

            // 💾 update system state
            state.setLastSignal(currentSignal);
            state.setLastTradeTime(now);
            systemStateService.update(state);

        } catch (Exception e) {
            System.out.println("Signal error: " + e.getMessage());
        }
    }

    // 🧠 MARKET HOURS LOGIC
    private boolean isMarketOpen() {

        if (testMode) {
            return true; // 🚀 FORCE RUN IN TEST MODE
        }

        LocalTime now = LocalTime.now();
        return now.isAfter(LocalTime.of(9, 15)) &&
                now.isBefore(LocalTime.of(15, 30));
    }
}