package com.riskpilot.service;

import com.riskpilot.model.Regime;
import com.riskpilot.model.TimePhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;

@Service
public class LiveMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger(LiveMetricsLogger.class);
    private static final String CSV_PATH = "shadow_live_forward_logs.csv";
    private static final String CSV_HEADER =
        "signalTime,executionTime,latencySec,expectedEntry,actualEntry,entrySlippage," +
        "expectedExit,actualExit,exitSlippage,tp1Hit,runnerCaptured,mfe,mae,realizedR," +
        "gateDecision,rejectReason,regime,timePhase,feedStable,exitReason,exitTime";

    public synchronized void logReject(
        LocalDateTime signalTime,
        String rejectReason,
        Regime regime,
        TimePhase timePhase,
        boolean feedStable
    ) {
        ensureHeader();
        appendRow(String.format(
            "%s,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,REJECT,%s,%s,%s,%s,%s,%s",
            signalTime,
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
        ensureHeader();
        long latencySec = java.time.Duration.between(signalTime, executionTime).getSeconds();
        double entrySlippage = actualEntryPrice - expectedEntryPrice;
        double exitSlippage = actualExitPrice - expectedExitPrice;

        appendRow(String.format(
            "%s,%s,%d,%f,%f,%f,%f,%f,%f,%b,%b,%f,%f,%f,%s,%s,%s,%s,%b,%s,%s",
            signalTime,
            executionTime,
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

    private void ensureHeader() {
        File csv = new File(CSV_PATH);
        if (!csv.exists() || csv.length() == 0) {
            appendRow(CSV_HEADER);
        }
    }

    private void appendRow(String row) {
        try (FileWriter fw = new FileWriter(CSV_PATH, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(row);
            pw.flush();
        } catch (IOException e) {
            log.error("CRITICAL: FAILED TO WRITE TO PERSISTENT CSV", e);
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
