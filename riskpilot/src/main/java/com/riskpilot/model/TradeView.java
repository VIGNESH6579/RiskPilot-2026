package com.riskpilot.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record TradeView(
    String signalTime,
    String executionTime,
    String direction,
    Double expectedEntry,
    Double actualEntry,
    Double expectedExit,
    Double actualExit,
    Double entrySlippage,
    Double exitSlippage,
    Double mfe,
    Double mae,
    Double realizedR,
    Boolean tp1Hit,
    Boolean runnerCaptured,
    String exitReason,
    String exitType,
    String regime,
    String timePhase,
    Boolean feedStable,
    Integer quantity,
    Integer remainingQuantity,
    Boolean recovery,
    Long entryLatencyMs,
    Long exitLatencyMs,
    Double latencySec,
    String gateDecision,
    String rejectReason,
    String exitTime
) {
    public static TradeView fromTradeLog(TradeLog log) {
        return new TradeView(
            stringify(log.getSignalTime()),
            stringify(log.getExecutionTime()),
            log.getDirection(),
            log.getExpectedEntry(),
            log.getActualEntry(),
            log.getExpectedExit(),
            log.getActualExit(),
            log.getEntrySlippage(),
            log.getExitSlippage(),
            log.getMfe(),
            log.getMae(),
            log.getRealizedR(),
            log.getTp1Hit(),
            log.getRunnerCaptured(),
            log.getExitReason(),
            log.getExitType(),
            log.getRegime(),
            log.getTimePhase(),
            log.getFeedStable(),
            log.getQuantity(),
            log.getRemainingQuantity(),
            log.getRecovery(),
            log.getEntryLatencyMs(),
            log.getExitLatencyMs(),
            log.getLatencySec(),
            log.getGateDecision(),
            log.getRejectReason(),
            stringify(log.getExitTime())
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("signalTime", signalTime);
        row.put("executionTime", executionTime);
        row.put("direction", direction);
        row.put("expectedEntry", expectedEntry);
        row.put("actualEntry", actualEntry);
        row.put("expectedExit", expectedExit);
        row.put("actualExit", actualExit);
        row.put("entrySlippage", entrySlippage);
        row.put("exitSlippage", exitSlippage);
        row.put("mfe", mfe);
        row.put("mae", mae);
        row.put("realizedR", realizedR);
        row.put("tp1Hit", tp1Hit);
        row.put("runnerCaptured", runnerCaptured);
        row.put("exitReason", exitReason);
        row.put("exitType", exitType);
        row.put("regime", regime);
        row.put("timePhase", timePhase);
        row.put("feedStable", feedStable);
        row.put("quantity", quantity);
        row.put("remainingQuantity", remainingQuantity);
        row.put("recovery", recovery);
        row.put("entryLatencyMs", entryLatencyMs);
        row.put("exitLatencyMs", exitLatencyMs);
        row.put("latencySec", latencySec);
        row.put("gateDecision", gateDecision);
        row.put("rejectReason", rejectReason);
        row.put("exitTime", exitTime);
        return row;
    }

    private static String stringify(LocalDateTime value) {
        return value != null ? value.toString() : null;
    }
}
