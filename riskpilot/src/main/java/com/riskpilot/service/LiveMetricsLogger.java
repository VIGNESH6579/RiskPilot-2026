package com.riskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;

@Service
public class LiveMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger(LiveMetricsLogger.class);
    private static final String CSV_PATH = "shadow_live_forward_logs.csv";

    public void logShadowExecution(
            LocalDateTime signalTime,
            LocalDateTime executionTime,
            double expectedEntryPrice,
            double actualEntryPrice,
            double mfe,
            double mae,
            boolean isRunner,
            String exitReason,
            LocalDateTime exitTime
    ) {
        log.info(">>> SHADOW EXECUTION LOG <<<");
        log.info("  Signal Time       : {}", signalTime);
        log.info("  Execution Time    : {}", executionTime);
        log.info("  Latency Gap (s)   : {} s", java.time.Duration.between(signalTime, executionTime).getSeconds());
        log.info("  Expected Entry    : {}", expectedEntryPrice);
        log.info("  Actual Entry      : {}", actualEntryPrice);
        log.info("  Slippage          : {} pts", actualEntryPrice - expectedEntryPrice);
        log.info("  Max Favorable Ex  : {} pts", mfe);
        log.info("  Max Adverse Ex    : {} pts", mae);
        log.info("  Exit Reason       : {}", exitReason);
        log.info("  Exit Time         : {}", exitTime);
        log.info("=====================================");
        
        writeToPersistentCsv(signalTime, executionTime, expectedEntryPrice, actualEntryPrice, mfe, mae, isRunner, exitReason, exitTime);
    }
    
    private synchronized void writeToPersistentCsv(LocalDateTime signalTime, LocalDateTime executionTime, double expectedEntryPrice, double actualEntryPrice, double mfe, double mae, boolean isRunner, String exitReason, LocalDateTime exitTime) {
        try (FileWriter fw = new FileWriter(CSV_PATH, true);
             PrintWriter pw = new PrintWriter(fw)) {
            
            String logRow = String.format("%s,%s,%d,%f,%f,%f,%f,%f,%b,%s,%s",
                    signalTime, executionTime,
                    java.time.Duration.between(signalTime, executionTime).getSeconds(),
                    expectedEntryPrice, actualEntryPrice,
                    (actualEntryPrice - expectedEntryPrice), 
                    mfe, mae, isRunner, exitReason, exitTime
            );
            pw.print(logRow + "\n");
            pw.flush();
            fw.flush();
            
        } catch (IOException e) {
            log.error("CRITICAL: FAILED TO WRITE TO PERSISTENT CSV", e);
        }
    }
}
