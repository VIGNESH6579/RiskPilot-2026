package com.riskpilot.service;

import com.riskpilot.model.Signal;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void sendSignal(Signal signal) {
        messagingTemplate.convertAndSend("/topic/signal", signal);
    }
}