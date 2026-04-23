from pathlib import Path
import pandas as pd
import numpy as np
import sys
from datetime import datetime

CSV_PATH = Path("shadow_live_forward_logs.csv")
KILL_FLAG_FILE = Path("KILL_SWITCH.flag")
KILL_LOG_FILE = Path("kill_log.csv")

# 🔴 KILL-SWITCH THRESHOLDS (NO DEBATE)
EXPECTANCY_GO = 0.07
EXPECTANCY_MIN = 0.04
EXPECTANCY_CRITICAL = 0.00  # Negative = immediate kill
TP1_PASS = 0.65
TP1_FAIL = 0.60
TP1_CRITICAL = 0.55  # Below this = kill
RUNNER_PASS = 0.20
RUNNER_FAIL = 0.15
RUNNER_CRITICAL = 0.10  # Below this = kill
MAX_ENTRY_SLIPPAGE = 2.0
MAX_TP1_SLIPPAGE = 3.0
MAX_RUNNER_SLIPPAGE = 6.0
CRITICAL_ENTRY_SLIPPAGE = 3.0  # Above this = kill
CRITICAL_RUNNER_SLIPPAGE = 7.0  # Above this = kill
MAX_LOSS_STREAK = 7  # Above this = kill
MAX_HEARTBEAT_PANICS = 1  # Above this = kill
MAX_FEED_FAILURES = 3  # Above this = kill
TAIL_TOP_N = 2


def _bool_series(series: pd.Series) -> pd.Series:
    return series.astype(str).str.lower().isin({"true", "1", "yes", "y"})


def evaluate_kill_switch_metrics(total_trades, expectancy, tp1_rate, runner_rate, 
                               avg_entry_slip, tp1_slip, runner_slip, max_loss_streak,
                               heartbeat_panics, feed_failures) -> list:
    """🔴 KILL-SWITCH EVALUATION - NO DEBATE, NO WARNINGS"""
    reasons = []

    # EDGE COLLAPSE (PRIMARY TRIGGER)
    if expectancy < EXPECTANCY_CRITICAL:
        reasons.append("EXPECTANCY_NEGATIVE")
    elif expectancy < EXPECTANCY_MIN:
        reasons.append("EXPECTANCY_BREAKDOWN")

    # EXECUTION FAILURE
    if avg_entry_slip > CRITICAL_ENTRY_SLIPPAGE:
        reasons.append("ENTRY_SLIPPAGE_CRITICAL")
    elif avg_entry_slip > MAX_ENTRY_SLIPPAGE:
        reasons.append("ENTRY_SLIPPAGE_HIGH")

    if tp1_slip > MAX_TP1_SLIPPAGE:
        reasons.append("TP1_SLIPPAGE_HIGH")

    if runner_slip > CRITICAL_RUNNER_SLIPPAGE:
        reasons.append("RUNNER_SLIPPAGE_CRITICAL")

    # STRUCTURAL FAILURE
    if tp1_rate < TP1_CRITICAL:
        reasons.append("TP1_VALIDATION_FAIL")
    elif tp1_rate < TP1_FAIL:
        reasons.append("TP1_WEAK")

    if runner_rate < RUNNER_CRITICAL:
        reasons.append("RUNNER_EDGE_LOSS")
    elif runner_rate < RUNNER_FAIL:
        reasons.append("RUNNER_WEAK")

    # RISK BREACH
    if max_loss_streak >= MAX_LOSS_STREAK:
        reasons.append("LOSS_STREAK_CRITICAL")

    # INFRA FAILURE
    if heartbeat_panics > MAX_HEARTBEAT_PANICS:
        reasons.append("HEARTBEAT_FAILURE")

    if feed_failures > MAX_FEED_FAILURES:
        reasons.append("FEED_UNSTABLE")

    return reasons


def write_kill_switch(reasons: list) -> None:
    """🔴 HARD KILL SWITCH - SYSTEM SHUTDOWN"""
    print("\n🚨 KILL SWITCH ACTIVATED 🚨")
    print("Reasons:", reasons)
    
    # Write kill flag file (Java will read this)
    try:
        KILL_FLAG_FILE.write_text("\n".join(reasons))
        print(f"🔴 KILL FLAG WRITTEN: {KILL_FLAG_FILE}")
    except Exception as e:
        print(f"ERROR: Failed to write kill flag: {e}")
        sys.exit(1)  # Still exit even if write fails

    # Append to kill log
    try:
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        log_entry = f"{timestamp},{'|'.join(reasons)}\n"
        with open(KILL_LOG_FILE, "a") as f:
            f.write(log_entry)
        print(f"📝 KILL LOG UPDATED: {KILL_LOG_FILE}")
    except Exception as e:
        print(f"ERROR: Failed to write kill log: {e}")

    # HARD EXIT - NO RECOVERY
    print("🔴 SYSTEM SHUTTING DOWN - INVESTIGATE BEFORE RESTART")
    sys.exit(1)


def main() -> None:
    if not CSV_PATH.exists():
        print(f"ERROR: file not found: {CSV_PATH}")
        return

    df = pd.read_csv(CSV_PATH)
    if df.empty:
        print("No rows found. Run after market close.")
        return

    # Trade rows only; keep rejects for integrity stats
    all_rows = df.copy()
    trades = df[df["gateDecision"].astype(str).str.upper() == "ALLOW"].copy()
    if trades.empty:
        print("No executed trades found (ALLOW rows are empty).")
        return

    trades["tp1Hit"] = _bool_series(trades["tp1Hit"])
    trades["runnerCaptured"] = _bool_series(trades["runnerCaptured"])
    trades["feedStable"] = _bool_series(trades["feedStable"])
    trades["realizedR"] = pd.to_numeric(trades["realizedR"], errors="coerce").fillna(0.0)
    trades["entrySlippage"] = pd.to_numeric(trades["entrySlippage"], errors="coerce").fillna(np.nan)
    trades["exitSlippage"] = pd.to_numeric(trades["exitSlippage"], errors="coerce").fillna(np.nan)
    trades["signalTime"] = pd.to_datetime(trades["signalTime"], errors="coerce")
    trades = trades.dropna(subset=["signalTime"])

    total_trades = len(trades)
    expectancy = trades["realizedR"].mean()
    tp1_rate = trades["tp1Hit"].mean()
    runner_rate = trades["runnerCaptured"].mean()
    avg_entry_slip = trades["entrySlippage"].mean()

    tp1_slip = trades.loc[trades["tp1Hit"], "exitSlippage"].mean()
    runner_slip = trades.loc[trades["runnerCaptured"], "exitSlippage"].mean()

    sorted_r = trades.sort_values(by="realizedR", ascending=False)
    amputated = sorted_r.iloc[TAIL_TOP_N:] if len(sorted_r) > TAIL_TOP_N else sorted_r
    fragile_expectancy = amputated["realizedR"].mean() if not amputated.empty else 0.0

    losses = trades["realizedR"] < 0
    max_loss_streak, current = 0, 0
    for loss in losses:
        if loss:
            current += 1
            max_loss_streak = max(max_loss_streak, current)
        else:
            current = 0

    heartbeat_panics = trades["exitReason"].astype(str).str.contains("HEARTBEAT_PANIC", case=False, na=False).sum()
    rejects = (all_rows["gateDecision"].astype(str).str.upper() == "REJECT").sum()
    feed_failures = (~trades["feedStable"]).sum()

    trades["date"] = trades["signalTime"].dt.date
    daily = trades.groupby("date")["realizedR"].mean().rename("daily_expectancy")
    rolling_5 = daily.rolling(5, min_periods=1).mean().rename("rolling_5d")

    verdict = "GO"
    if expectancy < EXPECTANCY_MIN:
        verdict = "NO-GO"
    elif expectancy < EXPECTANCY_GO:
        verdict = "CONDITIONAL"

    if tp1_rate < TP1_FAIL or runner_rate < RUNNER_FAIL:
        verdict = "NO-GO"
    elif (tp1_rate < TP1_PASS or runner_rate < RUNNER_PASS) and verdict != "NO-GO":
        verdict = "CONDITIONAL"

    if avg_entry_slip > MAX_ENTRY_SLIPPAGE or tp1_slip > MAX_TP1_SLIPPAGE or runner_slip > MAX_RUNNER_SLIPPAGE:
        verdict = "NO-GO"

    if fragile_expectancy < 0:
        verdict = "NO-GO"

    print("\n==============================")
    print("RISKPILOT FORWARD SCORECARD")
    print("==============================")
    print(f"Total Trades: {total_trades}")
    print(f"Expectancy (R): {expectancy:.4f}")
    print(f"TP1 Rate: {tp1_rate:.2%}")
    print(f"Runner Rate: {runner_rate:.2%}")
    print(f"Max Loss Streak: {max_loss_streak}")
    print("\n--- Slippage ---")
    print(f"Entry: {avg_entry_slip:.2f}")
    print(f"TP1 Exit: {tp1_slip:.2f}")
    print(f"Runner Exit: {runner_slip:.2f}")
    print("\n--- Tail Fragility ---")
    print(f"Original R: {expectancy:.4f}")
    print(f"Without Top {TAIL_TOP_N}: {fragile_expectancy:.4f}")
    print("\n--- Integrity ---")
    print(f"Rejects: {rejects}")
    print(f"Feed Failures: {feed_failures}")
    print(f"Heartbeat Panic Exits: {heartbeat_panics}")
    print("\n--- Daily / Rolling 5D ---")
    summary = pd.concat([daily, rolling_5], axis=1)
    print(summary.tail(7).to_string())
    print("\n==============================")
    print(f"FINAL VERDICT: {verdict}")
    print("==============================\n")

    # 🔴 KILL-SWITCH EVALUATION (FINAL AUTHORITY)
    kill_reasons = evaluate_kill_switch_metrics(
        total_trades, expectancy, tp1_rate, runner_rate,
        avg_entry_slip, tp1_slip, runner_slip, max_loss_streak,
        heartbeat_panics, feed_failures
    )

    if kill_reasons:
        write_kill_switch(kill_reasons)
    else:
        print("✅ KILL SWITCH: PASSED - System can continue")


if __name__ == "__main__":
    main()
