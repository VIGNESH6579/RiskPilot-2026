package com.riskpilot.service;

import com.riskpilot.model.SignalLog;
import com.riskpilot.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SignalLogger {

    private final SignalLogRepository repo;

    public void log(double spot, double vix, int support, int resistance, int confidence, String decision, String reason) {
        
        SignalLog logMetric = new SignalLog();
        logMetric.setTimestamp(LocalDateTime.now());
        logMetric.setSpot(spot);
        logMetric.setVix(vix);
        logMetric.setSupport(support);
        logMetric.setResistance(resistance);
        logMetric.setConfidence(confidence);
        logMetric.setDecision(decision);
        logMetric.setReason(reason);

        // repo.save(logMetric); System-level execution bypass
        System.out.printf("📊 Mapped Live Frame: SPOT=%f | VIX=%f | S=%d | R=%d | DS=%s\n", spot, vix, support, resistance, decision);
    }
}
