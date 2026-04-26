package com.riskpilot.service;

import com.riskpilot.model.Regime;
import com.riskpilot.model.TimePhase;
import com.riskpilot.model.TradeLog;
import com.riskpilot.repository.TradeLogRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LiveMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger(LiveMetricsLogger.class);
    private static final String CSV_HEADER =
        "signalTime,executionTime,latencySec,expectedEntry,actualEntry,entrySlippage," +
        "expectedExit,actualExit,exitSlippage,tp1Hit,runnerCaptured,mfe,mae,realizedR," +
        "gateDecision,rejectReason,regime,timePhase,feedStable,exitReason,exitTime";

    private final TradeLogRepository tradeLogRepository;

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

        persistLog(TradeLog.builder()
            .signalTime(signalTime)
            .latencySec(0.0)
            .gateDecision("REJECT")
            .rejectReason(rejectReason)
            .regime(regime != null ? regime.name() : null)
            .timePhase(timePhase != null ? timePhase.name() : null)
            .feedStable(feedStable)
            .build());
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
        long latencySec = Duration.between(signalTime, executionTime).getSeconds();
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

        persistLog(TradeLog.builder()
            .signalTime(signalTime)
            .executionTime(executionTime)
            .latencySec((double) latencySec)
            .expectedEntry(expectedEntryPrice)
            .actualEntry(actualEntryPrice)
            .entrySlippage(entrySlippage)
            .expectedExit(expectedExitPrice)
            .actualExit(actualExitPrice)
            .exitSlippage(exitSlippage)
            .tp1Hit(tp1Hit)
            .runnerCaptured(runnerCaptured)
            .mfe(mfe)
            .mae(mae)
            .realizedR(realizedR)
            .gateDecision(gateDecision)
            .rejectReason(rejectReason)
            .regime(regime != null ? regime.name() : null)
            .timePhase(timePhase != null ? timePhase.name() : null)
            .feedStable(feedStable)
            .exitReason(exitReason)
            .exitTime(exitTime)
            .build());
    }

    private void persistLog(TradeLog tradeLog) {
        try {
            tradeLogRepository.save(tradeLog);
        } catch (Exception e) {
            log.warn("Trade log DB persist failed: {}", e.getMessage());
        }
    }

    private void ensureHeader() {
        File csv = new File(csvPath());
        if (!csv.exists() || csv.length() == 0) {
            appendRow(CSV_HEADER);
        }
    }

    private void appendRow(String row) {
        try (FileWriter fw = new FileWriter(csvPath(), true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(row);
            pw.flush();
        } catch (IOException e) {
            log.error("Failed to write forward metrics CSV", e);
        }
    }

    private String csvPath() {
        return System.getenv().getOrDefault("RISKPILOT_CSV_PATH", "shadow_live_forward_logs.csv");
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
