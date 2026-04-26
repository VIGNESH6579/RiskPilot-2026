package com.riskpilot.service;

import com.riskpilot.model.Signal;
import org.springframework.messaging.support.MessageBuilder;
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
        messagingTemplate.send("/topic/signal", MessageBuilder.withPayload(signal).build());
    }

    public void sendSessionState(Map<String, Object> payload) {
        messagingTemplate.send("/topic/session", MessageBuilder.withPayload(payload).build());
    }

    public void sendTradeExecution(Map<String, Object> tradeData) {
        messagingTemplate.send("/topic/trade", MessageBuilder.withPayload(tradeData).build());
    }
}
