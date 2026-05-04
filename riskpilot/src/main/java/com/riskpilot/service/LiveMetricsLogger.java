package com.riskpilot.service;

import com.riskpilot.model.Regime;
import com.riskpilot.model.TimePhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * LiveMetricsLogger logs shadow trading metrics to CSV for analysis.
 * 
 * BUG-043: Uses persistent FileWriter to avoid repeated file open/close cycles.
 */
@Service
public class LiveMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger(LiveMetricsLogger.class);
    private static final String CSV_PATH = "shadow_live_forward_logs.csv";
    private static final String CSV_HEADER =
        "signalTime,executionTime,direction,latencySec,expectedEntry,actualEntry,entrySlippage," +
        "expectedExit,actualExit,exitSlippage,tp1Hit,runnerCaptured,mfe,mae,realizedR," +
        "gateDecision,rejectReason,regime,timePhase,feedStable,exitReason,exitTime";
    
    // BUG-043: Persistent FileWriter for performance
    private PrintWriter csvWriter;
    private BufferedWriter bufferedWriter;
    private final Object writerLock = new Object();
    
    @PostConstruct
    public void init() {
        try {
            File csv = new File(CSV_PATH);
            boolean exists = csv.exists() && csv.length() > 0;
            bufferedWriter = Files.newBufferedWriter(
                Path.of(CSV_PATH),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.SYNC
            );
            csvWriter = new PrintWriter(bufferedWriter);
            if (!exists) {
                csvWriter.println(CSV_HEADER);
                csvWriter.flush();
            }
            log.info("LiveMetricsLogger initialized with persistent CSV writer");
        } catch (IOException e) {
            log.error("Failed to initialize LiveMetricsLogger: {}", e.getMessage());
        }
    }
    
    @PreDestroy
    public void cleanup() {
        synchronized (writerLock) {
            if (csvWriter != null) {
                csvWriter.flush();
                csvWriter.close();
                log.info("LiveMetricsLogger CSV writer closed");
            }
        }
    }

    public synchronized void logReject(
        LocalDateTime signalTime,
        String rejectReason,
        Regime regime,
        TimePhase timePhase,
        boolean feedStable
    ) {
        ensureHeader();
        appendRow(String.format(
            "%s,%s,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,REJECT,%s,%s,%s,%s,%s,%s",
            signalTime,
            "",
            "",
            0,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            escapeCsv(rejectReason),
            regime,
            timePhase,
            feedStable,
            "",
            ""
        ));
        log.info("Gate reject logged: {}", rejectReason);
    }

    public synchronized void logShadowExecution(
        LocalDateTime signalTime,
        LocalDateTime executionTime,
        double expectedEntryPrice,
        double actualEntryPrice,
        double expectedExitPrice,
        double actualExitPrice,
        boolean tp1Hit,
        boolean runnerCaptured,
        double mfe,
        double mae,
        double realizedR,
        String gateDecision,
        String rejectReason,
        Regime regime,
        TimePhase timePhase,
        boolean feedStable,
        String exitReason,
        LocalDateTime exitTime
    ) {
        logShadowExecution(signalTime, executionTime, "", expectedEntryPrice, actualEntryPrice,
            expectedExitPrice, actualExitPrice, tp1Hit, runnerCaptured, mfe, mae, realizedR,
            gateDecision, rejectReason, regime, timePhase, feedStable, exitReason, exitTime);
    }

    public synchronized void logShadowExecution(
        LocalDateTime signalTime,
        LocalDateTime executionTime,
        String direction,
        double expectedEntryPrice,
        double actualEntryPrice,
        double expectedExitPrice,
        double actualExitPrice,
        boolean tp1Hit,
        boolean runnerCaptured,
        double mfe,
        double mae,
        double realizedR,
        String gateDecision,
        String rejectReason,
        Regime regime,
        TimePhase timePhase,
        boolean feedStable,
        String exitReason,
        LocalDateTime exitTime
    ) {
        ensureHeader();
        long latencySec = java.time.Duration.between(signalTime, executionTime).getSeconds();
        double entrySlippage = actualEntryPrice - expectedEntryPrice;
        double exitSlippage = actualExitPrice - expectedExitPrice;

        appendRow(String.format(
            "%s,%s,%s,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%b,%b,%.4f,%.4f,%.4f,%s,%s,%s,%s,%b,%s,%s",
            signalTime,
            executionTime,
            direction == null ? "" : direction,
            latencySec,
            expectedEntryPrice,
            actualEntryPrice,
            entrySlippage,
            expectedExitPrice,
            actualExitPrice,
            exitSlippage,
            tp1Hit,
            runnerCaptured,
            mfe,
            mae,
            realizedR,
            gateDecision,
            escapeCsv(rejectReason),
            regime,
            timePhase,
            feedStable,
            escapeCsv(exitReason),
            exitTime
        ));

        log.info("Shadow execution logged with gateDecision={} exitReason={}", gateDecision, exitReason);
    }

    /**
     * BUG-043: Append row using persistent writer.
     * Synchronized to prevent concurrent writes.
     */
    private void appendRow(String row) {
        synchronized (writerLock) {
            if (csvWriter != null) {
                csvWriter.println(row);
                csvWriter.flush();
            } else {
                // Fallback to file-per-write if writer failed to initialize
                try {
                    Files.writeString(
                        Path.of(CSV_PATH),
                        row + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.SYNC
                    );
                } catch (IOException e) {
                    log.error("CRITICAL: FAILED TO WRITE TO PERSISTENT CSV", e);
                }
            }
        }
    }

    private void ensureHeader() {
        synchronized (writerLock) {
            if (csvWriter == null) {
                init();
            }
        }
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
