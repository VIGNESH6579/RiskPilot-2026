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
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LiveMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger(LiveMetricsLogger.class);
    private static final String CSV_HEADER =
        "signalTime,executionTime,direction,latencySec,entryLatencyMs,exitLatencyMs,expectedEntry,actualEntry,entrySlippage," +
        "expectedExit,actualExit,exitSlippage,tp1Hit,runnerCaptured,mfe,mae,realizedR,quantity,remainingQuantity,lotSize,pointValue,recovery," +
        "gateDecision,rejectReason,regime,timePhase,feedStable,exitReason,exitType,exitTime";

    private final TradeLogRepository tradeLogRepository;

    public synchronized TradeLog logReject(
        LocalDateTime signalTime,
        String rejectReason,
        Regime regime,
        TimePhase timePhase,
        boolean feedStable
    ) {
        LocalDateTime effectiveSignalTime = signalTime != null ? signalTime : LocalDateTime.now();
        ensureHeader();
        appendRow(String.join(",",
            effectiveSignalTime.toString(),
            "",
            "NA",
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
            "",
            "",
            "",
            "0",
            "0",
            "0",
            "0.0",
            "false",
            "REJECT",
            escapeCsv(rejectReason),
            regime != null ? regime.name() : "",
            timePhase != null ? timePhase.name() : "",
            Boolean.toString(feedStable),
            "",
            "",
            ""
        ));

        return persistLog(TradeLog.builder()
            .signalTime(effectiveSignalTime)
            .latencySec(0.0)
            .entryLatencyMs(0L)
            .exitLatencyMs(0L)
            .direction("NA")
            .quantity(0)
            .remainingQuantity(0)
            .lotSize(0)
            .pointValue(0.0)
            .recovery(false)
            .gateDecision("REJECT")
            .rejectReason(rejectReason)
            .regime(regime != null ? regime.name() : null)
            .timePhase(timePhase != null ? timePhase.name() : null)
            .feedStable(feedStable)
            .build());
    }

    public synchronized TradeLog logShadowExecution(
        LocalDateTime signalTime,
        LocalDateTime executionTime,
        String direction,
        long entryLatencyMs,
        long exitLatencyMs,
        double expectedEntryPrice,
        double actualEntryPrice,
        double expectedExitPrice,
        double actualExitPrice,
        boolean tp1Hit,
        boolean runnerCaptured,
        double mfe,
        double mae,
        double realizedR,
        int quantity,
        int remainingQuantity,
        int lotSize,
        double pointValue,
        String gateDecision,
        String rejectReason,
        Regime regime,
        TimePhase timePhase,
        boolean feedStable,
        String exitReason,
        String exitType,
        boolean recovery,
        LocalDateTime exitTime
    ) {
        LocalDateTime effectiveSignalTime = signalTime != null ? signalTime : LocalDateTime.now();
        LocalDateTime effectiveExecutionTime = executionTime != null ? executionTime : effectiveSignalTime;
        LocalDateTime effectiveExitTime = exitTime != null ? exitTime : effectiveExecutionTime;
        ensureHeader();
        double latencySec = entryLatencyMs / 1000.0;
        double entrySlippage = calculateEntrySlippage(direction, expectedEntryPrice, actualEntryPrice);
        double exitSlippage = calculateExitSlippage(direction, expectedExitPrice, actualExitPrice);

        appendRow(String.format(
            "%s,%s,%s,%f,%d,%d,%f,%f,%f,%f,%f,%f,%b,%b,%f,%f,%f,%d,%d,%d,%f,%b,%s,%s,%s,%s,%b,%s,%s,%s",
            effectiveSignalTime,
            effectiveExecutionTime,
            direction,
            latencySec,
            entryLatencyMs,
            exitLatencyMs,
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
            quantity,
            remainingQuantity,
            lotSize,
            pointValue,
            recovery,
            gateDecision,
            escapeCsv(rejectReason),
            regime,
            timePhase,
            feedStable,
            escapeCsv(exitReason),
            escapeCsv(exitType),
            effectiveExitTime
        ));

        return persistLog(TradeLog.builder()
            .signalTime(effectiveSignalTime)
            .executionTime(effectiveExecutionTime)
            .latencySec(latencySec)
            .entryLatencyMs(entryLatencyMs)
            .exitLatencyMs(exitLatencyMs)
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
            .direction(direction)
            .quantity(quantity)
            .remainingQuantity(remainingQuantity)
            .lotSize(lotSize)
            .pointValue(pointValue)
            .recovery(recovery)
            .gateDecision(gateDecision)
            .rejectReason(rejectReason)
            .regime(regime != null ? regime.name() : null)
            .timePhase(timePhase != null ? timePhase.name() : null)
            .feedStable(feedStable)
            .exitReason(exitReason)
            .exitType(exitType)
            .exitTime(effectiveExitTime)
            .build());
    }

    private double calculateEntrySlippage(String direction, double expectedEntryPrice, double actualEntryPrice) {
        if ("SHORT".equalsIgnoreCase(direction)) {
            return expectedEntryPrice - actualEntryPrice;
        }
        return actualEntryPrice - expectedEntryPrice;
    }

    private double calculateExitSlippage(String direction, double expectedExitPrice, double actualExitPrice) {
        if ("SHORT".equalsIgnoreCase(direction)) {
            return actualExitPrice - expectedExitPrice;
        }
        return expectedExitPrice - actualExitPrice;
    }

    private TradeLog persistLog(TradeLog tradeLog) {
        try {
            return tradeLogRepository.save(tradeLog);
        } catch (Exception e) {
            log.error("CRITICAL: Trade log persistence failed", e);
            return tradeLog;
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
