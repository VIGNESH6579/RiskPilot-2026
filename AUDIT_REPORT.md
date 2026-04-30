# RiskPilot-2026 — Brutal Senior-Architect / Quant-Systems Audit (Shadow-Mode Edition)

**Scope:** entire repository as of `main`.
**Mode under audit:** `SHADOW` only (operator confirmed: real broker order execution is **out of scope by design**).
**Stated intent:** produce trustworthy shadow-mode P&L, R-multiple, slippage, and regime telemetry from real Angel One SmartStream LTP data, so the strategy can be evaluated without touching capital.

**Re-framed central question, given shadow-only intent:**

> Can the numbers this system produces be trusted as a basis for any decision about the strategy?

Tone: no praise, no hedging.

Severity legend:
- **BLOCKER** — corrupts shadow telemetry; the numbers cannot be trusted at all.
- **HIGH** — biases shadow telemetry in a known direction; numbers must be discounted.
- **MEDIUM** — operational hazard; you will lose data, miss days, or chase ghosts.
- **LOW** — hygiene.

> **Note on what changed vs the prior audit pass:** the operator has confirmed there will be no real Angel One order placement. Findings about freeze-quantity, LPP/UPP, partial fills, cancel-on-disconnect, FIX semantics, broker reconciliation, etc. are now correctly classified as **N/A**. They are listed in §11 for completeness but are **not** counted against the verdict.

---

## 1. BLOCKERS — corrupt the shadow telemetry itself

### 1.1 [BLOCKER] `StrictValidationService.validateFreshTick` overwrites `tick.receivedAt` with `now`
- Lines 237–246: the returned `acceptedTick` is rebuilt with the **validation-time** `now` as `receivedAt`, instead of the real WebSocket-receive instant. `MarketTick.of(...)` then recomputes `sourceAgeMs` from the rewritten value.
- Every downstream metric is falsified by this one line:
  - `MarketDataStateService.lastTickAgeMs` reads "younger than reality" → false freshness.
  - `silenceMs` in `HeartbeatMonitor` resets on the wrong instant.
  - `estimateTickGapMs` used by `ExecutionSimulator.plan` becomes `now − now ≈ small`, biasing the simulator toward **lower simulated latency and lower simulated slippage** every single trade.
- Net effect: the entire shadow report is biased toward optimism. **You cannot use shadow slippage or shadow latency numbers for anything.**

### 1.2 [BLOCKER] `CandleAggregator.processTick` silently corrupts already-closed candles
- Lines 112–134: when a tick arrives whose `candleAlignedTime` is *before* the current building candle's start (out-of-order or replayed tick — common on Angel reconnects), the `else` branch fires and `applyTick` is run against the **current** candle. OHLC of a candle that is logically in the past gets mutated.
- No use of `MarketTick.sequenceId` to deduplicate or reject out-of-sequence ticks; the field is logged but never used as a filter.
- **All historical OHLC data the strategy is trained on is silently corruptible.** TrapEngine reads exactly these candles. Garbage in → garbage out.

### 1.3 [BLOCKER] Date-rollover bug in `CandleAggregator`
- Comparison at line 113 uses `LocalTime.isAfter`, which has no notion of date.
- A long-lived JVM that survives midnight (test harness, dev run, weekend) will have the first Monday 09:15 tick compare *before* the persisted Friday 15:25 candle and update Friday's candle instead of starting a new one.
- `clearHistory()` is only called from `restart()` and the daily-reset cron (which itself is on the deadlock path — see 1.5). If either is delayed or fails, the bug bites silently.

### 1.4 [BLOCKER] `feedUnstable` flag is *assigned*, not OR'd, on every tick
- `CandleAggregator` line 87: `feedUnstable = (lastArrivalTime != null && arrivalGapMs > 4500L);`
- A previous `markUnstable()` call (e.g. from `AngelTickStreamClient` on parse failure or from `HeartbeatMonitor.monitorHealth` on silence) is **silently cleared** by the next tick that arrives within 4.5 s.
- Instability events vanish from the data before any consumer can react. Shadow trades will be opened during what was actually an unstable feed window, then attributed to "stable feed" in the post-hoc telemetry. **You will believe the strategy has more uptime than it does.**

### 1.5 [BLOCKER] AB/BA deadlock between `tradeStateLock` and `CandleAggregator`'s monitor
- Tick path lock order: `synchronized(CandleAggregator.this)` → publishes `CandleClosedEvent` → `ShadowExecutionEngine.evaluateCandle` → `tradeStateLock.lock()`. Order: **CandleAggregator → tradeStateLock**.
- Daily-reset path lock order: `ShadowExecutionEngine.executeDailyHardReset` → `tradeStateLock.lock()` → `candleAggregator.clearHistory()` → `synchronized(CandleAggregator.this)`. Order: **tradeStateLock → CandleAggregator**.
- Two threads on opposite paths → classic deadlock. Triggers most cleanly at exactly 09:15 IST when the cron and the first tick race. Once dead, the WebSocket read loop stops, `HeartbeatMonitor` eventually marks the feed halted, and the rest of the day produces no shadow data **and no error other than silence**.
- For shadow-mode trustworthiness this is a blocker because you will lose entire days without knowing why.

### 1.6 [BLOCKER] No NSE holiday calendar, no weekend gate
- `MarketSessionService.isMarketOpen` only checks `time ∈ [09:15, 15:30)`.
- On Diwali muhurat the shadow system runs at the wrong window. On any non-trading weekday (Republic Day, Budget bank holiday, NSE-declared holiday) the system is nominally "open" — and if any feed source delivers a non-stale tick (replayed feed, broker bug, test harness), shadow trades will be recorded with no annotation that they happened on a closed day.
- **Backtest aggregations will be polluted by trades that could not have occurred in real life.**

### 1.7 [BLOCKER] Shadow P&L is computed in *index points × pointValue*, but the strategy claims to trade *options*
- `ShadowExecutionEngine.pnlInr(trade, price, lots)` line ~1107: `(price − entryPrice) × pointValue × unitsForLots(lots)`.
- `pointValue` is a configured constant (NIFTY ₹50/pt). This is **futures math**, not options math.
- For an options book, realised P&L per index point ≈ `delta` (≪ 1) for OTM/near-money positions, plus gamma/theta/vega contributions. The number this system prints is therefore wrong by a factor of `1/delta` — typically 3×–5× too optimistic for the position the operator presumably intends to take.
- For shadow-mode trustworthiness: the strategy *might* still be a real edge, but you have no way to read whether it is, because the displayed ₹ figure has no monetary meaning. **R-multiples derived from this P&L are also wrong.** Daily-loss-R, win-rate, expectancy — all wrong.
- Two acceptable fixes:
  1. Restrict the strategy *explicitly* to NIFTY index futures and rename the engine accordingly. The math then becomes correct and the comparison to live futures execution is meaningful.
  2. Add a real option-leg model (delta-aware fill price, theta decay across the holding period, gamma adjustment to delta over the move). This is non-trivial.

### 1.8 [BLOCKER] No persistence transaction boundaries → silent data corruption on restart
- `closeTrade` runs nine sequential side effects (analytics insert → WS broadcast → in-memory updates → ntfy HTTP → DB update → state update). **None of them are inside a `@Transactional`.** A crash between steps 1 and 8 leaves:
  - The analytics row written.
  - The `Trade` entity still `status=ACTIVE`.
  - The in-memory engine without an active trade.
- On startup, `restoreActiveTrade` then rehydrates a phantom open position that already exited. Worse, it computes its `initialRiskPoints` from the *current* persisted SL — which may already have been moved to breakeven on TP1 — yielding a near-zero risk-per-trade for everything that follows that trade. **All R-multiples printed after that point are mathematically wrong.**
- For shadow-mode trustworthiness this is a blocker because the recovery path silently produces wrong numbers, not crashes.

### 1.9 [BLOCKER] `restoreActiveTrade` recomputes `initialRiskPoints` from current SL
- Same root cause as 1.8 but worth listing separately because it bites even when there is **no** crash — every clean restart of an in-flight trade post-TP1 wrecks subsequent R-multiples for that trade.

---

## 2. HIGH — biases shadow telemetry in a known direction

### 2.1 [HIGH] `closeTrade` re-validates exit slippage but swallows the exception
- `strictValidationService.validateExitExecution(...)` runs inside `try { … } catch (Exception e) { log.warn(…) }`. The strict-mode exit gate is silently neutered for exits.
- Same pattern for every persistence call (`persistOpenedTrade`, `syncPersistedActiveTrade`, `finalizePersistedTrade`, `cancelPersistedActiveTrade`, `restoreActiveTrade`): catch, log, continue. Combined with 1.8 you get partial writes that are also silently un-flagged.
- Result: the "this exit was rejected by strict validation" telemetry that the operator presumably wants to read in shadow mode never appears. You will see a clean exit row even when the exit was non-conforming.

### 2.2 [HIGH] `ActiveTradeExecution.fromTickTP1` rounds TP1 lots to integer
- `tp1Lots = max(1, round(quantity * 0.20))`. For `quantity = 1` this is `1` (i.e. **100% at TP1, zero runner**). For `quantity = 2` it is `1` (50%). For `quantity = 3` it is `1` (33%). For `quantity = 4` it is `1` (25%).
- The strategy claim "scale 20% at TP1, runner the rest" is not what the code does at the lot counts a real account will use. Shadow runner-rate, runner-slippage, runner-expectancy numbers are therefore **structurally biased** away from what the strategy is supposed to be measuring.

### 2.3 [HIGH] `RegimeFilter.tradingAllowed` is computed but never gated on
- `RiskGateEngine.evaluateEntry` only checks `state.regime() != Regime.TREND`. The whole regime score / `tradingAllowed` flag and the parallel `RegimeConfidenceEngine` (55/70 thresholds) are decorative — they appear in `/health` but do not block any trade.
- Shadow telemetry will therefore show entries during regimes the engineering claims to filter out. The "regime gating works" hypothesis cannot be tested from this data.

### 2.4 [HIGH] `pointValue` in shadow P&L confuses lot count with unit count
- See 1.7. The compounding effect: `unitsForLots(lots) = lots × lotSize`, and `pnlInr = pricePoints × pointValue × units`. For NIFTY, `pointValue` is ₹50 and `lotSize` is 50 → `units` ≈ 50 × lots → P&L is multiplied by `50` an extra time vs the futures-correct formula `pricePoints × pointValue × lots`. **The figure is off by `lotSize` even before considering options/delta.** Worth verifying against the configured properties — if `pointValue` was set to `1` to compensate, the configuration is concealing the bug.

### 2.5 [HIGH] `TrapEngine` magic numbers (6, 10, 120 pts), hardcoded "NIFTY", no ATR normalisation
- 6.0 pt minimum breakout depth, 10.0 pt SL buffer, 120.0 pt max stop distance. Zero parameter is regime-aware or volatility-normalised. NIFTY in 2018 vs 2024 has very different intraday ranges; the same numbers cannot be optimal for both.
- Shadow telemetry from this engine measures the trap-with-these-specific-constants, not "the trap strategy" generally. Walk-forward conclusions cannot generalise.

### 2.6 [HIGH] Trailing stop in `ActiveTradeExecution.fromCandleClose` is not an ATR
- Computed as `atr = candle.high − candle.low` (just the last candle's range) and buffer `max(10, atr × 0.4)`. Wide candle → permissive trail; narrow candle → 10pt above last bar. Not a coherent trail model — runner-stop telemetry is therefore not a clean read on "would a real ATR-trail work?".

### 2.7 [HIGH] `PositionSizer` uses `equityInr` that includes unrealised P&L
- `RiskEngine.refresh` blends realised + unrealised. Position size therefore drifts during an open trade and across consecutive trades in a non-obvious way that is **not** documented as compounding. Reported risk-per-trade in shadow telemetry is internally consistent but does not match the static "0.5%" the operator probably expects.

### 2.8 [HIGH] Daily-loss-R only counts losses (`Math.min(0.0, realizedR)`), wins do not relax it
- `closeTrade` line ~686. This is "cumulative gross loss only", not what most desks call "daily loss limit". Shadow telemetry on "days the daily-loss kicked in" will overstate compared to the conventional definition. Either intentional or a bug — either way it is undocumented.

### 2.9 [HIGH] `RiskEngine.dailyLossLimitBreached` reads `realizedPnl` only
- A trade in deep drawdown that has not hit SL is invisible to the gate. A gap-down on the next candle exceeding the limit by 3× will only be noticed *after* exit. In shadow mode this means the kill-on-daily-loss event is delayed vs reality — telemetry on its trigger frequency understates.

### 2.10 [HIGH] `rejectReasonCounts` grows unbounded
- `StrictValidationService.validateFreshTick` synthesises reasons like `"LIVE_TICK_STALE: age=812ms max=2000ms"` (lines 208–212). Each unique value is a new key in `rejectReasonCounts`. Long-running JVM → unbounded heap growth.
- Operationally: you will see the JVM die after a few weeks of uptime, losing the in-memory edge tracker / regime tracker / streak counters that have not been persisted. **Adaptive-regime / edge-tracker windows cannot be trusted across long runs** for this reason.

### 2.11 [HIGH] In-memory state for `RealTimeEdgeTracker`, `AdaptiveRegimeEngine`, `StrictValidationService`, regime histories — none persisted
- A pod restart wipes the moving windows the operator presumably wants to evaluate the strategy with. Combined with 2.10, every long-window read is suspect.

---

## 3. MEDIUM — operational hazards that will cost you data or days

### 3.1 [MEDIUM] Single-thread broadcast executor with unbounded `LinkedBlockingQueue`
- One slow UI client can OOM the JVM. In shadow mode this kills your data run.

### 3.2 [MEDIUM] Synchronous JPA writes on the WebSocket thread
- `tradeRepository.save(...)` blocking inside `tradeStateLock` on the WS-driven candle path. P99 of any Postgres write can exceed `maxSilenceMs`, causing the `HeartbeatMonitor` to mark the feed halted **even though the feed is fine**. False heartbeat panics → shadow data discarded for the rest of that window.

### 3.3 [MEDIUM] `KillSwitchEngine` is not actually a kill-switch
- Relative path `KILL_SWITCH.flag` (depends on JVM cwd on Render — opaque).
- `evaluateInternal(MetricsWindow)` has zero callers; the "internal" kill-switch is dead code.
- `writeKillSwitch` has zero Java callers; the comment refers to a "Python forward_scorecard" that does not exist in the repo.
- For shadow mode the practical impact is reduced (no real money), but it means there is no automatic mechanism to halt a misbehaving shadow run that is polluting your dataset with bad data. You have to manually SIGTERM.

### 3.4 [MEDIUM] No NTP-skew tolerance on TOTP
- `AngelAuthService` uses `System.currentTimeMillis()` to compute the TOTP, no two-bucket retry. Container clock drift > 30s on a cold start → permanent auth failure → your shadow run is dead until manual restart.

### 3.5 [MEDIUM] `AngelAuthService` calls `https://api.ipify.org` synchronously inside `authenticate()`
- Adds an external dependency to your auth path. ipify outage = your shadow system can't auth. Also leaks your deployment IP to a third party every reconnect.

### 3.6 [MEDIUM] No `RestTemplate` connect/read timeouts
- `AngelAuthService` uses default `RestTemplate`. Slow Angel API → indefinite stall on the auth thread → the WS reconnect chain stalls behind it.

### 3.7 [MEDIUM] `restoreSessionCandles` runs in `@PostConstruct` doing a synchronous `findTop50`
- If Postgres is slow at boot, the container fails the readiness probe → Render restarts → loops. Cascading failure.

### 3.8 [MEDIUM] `HeartbeatMonitor.monitorHealth` writes a fresh snapshot every 2s unconditionally
- Causes a state broadcast every 2s even when nothing changed. Wastes WS bandwidth and shows up as background noise in any per-event analysis you do downstream.

### 3.9 [MEDIUM] `AngelAuthService.preMarketAuth` is `@Scheduled(cron="0 0 9 * * MON-FRI")` and does not respect the holiday calendar
- Burns one TOTP attempt on every NSE holiday Monday–Friday. Angel rate-limits aggressive auth; this can lock your account temporarily during a normal week with two holidays.

### 3.10 [MEDIUM] No `volatile` on hot-path fields read outside the lock
- `activeTradeId`, `lastTriggeredCandleTime`, `currentJwtToken`, `currentFeedToken`, `pendingExit`, `lastRegimeConfidenceScore` and others are written under `tradeStateLock` and read on the broadcast/UI path without it. Stale reads forever possible on a long-running JVM. Practically rare but nondeterministic, which is the worst kind of bug to have when validating telemetry.

### 3.11 [MEDIUM] No optimistic locking (`@Version`) on `Trade`
- Concurrent `tradeRepository.save(entity)` against the same row is last-write-wins. `syncPersistedActiveTrade` (per-tick) racing `finalizePersistedTrade` (close) racing startup `restoreActiveTrade` is reachable.

### 3.12 [MEDIUM] No idempotency / dedup on Angel re-subscription
- WS reconnect mid-second can leave a prior subscription live → duplicate ticks. `MarketTick.sequenceId` exists but is not used to deduplicate. Duplicate ticks bias `ExecutionSimulator.estimateTickGapMs` toward zero, biasing simulated slippage downward — same direction as 1.1, compounding.

### 3.13 [MEDIUM] Two parallel regime systems (`RegimeFilter` + `RegimeConfidenceEngine`) with different thresholds
- Either decorative (only `RegimeFilter` is read by the gate) or unsynchronised. Pick one. Today it is the first one, with the second emitted only as a UI score — but the architecture suggests they were meant to agree.

### 3.14 [MEDIUM] No correlation IDs on log lines
- Tracing a single shadow trade across the 10–15 log statements that touch it is grep-by-trade-id. Trade ID is printed in *some* lines, not all.

### 3.15 [MEDIUM] Per-tick logging at INFO including hex packet preview
- ~10 KB/s baseline log volume. Render free-tier log retention will be exhausted in hours. You lose the very telemetry you are running shadow mode to collect.

### 3.16 [MEDIUM] `CandleAggregator.afterHoursBuffer` and other buffers have implicit assumptions
- `afterHoursBuffer` capped at 250. Fine for normal days. After a corporate announcement or Budget speech, after-hours tick burst can overflow silently.

---

## 4. LOW — hygiene

- 4.1 `KillSwitchEngine.writeKillSwitch` writes via `Files.write(...)` without fsync — crash between write and flush silently loses the flag.
- 4.2 `Executors.newScheduledThreadPool(2)` in `AngelTickStreamClient` is unnamed — useless thread dumps under load.
- 4.3 Angel credentials (`apiKey`, `clientCode`, `pin`, `totpSecret`) are `@Value` strings with empty-string defaults; `log.warn("Angel auth rejected: {}", response.getBody())` will dump full Angel responses including `clientcode` on auth failure.
- 4.4 MAC-address fallback `"00:00:00:00:00:00"` — Angel flags zero-MAC requests as suspicious.
- 4.5 No CORS / CSRF hardening on the public `/health`, `/monitoring/*` endpoints; `KillSwitchEngine.clearKillSwitch` should never be exposed even read-only.
- 4.6 Schema appears to be JPA `ddl-auto`; no Flyway/Liquibase migrations visible — silent column drift across releases.
- 4.7 No HikariCP pool tuning visible — default size 10 will starve under contention with the synchronous tick-path writes.
- 4.8 `TrapEngine.calculateQuantity` writes `signal.setQuantity(...)` from `TRAP_RISK_CAPITAL` env var, but `ShadowExecutionEngine.openTrade` immediately overrides via `PositionSizer`. The signal log shows one number, the trade log another — guaranteed audit-trail confusion.

---

## 5. STRATEGY-MATH SPECIFIC SHADOW HAZARDS (worth its own section)

If the operator's intent is to evaluate this strategy from shadow data, these are the things that will mislead you most:

1. **Index-point P&L vs real options P&L (1.7, 2.4)** — the displayed ₹ figure has no real-money meaning. R-multiples derived from it are correspondingly meaningless. The fastest fix is to declare the strategy as NIFTY-futures-only and rename `pnlInr` to use a single, correct futures formula. Then the shadow numbers are at least a meaningful proxy for futures execution.

2. **Slippage is a function of falsified latency (1.1)** — because `validateFreshTick` rewrites `receivedAt`, the simulator will systematically under-report latency and under-report slippage. Whatever expectancy the shadow run reports, the real-run expectancy will be lower by the slippage delta you have not measured.

3. **Runner-stage measurements are biased by 100%-at-TP1 at low lot counts (2.2)** — at 1–4 lots, the supposed "20% at TP1, runner the rest" is actually 25–100% at TP1. Runner-rate, runner-slippage, runner-expectancy in the shadow report measure a different strategy than the one specified.

4. **Regime gating is decorative (2.3)** — entries happen in regimes the documentation says are filtered out. The "regime filter is helping me" hypothesis cannot be tested from shadow data because the filter is not actually engaged.

5. **Daily-loss-R definition disagrees with the textbook (2.8)** — wins do not offset losses. Shadow telemetry on this metric does not mean what an external reader will assume it means.

6. **In-memory state windows reset on every restart (2.10, 2.11)** — `RealTimeEdgeTracker`, `AdaptiveRegimeEngine` rolling stats are wiped. A 30-day shadow run on Render with even one redeploy is actually multiple shorter runs concatenated, not a continuous window.

7. **Holiday & weekend pollution (1.6)** — shadow trades may be recorded on closed days from any non-Angel feed source.

---

## 6. WHAT WAS GOOD

- Strict typed configuration in `RiskPilotProperties`.
- `ExecutionSimulator` cleanly separated from `ShadowExecutionEngine`.
- Use of immutable records for `MarketTick`, `ActiveTradeExecution`, `EquitySnapshot`, `RegimeMetrics`.
- Daily-reset cron is wired.
- The recent commit fixing NIFTY weekly expiry to TUESDAY is correct per SEBI single-weekly framework.

That is the entire list.

---

## 7. FINAL VERDICT (Shadow-Mode Edition)

### **Are the shadow numbers trustworthy as a basis for any decision? — NO.**

### **Confidence: 95%.**

(Confidence is one notch lower than the live-trading verdict because some items, e.g. unbounded `rejectReasonCounts`, only bite long-running deployments — a one-day shadow read may be partially salvageable.)

### Top 5 disqualifying findings, shadow-only

1. **`validateFreshTick` overwrites `receivedAt` (1.1)** — biases all latency / slippage / staleness telemetry toward optimism.
2. **`pnlInr` uses index-point math for what the operator presumably intends as an options book (1.7)** — every ₹ figure and every R-multiple is wrong.
3. **`CandleAggregator` silently corrupts closed candles, has a midnight rollover bug, and clears the `feedUnstable` flag on the next tick (1.2 / 1.3 / 1.4)** — the OHLC the strategy reads is unreliable.
4. **AB/BA deadlock between `tradeStateLock` and `CandleAggregator` (1.5)** — entire shadow days will be lost silently, and you won't know which days.
5. **No transaction boundaries + `restoreActiveTrade` recomputes initial risk wrong (1.8 / 1.9)** — every restart of an in-flight trade silently produces wrong post-restore R-multiples.

### Minimum work to make the shadow numbers worth reading

In priority order:

1. Fix `validateFreshTick` to preserve the original `receivedAt`. (One-line fix. Single biggest win.)
2. Fix `pnlInr`: either restrict the strategy to NIFTY futures (rename, use the correct futures formula) or add a delta-aware option-leg model.
3. Fix `CandleAggregator`:
   - Reject ticks whose `candleAlignedTime` is before the current candle (or apply only if the prior candle was not yet finalised).
   - Compare on `LocalDateTime`, not `LocalTime` — kill the rollover bug.
   - Use `feedUnstable |= …` and clear it only on a successful continuous window of N stable ticks.
4. Move all DB writes off the WebSocket / `tradeStateLock` path onto a bounded queue with a single-consumer worker. Wrap each `closeTrade` / `persistOpenedTrade` sequence in a single `@Transactional`. Add `@Version` to `Trade`.
5. Persist `initialRiskPoints` on every TP1 / SL move so restore reads it directly.
6. Add an NSE-holiday calendar check to `MarketSessionService`. Record `tradingDay` annotation on every persisted trade row.
7. Bound `rejectReasonCounts` cardinality (canonicalise reason codes — e.g. `LIVE_TICK_STALE` without the inline numbers; put the numbers in a separate gauge).
8. Persist `RealTimeEdgeTracker` / `AdaptiveRegimeEngine` rolling state to the DB, rehydrate on startup.
9. Wire the regime score / `RegimeConfidenceEngine` into `RiskGateEngine` if it is supposed to gate entries; otherwise delete it.
10. Replace per-tick INFO logging with a sampled / structured logger; ship metrics to Micrometer + Prometheus instead.
11. Add a deterministic tick-replay harness (read a JSON-lines tick log, feed it through the pipeline as if live). This is the single biggest force-multiplier for evaluating the strategy from shadow data — without it you can only learn one trading day per real day.

After steps 1–6 are done, **shadow-mode telemetry will be worth reading**. Until then, treat current numbers as directional intuition only.

---

## 8. ITEMS NOW OUT OF SCOPE (live-trading concerns no longer counted)

For completeness, the following findings from the prior live-trading audit are **not** counted against the shadow-only verdict, because the operator has explicitly chosen not to place real orders. They remain true; they would re-activate if any future change introduces a real broker order layer.

- No broker order placement / `placeOrder` / `modifyOrder` / `cancelOrder` calls.
- No freeze-quantity, LPP/UPP, circuit-limit guards.
- No partial-fill handling, no average-price recomputation, no leftover-quantity tracking.
- No cancel-on-disconnect policy, no broker reconciliation job.
- No FIX-equivalent order state machine.
- No bid/ask spread observation (`simulation.spreadMidpoint()` is a configured constant; in shadow this is a *modelling* assumption you can choose to live with or refine).

— end of audit (shadow-mode edition) —
