package com.riskpilot.service;

import com.riskpilot.model.Signal;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    public void send(Signal signal) {

        String message = "\n📊 SIGNAL\n" +
                "Symbol: " + signal.getSymbol() + "\n" +
                "Direction: " + signal.getDirection() + "\n" +
                "Entry: " + signal.getEntry() + "\n" +
                "Target: " + signal.getTarget() + "\n" +
                "SL: " + signal.getStopLoss() + "\n" +
                "Confidence: " + signal.getConfidence();

        // 🔴 for now just print
        System.out.println(message);

        // 👉 Later: integrate ntfy / telegram
    }
}