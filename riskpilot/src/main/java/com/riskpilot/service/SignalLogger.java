package com.riskpilot.service;

import com.riskpilot.model.SignalLog;
import com.riskpilot.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SignalLogger {
    private static final Logger log = LoggerFactory.getLogger(SignalLogger.class);

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

        repo.save(logMetric);
        log.info("Signal frame persisted: spot={} vix={} support={} resistance={} decision={}",
            spot, vix, support, resistance, decision);
    }
}
