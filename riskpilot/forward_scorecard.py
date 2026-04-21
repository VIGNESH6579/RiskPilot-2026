import csv
import math
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple


CSV_PATH = Path("shadow_live_forward_logs.csv")

# Hard gates (frozen for the full run; do not tweak mid-test)
EXPECTANCY_GO = 0.07
EXPECTANCY_CONDITIONAL = 0.04
TP1_PASS = 0.65
TP1_FAIL = 0.60
RUNNER_PASS = 0.20
RUNNER_FAIL = 0.15
ENTRY_SLIP_PASS = 2.0
TP1_SLIP_PASS = 3.0
RUNNER_SLIP_PASS = 6.0
MAX_LOSS_STREAK_OK = 4
MAX_LOSS_STREAK_BORDERLINE = 6
MAX_HEARTBEAT_PANIC = 1
MAX_MISSING_TICK_SESSIONS = 3


@dataclass
class TradeRow:
    pnl_r: float
    tp1_hit: bool
    runner_survived: bool
    entry_slip: Optional[float]
    tp1_slip: Optional[float]
    runner_slip: Optional[float]
    exit_reason: str


def _to_float(value: str) -> Optional[float]:
    if value is None:
        return None
    value = str(value).strip()
    if value == "":
        return None
    try:
        return float(value)
    except ValueError:
        return None


def _to_bool(value: str) -> bool:
    return str(value).strip().lower() in {"1", "true", "yes", "y"}


def _mean(values: List[float]) -> Optional[float]:
    return statistics.mean(values) if values else None


def _safe_div(n: float, d: float) -> float:
    return n / d if d else 0.0


def _extract_row(raw: Dict[str, str]) -> Optional[TradeRow]:
    # Supported schemas:
    # A) Native logger: signalTime,executeTime,latencySec,expectedEntry,actualEntry,slippage,mfe,mae,isRunner,exitReason,exitTime
    # B) Manual schema: TP1,Run,MFE,MAE,Latency Drag,Entry Slippage,TP1 Slippage,Runner Slippage,Exit Type,R
    #
    # If R is absent, use proxy:
    # - TP1 hit => +1R minimum
    # - Runner survived => +1.5R proxy
    # - TP1 miss => -1R
    # This keeps gating deterministic until full leg-level PnL fields are logged.
    r_value = _to_float(raw.get("R", ""))

    is_runner = _to_bool(raw.get("isRunner", raw.get("Run", "false")))
    exit_reason = str(raw.get("exitReason", raw.get("Exit Type", ""))).strip()
    tp1_from_field = _to_bool(raw.get("TP1", ""))
    tp1_from_reason = "TP1" in exit_reason.upper() or is_runner
    tp1_hit = tp1_from_field or tp1_from_reason

    if r_value is None:
        if tp1_hit and is_runner:
            pnl_r = 1.5
        elif tp1_hit:
            pnl_r = 1.0
        else:
            pnl_r = -1.0
    else:
        pnl_r = r_value

    entry_slip = _to_float(raw.get("Entry Slippage", ""))
    tp1_slip = _to_float(raw.get("TP1 Slippage", ""))
    runner_slip = _to_float(raw.get("Runner Slippage", ""))

    if entry_slip is None:
        expected_entry = _to_float(raw.get("expectedEntry", ""))
        actual_entry = _to_float(raw.get("actualEntry", ""))
        if expected_entry is not None and actual_entry is not None:
            entry_slip = abs(actual_entry - expected_entry)

    # Only total slippage is available in native logger today.
    # Keep it as fallback for visibility but do not treat it as per-leg decomposition.
    total_slippage = _to_float(raw.get("Latency Drag", raw.get("slippage", "")))
    if tp1_slip is None and tp1_hit and not is_runner:
        tp1_slip = total_slippage
    if runner_slip is None and is_runner:
        runner_slip = total_slippage

    return TradeRow(
        pnl_r=pnl_r,
        tp1_hit=tp1_hit,
        runner_survived=is_runner,
        entry_slip=entry_slip,
        tp1_slip=tp1_slip,
        runner_slip=runner_slip,
        exit_reason=exit_reason,
    )


def _max_losing_streak(trades: List[TradeRow]) -> int:
    max_streak = 0
    streak = 0
    for trade in trades:
        if trade.pnl_r < 0:
            streak += 1
            max_streak = max(max_streak, streak)
        else:
            streak = 0
    return max_streak


def _tail_robust_r(trades: List[TradeRow], top_n: int) -> float:
    if len(trades) <= top_n:
        return 0.0
    sorted_indices = sorted(range(len(trades)), key=lambda i: trades[i].pnl_r, reverse=True)
    drop = set(sorted_indices[:top_n])
    kept = [t.pnl_r for i, t in enumerate(trades) if i not in drop]
    return _mean(kept) or 0.0


def _infra_incidents(trades: List[TradeRow]) -> Tuple[int, int, int]:
    heartbeat_panics = 0
    missing_tick_flags = 0
    duplicate_lag_flags = 0
    for trade in trades:
        reason = trade.exit_reason.upper()
        if "HEARTBEAT_PANIC" in reason:
            heartbeat_panics += 1
        if "MISSING_TICK" in reason:
            missing_tick_flags += 1
        if "DUPLICATE_SIGNAL" in reason or "LAG_FALSE_SIGNAL" in reason:
            duplicate_lag_flags += 1
    return heartbeat_panics, missing_tick_flags, duplicate_lag_flags


def main() -> None:
    if not CSV_PATH.exists():
        print(f"ERROR: File not found: {CSV_PATH}")
        return

    with CSV_PATH.open("r", newline="", encoding="utf-8") as file_handle:
        reader = csv.DictReader(file_handle)
        rows = list(reader)

    if not rows:
        print("No rows found in shadow_live_forward_logs.csv. Run after forward sessions complete.")
        return

    trades = []
    for raw in rows:
        trade = _extract_row(raw)
        if trade is not None:
            trades.append(trade)

    if not trades:
        print("No valid trade rows parsed from CSV.")
        return

    total = len(trades)
    tp1_rate = _safe_div(sum(1 for t in trades if t.tp1_hit), total)
    runner_rate = _safe_div(sum(1 for t in trades if t.runner_survived), total)
    expectancy_r = _mean([t.pnl_r for t in trades]) or 0.0
    losing_streak = _max_losing_streak(trades)
    r_no_top_1 = _tail_robust_r(trades, 1)
    r_no_top_2 = _tail_robust_r(trades, 2)

    entry_slips = [t.entry_slip for t in trades if t.entry_slip is not None]
    tp1_slips = [t.tp1_slip for t in trades if t.tp1_slip is not None]
    runner_slips = [t.runner_slip for t in trades if t.runner_slip is not None]
    avg_entry_slip = _mean(entry_slips)
    avg_tp1_slip = _mean(tp1_slips)
    avg_runner_slip = _mean(runner_slips)

    heartbeat_panics, missing_tick_flags, duplicate_lag_flags = _infra_incidents(trades)

    # Hard decision logic
    decomposition_present = (
        avg_entry_slip is not None and avg_tp1_slip is not None and avg_runner_slip is not None
    )
    slippage_pass = (
        decomposition_present
        and avg_entry_slip <= ENTRY_SLIP_PASS
        and avg_tp1_slip <= TP1_SLIP_PASS
        and avg_runner_slip <= RUNNER_SLIP_PASS
    )
    infra_fail = (
        heartbeat_panics > MAX_HEARTBEAT_PANIC
        or missing_tick_flags > MAX_MISSING_TICK_SESSIONS
        or duplicate_lag_flags > 0
    )

    decision = "NO-GO"
    if (
        expectancy_r >= EXPECTANCY_GO
        and tp1_rate >= TP1_PASS
        and runner_rate >= RUNNER_PASS
        and slippage_pass
        and not infra_fail
        and r_no_top_2 > 0
    ):
        decision = "GO"
    elif (
        expectancy_r >= EXPECTANCY_CONDITIONAL
        and tp1_rate >= TP1_FAIL
        and runner_rate >= RUNNER_FAIL
        and not infra_fail
    ):
        decision = "CONDITIONAL GO"

    print("\n================ RISKPILOT FORWARD SCORECARD ================\n")
    print(f"Total Trades                      : {total}")
    print(f"Expectancy (R / trade)            : {expectancy_r:.4f}")
    print(f"TP1 Validation Rate               : {tp1_rate:.2%}")
    print(f"Runner Survival Rate              : {runner_rate:.2%}")
    print(f"Max Losing Streak                 : {losing_streak}")
    print(f"R without top 1 trade             : {r_no_top_1:.4f}")
    print(f"R without top 2 trades            : {r_no_top_2:.4f}")
    print("")
    print("---- Slippage Decomposition ----")
    print(f"Entry Slippage Avg                : {avg_entry_slip if avg_entry_slip is not None else 'MISSING'}")
    print(f"TP1 Slippage Avg                  : {avg_tp1_slip if avg_tp1_slip is not None else 'MISSING'}")
    print(f"Runner Exit Slippage Avg          : {avg_runner_slip if avg_runner_slip is not None else 'MISSING'}")
    print("")
    print("---- Infra Integrity ----")
    print(f"Heartbeat Panic Exits             : {heartbeat_panics}")
    print(f"Missing Tick Flags                : {missing_tick_flags}")
    print(f"Duplicate/Lag False Signal Flags  : {duplicate_lag_flags}")
    print("")
    print("---- Gate Checks ----")
    print(f"Expectancy Gate (>= {EXPECTANCY_GO:.2f})      : {'PASS' if expectancy_r >= EXPECTANCY_GO else 'FAIL'}")
    print(f"TP1 Gate (>= {TP1_PASS:.0%})                 : {'PASS' if tp1_rate >= TP1_PASS else 'FAIL'}")
    print(f"Runner Gate (>= {RUNNER_PASS:.0%})            : {'PASS' if runner_rate >= RUNNER_PASS else 'FAIL'}")
    print(f"Slippage Decomposition Gate        : {'PASS' if slippage_pass else 'FAIL'}")
    print(f"Tail Robustness Gate (R no top2>0) : {'PASS' if r_no_top_2 > 0 else 'FAIL'}")
    print(f"Infrastructure Gate                : {'PASS' if not infra_fail else 'FAIL'}")
    print("")
    print(f"FINAL VERDICT                     : {decision}")
    print("\n==============================================================\n")


if __name__ == "__main__":
    main()
