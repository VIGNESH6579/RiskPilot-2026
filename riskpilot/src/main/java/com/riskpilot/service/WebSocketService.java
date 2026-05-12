package com.riskpilot.service;

import com.riskpilot.config.PlainWebSocketConfig;
import com.riskpilot.model.Signal;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class WebSocketService {

    public void sendSignal(Signal signal) {
        PlainWebSocketConfig.TradeDataWebSocketHandler.broadcastEvent("signal", signal);
    }

    public void sendSessionState(Map<String, Object> payload) {
        PlainWebSocketConfig.TradeDataWebSocketHandler.broadcastSessionState(payload);
    }

    public void sendTradeExecution(Map<String, Object> tradeData) {
        PlainWebSocketConfig.TradeDataWebSocketHandler.broadcastTradeData(tradeData);
    }

    public void broadcastKillSwitchExit(String reason, int countdownSeconds) {
        Map<String, Object> payload = Map.of(
            "type", "KILL_SWITCH_EXIT",
            "reason", reason,
            "countdown", countdownSeconds,
            "timestamp", java.time.Instant.now().toString()
        );
        PlainWebSocketConfig.TradeDataWebSocketHandler.broadcastEvent("system_exit", payload);
    }
}
