package com.riskpilot.service;

import com.riskpilot.model.Signal;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void sendSignal(Signal signal) {
        messagingTemplate.convertAndSend("/topic/signal", signal);
    }

    public void sendSessionState(Map<String, Object> payload) {
        messagingTemplate.convertAndSend("/topic/session", (Object) payload);
    }

    public void sendTradeExecution(Map<String, Object> tradeData) {
        messagingTemplate.convertAndSend("/topic/trade", tradeData);
    }
}