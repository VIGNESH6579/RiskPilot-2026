# RiskPilot-2026 — Brutal Senior-Architect / Quant-Systems Audit

**Scope:** entire repository as of commit on `main` (post `d32e5b3`).
**Mode under audit:** `SHADOW` (only mode actually implemented).
**Stated intent:** real-money NIFTY index-options trap-strategy execution via Angel One SmartStream LTP feed.
**Tone:** no praise, no hedging. Every finding below would block a Day-1 production go-live at any serious prop / fund desk.

Severity legend: **BLOCKER** (live-trading stop), **HIGH** (must fix before any capital), **MEDIUM** (will hurt P&L or operability), **LOW** (hygiene).

---

## 1. CRITICAL BUGS

### 1.1 [BLOCKER] There is no real broker order-execution layer at all
- `ShadowExecutionEngine` + `ExecutionSimulator` *simulate* fills (`SimulatedFill`, `fillEntry`, `fillExit`). No `placeOrder`, `modifyOrder`, `cancelOrder`, `orderBook`, `positionBook`, `tradeBook` calls to Angel One REST anywhere in `service/`.
- "Execution latency" is the inter-tick gap clamped into a configured envelope (`ExecutionSimulator.plan`, lines 60–61). It is **not** the real RTT to the broker.
- "Slippage" is a deterministic linear function of last candle range + clamped tick speed (`ExecutionSimulator.plan`, lines 70–81). It cannot model real partial fills, order-book sweeps, circuit-limit halts, or freeze-quantity rejections.
- `pnlInr` in shadow trades uses configured `pointValue` against the **NIFTY index price** (`ShadowExecutionEngine.pnlInr`, line 1107). Real trades are in F&O option premiums, where delta ≪ 1 and gamma/theta dominate. The P&L numbers this system produces have **no monetary meaning** for an options book.
- The README, configs, and validation chain claim "LIVE/SHADOW" parity, but `RiskPilotProperties.isRealFeedMode()` only gates the **feed**, not the order path. Switching `mode: LIVE` would change a label in logs and absolutely nothing else.

**Verdict on this single point alone: SHADOW-ONLY. The repo is incapable of live trading.**

### 1.2 [BLOCKER] Deadlock potential between `tradeStateLock` and `CandleAggregator`'s monitor
- Tick path: `AngelTickStreamClient.onBinary` → `ingestTick` → `CandleAggregator.processTick` (acquires `synchronized(this)`) → `publishEvent(CandleClosedEvent)` → `ShadowExecutionEngine.evaluateCandle` (acquires `tradeStateLock`).
  Lock order: **CandleAggregator.monitor → tradeStateLock**.
- Daily-reset / recovery path: `ShadowExecutionEngine.executeDailyHardReset` → `tradeStateLock.lock()` → `candleAggregator.clearHistory()` (acquires `synchronized(this)`).
  Lock order: **tradeStateLock → CandleAggregator.monitor**.
- Two threads on opposite paths → classic AB/BA deadlock. Once it triggers (e.g. cron fires at exactly 09:15 IST while a tick is being processed), the WS thread and the scheduler thread block forever, the WS read-loop stops, the heartbeat dies, but `markHalted` was already called on a different thread that *also* needed the lock — silent total stall during market open.

### 1.3 [BLOCKER] `StrictValidationService.validateFreshTick` overwrites `tick.receivedAt` with the validation-time `now`
- Lines 237–246: returned `acceptedTick` is built with `now` (the validation timestamp) as `receivedAt` instead of the real WS receive instant.
- All downstream latency / staleness measurements (`MarketDataStateService.lastTickAgeMs`, `silenceMs`, heartbeat staleness, `estimateTickGapMs` used by the simulator) now read a **falsified** receivedAt.
- Combined with `MarketTick.of(...)` recomputing `sourceAgeMs` from the new `receivedAt`, the system will systematically *under-report* age and *under-report* inter-arrival gap. Stale ticks closer to the threshold will sneak through.

### 1.4 [BLOCKER] `KillSwitchEngine` is not actually a kill-switch
- `KILL_FLAG_FILE = "KILL_SWITCH.flag"` is a **relative path** (line 17). It depends entirely on the JVM's CWD on Render — opaque to the operator. If the operator drops a file in the repo root, the running container can't see it.
- `isKillSwitchTriggered()` does a `Files.exists(...)` and `Files.readAllLines(...)` on every entry-gate evaluation (called from `RiskGateEngine.evaluateEntry`). That is a synchronous filesystem stat on the WS-driven hot path.
- `evaluateInternal(MetricsWindow)` exists but is **never called from anywhere** — `rg "evaluateInternal" .` in the repo confirms zero callers. The internal kill-switch logic is dead code.
- `writeKillSwitch` is also never called by Java. The comment refers to a "Python forward_scorecard" that does not exist in the repo.
- Net effect: there is no automatic kill-switch. The only way to stop the system mid-day is manual SIGTERM.

### 1.5 [BLOCKER] No NSE holiday calendar, no weekend check
- `MarketSessionService.isMarketOpen` only checks the time-of-day window `[09:15, 15:30)`.
- Saturdays, Sundays, Diwali, Republic Day, Budget special sessions, NSE muhurat session — all treated identically. The system will:
  - Show "MARKET OPEN" on `/health` on weekends and holidays.
  - Permit trade entry if any source ever delivers a non-stale tick during those windows (e.g. a replayed feed, a test harness, a brokerage feed bug).
  - Mis-classify the post-09:00 NSE pre-open auction (09:00–09:08) as "closed" (correct) but also fail to recognise the special trading sessions outside 09:15–15:30 used during muhurat / migration days.

### 1.6 [BLOCKER] `AngelTickStreamClient.parseTick` accepts ages > 12 hours during clock disagreement
- `normalizeExchangeFeedEpochMs` only rejects ages older than year 2020 or below `MIN_REASONABLE_EPOCH_MILLIS` (~2017-07-14). It does **not** reject "future" timestamps or wildly stale ones.
- Combined with bug 1.3 (rewriting `receivedAt` to `now`), a stale exchange timestamp from a paused feed → `ageMs` computed in `parseTick` is correct, but the *age stored on the accepted tick is wrong* (positions to `now - exchangeTs` recomputed by `MarketTick.of` using rewritten `receivedAt`). The strict-timing gate then runs against this falsified figure.

### 1.7 [BLOCKER] `CandleAggregator` silently mutates a closed candle when ticks arrive out-of-order
- `processTick` lines 112–134: if the incoming tick's `candleAlignedTime` is *before* the current candle's start time (out-of-order or replayed tick), the `else` branch fires and calls `applyTick` against the **current** building candle — corrupting OHLC of a candle that should already be closed or that is logically in the past.
- No sequence-id monotonicity check anywhere. `MarketTick.sequenceId` is read and logged but never used to reject out-of-sequence ticks.

### 1.8 [HIGH] `feedUnstable` flag in `CandleAggregator` is reset by every fast tick
- Line 87: `feedUnstable = (lastArrivalTime != null && arrivalGapMs > 4500L);` — assigns, doesn't OR.
- A previous `markUnstable()` call (e.g. from `AngelTickStreamClient` on parse failure) is **silently cleared** by the next tick that arrives within 4.5 s. Instability events vanish before any downstream consumer can react.

### 1.9 [HIGH] Date-rollover bug in `CandleAggregator.processTick`
- Comparison at line 113 uses `LocalTime.isAfter`, which has no concept of date. After a JVM that survives across midnight (test harness, dev run, or worst-case a server kept running over a weekend), the first Monday tick at 09:15 will compare *before* the persisted Friday 15:25 candle and update it instead of starting a new one.
- `clearHistory()` is only called from `ShadowExecutionEngine.restart()` and the daily-reset cron. If either fails or is delayed, the bug bites.

### 1.10 [HIGH] `ShadowExecutionEngine.closeTrade` re-validates exit slippage but swallows the result
- Lines 599–608: `strictValidationService.validateExitExecution(...)` is called inside `try { ... } catch (Exception e) { log.warn(...) }`. The whole "strict mode rejects on slippage breach" claim is silently neutered for exits — every exit goes through regardless of slippage.
- Same pattern in `persistOpenedTrade`, `syncPersistedActiveTrade`, `finalizePersistedTrade`, `cancelPersistedActiveTrade`, `restoreActiveTrade`: catch `Exception`, `log.warn`, continue. **No DB transaction boundaries.** Partial writes during a Postgres blip leave the trade table in an inconsistent state with no recovery.

### 1.11 [HIGH] `rejectReasonCounts` grows unbounded
- `ShadowExecutionEngine.logReject` does `rejectReasonCounts.computeIfAbsent(reason, ...)` with no key-set ceiling. Any code path that synthesises reasons from variable strings (e.g. `String.format("LIVE_TICK_STALE: age=%dms max=%dms", ...)`) — and `StrictValidationService.validateFreshTick` does exactly this on lines 208–212 — produces an unbounded number of distinct keys. Long-lived process → memory leak.

### 1.12 [HIGH] Activity flags `activeTradeId` and `lastTriggeredCandleTime` are not `volatile`
- Read on the WS-thread tick path *outside* `tradeStateLock` (in broadcast methods) and written on the cron / WS path. Without `volatile` or final synchronisation, read threads may observe stale values indefinitely on a long-running JVM.

### 1.13 [HIGH] `restoreActiveTrade` reconstructs `initialRiskPoints` as `|entryPrice − stopLoss|`, losing the *original* risk
- Line 1040: `Math.abs(trade.getEntryPrice() − trade.getStopLoss())`. After TP1 the persisted `stopLoss` was moved to breakeven (= entryPrice), so reconstructed `initialRiskPoints` becomes 0 → divide-by-zero is "saved" by `Math.max(1.0, ...)` later, but **R-multiples post-restore are mathematically wrong**, the daily-loss-R limit is wrong, and the size-reduction logic is wrong.

### 1.14 [HIGH] `pnlInr(trade, price, lots)` uses `unitsForLots(lots)` while `quantity` is in lots already
- `ShadowExecutionEngine.pnlInr` is fine, but `ActiveTradeExecution.fromTickTP1` line 35–37 takes `tp1Lots = max(1, round(quantity * 0.20))` where `quantity` is the **lot count**. For `quantity = 1` (most common at small accounts), TP1 takes the **entire position** at TP1 and leaves zero runner — silently disabling the runner stage that the rest of the engine assumes will exist.

### 1.15 [MEDIUM] `TrapEngine.calculateQuantity` is dead code that lies in the `Signal`
- Sets `signal.setQuantity(...)` based on `TRAP_RISK_CAPITAL` env var, but `ShadowExecutionEngine.openTrade` immediately overrides via `positionSizer.sizePositionLots(...)`. Operators reading the signal log will see one number, the broker view another — guaranteed audit-trail confusion.

### 1.16 [MEDIUM] `TrapEngine` uses hard-coded magic numbers (6.0, 10.0, 120.0 pts) and hard-coded `"NIFTY"` symbol
- No bands per regime, no scaling by ATR, no instrument abstraction. The thing that *generates the alpha* is the least configurable piece in the codebase.

---

## 2. HIGH-RISK DESIGN FLAWS

### 2.1 The whole "trade decision pipeline" runs on the WebSocket I/O thread
- `AngelWebSocketListener.onBinary` → `ingestTick` → `validateFreshTick` → `recordAcceptedTick` → `processTick` → `publishEvent(CandleClosedEvent)` synchronously calls `ShadowExecutionEngine.evaluateCandle` → which acquires `tradeStateLock`, runs the `TrapEngine`, the `RegimeFilter`, the `RegimeConfidenceEngine`, the `PositionSizer`, the `RiskGateEngine`, the `ExecutionSimulator`, and persists to Postgres — then returns to the WS thread which then calls `evaluateTick` on the same engine, which acquires `tradeStateLock` *again*.
- **Any DB stall, any Spring AOP advice, any GC pause stalls the entire feed.** No back-pressure, no bounded queue, no event-loop separation.

### 2.2 Single broadcast executor (`broadcastExecutor` = single-thread)
- All UI websocket fan-out happens on one thread. Slow client → queue grows → `OutOfMemoryError`. The `LinkedBlockingQueue` is unbounded.

### 2.3 No persistence transaction boundaries
- Every `tradeRepository.save(...)` runs in its own implicit transaction. A trade that opens, hits TP1, then crashes mid-runner leaves the DB with `tp1Hit=true, runnerActive=true, status=ACTIVE, remainingQuantity=N` but **no in-memory engine state**. `restoreActiveTrade` will rehydrate and immediately compute a wrong `initialRiskPoints` (see 1.13). No `@Transactional` anywhere in the trading flow.

### 2.4 No idempotency on Angel re-subscription
- `subscribeNifty` is fire-and-forget. If the WS reconnects mid-second and the prior subscription is still alive on the broker side, you can receive duplicate ticks; sequence-id is not used to deduplicate (see 1.7).

### 2.5 `AngelAuthService` fetches the public IP via `https://api.ipify.org`
- Production credential request **leaks the deployment topology to a third party** every authentication cycle (every pre-market 09:00 IST + every reconnect that needs auth).
- Also: blocks the auth path on a third-party HTTP call. If `ipify` is rate-limited or down, auth stalls.
- And: changes per call (ipify cache vs reality), so Angel's "must-match-IP" enforcement (which they do enforce on REST) becomes a flaky gate.

### 2.6 `AngelAuthService.preMarketAuth` is `@Scheduled(cron="0 0 9 * * MON-FRI")`
- Misses Saturday/Sunday correctly, but **runs on every NSE holiday Mon–Fri**, generating a useless auth attempt and burning the daily TOTP window. On settlement-bank-holidays where Angel rotates tokens server-side, this can also lock the account temporarily.

### 2.7 The `TradingSessionSnapshot` mutation pattern is racy
- `SessionStateManager.update(unaryOperator)` (presumed CAS — not read here, but the call pattern shows compose-on-current-snapshot) is correct **only if** every consumer always uses the snapshot atomically. Several call sites read individual fields then write a partial new snapshot built from `current.X`, `current.Y` — meaning two concurrent updates can clobber each other (e.g. `closeTrade` setting `tradeActive=false` while `updateActiveTradeState` from the next tick sets `tradeActive=true` based on its older view).

### 2.8 `RegimeFilter` and `RegimeConfidenceEngine` are two parallel, partially-overlapping regime systems
- Both maintain their own opening-range, breakout, ATR, efficiency state. They use *different* thresholds (`RegimeFilter.MIN_REGIME_SCORE = 4` vs `RegimeConfidenceEngine` `< 55 → block, < 70 → reduced`). Either both must agree (no such gating exists) or one is dead weight. Currently the engine reads both and uses the second only for a UI badge — meaning the *real* gating decision is the first one.

### 2.9 `KillSwitchEngine.evaluateInternal` has no caller and no scheduler
- The "internal kill switch" claim is fictional.

### 2.10 No reconciliation against the broker
- Even if a real order layer existed, there is no nightly position-reconciliation job, no order-book sweep on startup, no detection of orphan broker positions vs DB state.

---

## 3. LOGIC ERRORS IN STRATEGY

### 3.1 5-minute candles for a sub-2R intraday trap is too coarse
- `CandleAggregator` produces 5-minute candles (`(minute / 5) * 5`). `TrapEngine` requires 7 candles → **35 minutes of warmup** before the first valid signal. NIFTY morning move usually completes in the first 30 minutes; the strategy structurally cannot trade the highest-edge window of the day.

### 3.2 `TrapEngine` uses the "current closed candle's close" as both detection and entry price
- `signal.setEntry(t0.close)`. By the time the candle closes and the event fires, the price has already moved. The simulated fill then assumes you can transact at `tickPrice ± halfSpread ± impact` — but in reality the *signal price* and *realisable price* on a market order one tick later can differ by 2–5 points routinely on NIFTY index futures, and 10–20 paise on options after multiplying by delta. Real slippage is going to be much larger than what `ExecutionSimulator.slippageMaxPoints` allows.

### 3.3 Hard-coded constants in `TrapEngine` masquerading as a strategy
- 6.0 pt minimum breakout depth, 10.0 pt SL buffer, 120.0 pt max stop distance, 7-candle window, 5-candle range mean. Zero parameter is regime-aware, ATR-normalised, or risk-budget-derived.
- Stop distance is point-based on the **index**, but position sizing applies it to **option lots** via `lotSize × pointValue`. Mathematically this is a futures-style sizing model misapplied to options. The actual option premium move per index point is `delta`, not 1, so realised loss per "stop-loss point" will be ~`delta × 1` ≪ planned. **Risk-per-trade percentage is wrong by a factor of 1/delta.**

### 3.4 Trailing stop in `ActiveTradeExecution.fromCandleClose`
- `atr = candle.high − candle.low` (just the last candle's range — NOT an ATR). Buffer `max(10, atr × 0.4)`. For a wide volatile candle this gives a permissive trail; for a narrow candle it locks in 10 pts above the last bar high. Not a coherent trailing model.

### 3.5 TP1 fraction = 20% of `quantity` (lots) and rounded
- `Math.round(quantity * 0.20)` for `quantity ∈ {1,2,3,4}` produces `{1,1,1,1}` lots — i.e. 100%, 50%, 33%, 25%. The strategy claim of "scale out 20% at TP1, runner the rest" is not what the code does at small lot counts (which is the only realistic scale for the stated `riskPerTradePct`).

### 3.6 `RegimeConfidenceEngine` thresholds are unbacktested magic numbers
- 25/20/15/15/15/10 weight allocation, 55/70 cutoffs. No reference to backtest data, walk-forward results, or out-of-sample performance. Numerology, not quant.

### 3.7 `RegimeFilter` "trading allowed" is read but **not used as a gate**
- `RiskGateEngine.evaluateEntry` only checks `state.regime() != Regime.TREND`. The regime score / `tradingAllowed` flag is **never** consulted in the gate. The whole regime-confidence pipeline is decorative.

### 3.8 Daily-loss-R uses `state.cumulativeDailyLossR()` but the increment is `Math.min(0.0, realizedR)`
- `closeTrade` line 686: only adds losses, never offsets with wins. So the "daily loss limit in R" is actually "cumulative gross loss only" — a winning trade does not relax the limit. That's possibly intentional, but it is *not* what most desks call "daily loss limit" and it is undocumented.

### 3.9 `RiskEngine.dailyLossLimitBreached` uses `realizedPnl` only (not unrealized)
- A trade in a deep drawdown that hasn't hit SL is invisible to the gate. The gate only catches realised losses. A black-swan gap-down on the next candle that exceeds the daily-loss limit by 3× will be noticed only after exit.

### 3.10 `PositionSizer.sizePositionLots` uses `equityInr × riskPerTradePct`
- `equityInr` includes `unrealizedPnl` (`RiskEngine.refresh` adds unrealized). So position size *grows* with paper profits and *shrinks* on paper losses on the **same** open trade, which is meaningless because there is only one open trade at a time. For the next trade after a winner: equity has grown → next risk is on a higher base → compounding; after a loser: smaller. Not wrong per se, but `consecutiveLosses`-based reduction stacks on top of this and was never documented as compounding.

---

## 4. EXECUTION / BROKER RISKS

### 4.1 No order placement, no order-state machine, no reject handling
See 1.1. Cannot be repeated enough: this is an analytics + simulation engine. It is not connected to any broker for order entry.

### 4.2 No freeze-quantity check
NSE NIFTY F&O has a freeze quantity per order. `PositionSizer` will happily compute lots that exceed the freeze-quantity → broker will reject the order in real-mode. Nothing in the code guards against this.

### 4.3 No circuit-breaker / LPP / UPP awareness
NIFTY index has 10/15/20% circuit limits, options have LPP/UPP price bands. No code reads or respects these — slippage logic assumes infinite liquidity inside `slippageMaxPoints` (default value not inspected here, but configuration-bound).

### 4.4 No bid/ask spread observation
Angel SmartStream LTP packets carry only the last traded price. The system blindly trades a strategy that depends on tight spreads (trap reversals are spread-sensitive). The `simulation.spreadMidpoint()` is a configured constant, not a measurement.

### 4.5 `closeTrade` is only triggered from tick + candle events
- If the WS dies and ticks stop, the trade can't be closed even though the configured force-exit time has passed. There is no clock-driven exit watchdog independent of feed health.

### 4.6 Force-exit time check is per-tick
- `MarketSessionService.shouldForceExit` is only invoked from inside the tick path. If the feed disconnects 1 minute before the configured force-exit and reconnects 1 minute after market close, the position never gets the force-exit signal until the next session.

### 4.7 No partial-fill handling
- `SimulatedFill` returns one `actualPrice`. Real Angel orders can fill in 1, 5, 25, or N partial lots over hundreds of ms. There is no `OrderEvent` queue, no average-price recomputation, no leftover-quantity tracking.

### 4.8 No order cancel on disconnect
- If a hypothetical real order is in the broker's order book and the WS dies, Angel's behaviour is to **leave the order live**. There is no `cancelOrdersOnDisconnect` policy here, no equivalent of FIX `RestatementReason=Disconnect`.

---

## 5. PERFORMANCE / SCALABILITY

### 5.1 Synchronous DB writes on the tick path
- `syncPersistedActiveTrade` and `finalizePersistedTrade` and `cancelPersistedActiveTrade` and `persistOpenedTrade` all run blocking JPA `tradeRepository.save(...)` while holding `tradeStateLock` on the WS thread. P99 of any Postgres write — even on a warm pool — exceeds the configured `maxSilenceMs` heartbeat threshold under load. **One slow `save` = heartbeat panic.**

### 5.2 `restoreSessionCandles` reads `findTop50` synchronously in `@PostConstruct`
- Container can't pass startup probe if Postgres is slow. Render's failure mode then is to hard-restart the container, which on next attempt also can't start. Cascading failure.

### 5.3 `KillSwitchEngine.isKillSwitchTriggered` does `Files.exists` + `Files.readAllLines` on the entry path
- Disk I/O on every signal evaluation. On Render's networked filesystem this will dominate latency.

### 5.4 `AngelAuthService.resolvePublicIp` makes an outbound HTTP call inside the auth method
- Blocks auth. Pre-market 09:00 IST sees this called; if `ipify` is slow, the WS connect chain stalls.

### 5.5 Unbounded growth: `rejectReasonCounts`, `LinkedBlockingQueue` for broadcast, `afterHoursBuffer` (capped at 250 — fine), `breakoutHistory` (capped at 10 — fine), `candleHistory` in `RegimeFilter` (capped at 20 — fine)
- The first two are unbounded.

### 5.6 GC pressure
- Every tick allocates a `MarketTick` (record), every `stateManager.update` allocates a `TradingSessionSnapshot`, every candle close allocates a `Candle`, every gate decision allocates a `GateDecision`. NIFTY ticks at 5–50 Hz steady-state, peaks at 200+ Hz around expiry. At 200 Hz × ~6 record allocations per tick = ~1.2k objects/sec just in the hot path. On a JDK 21 G1GC default config this is tolerable, but with the synchronous DB write in the same path you will see GC stalls coincident with Postgres P99 spikes.

---

## 6. SECURITY / CONFIG

### 6.1 `SESSION_SECRET` is in environment but no audit shows JWT/cookie signing
- Without reading `SecurityConfig.java` and `PlainWebSocketConfig.java` here, the `/health`, `/monitoring/*`, and WS endpoints presumably accept unauthenticated traffic on Render. **Anyone with the public URL can read the active trade, P&L, and reject reasons.** A control endpoint to clear the kill-switch (`KillSwitchEngine.clearKillSwitch`) exposed via `EngineController` would be game-over.

### 6.2 Angel credentials read via `@Value` with empty-string default
- `apiKey`, `clientCode`, `pin`, `totpSecret`. All printable in heap dumps, not redacted in logs (the `log.warn("Angel auth rejected: {}", response.getBody())` *will* dump the full Angel response which on auth failure contains `clientcode`).

### 6.3 TOTP generated with `System.currentTimeMillis()` only
- No NTP-skew tolerance, no two-bucket retry. Container clock drift > 30 s (common on first boot of a sleeping Render free dyno) → permanent auth failure until restart.

### 6.4 MAC-address fallback `00:00:00:00:00:00`
- Angel actively flags zero-MAC requests as suspicious and may rate-limit / block the account.

### 6.5 RestTemplate for Angel auth without timeout config
- Default `RestTemplate` has no connect/read timeouts. A slow Angel API stalls the auth thread indefinitely, blocking the WS reconnect.

### 6.6 No CORS / no CSRF discussion
- (Not inspected in detail; flag as needs-review.)

### 6.7 Database credentials assumed to come from env, no rotation policy, no migration tooling visible
- Schema is created by JPA `ddl-auto` on startup (presumed). On schema drift between releases, silent data loss is possible.

---

## 7. CONCURRENCY / THREADING

### 7.1 See 1.2 (deadlock) and 2.7 (snapshot races).

### 7.2 `volatile` missing on `webSocket`, `currentJwtToken`, `currentFeedToken`, `lastAuthAttemptEpochMs`, `activeTradeId`, `activeSignalTime`, `activeExecutionTime`, `activeExpectedEntry`, `activeEntryLatencyMs`, `pendingExit`, `lastTriggeredCandleTime`, `lastRegimeConfidenceScore`
- Some are written under `tradeStateLock` and read outside of it (broadcast path), violating happens-before.

### 7.3 `ExecutorService` resources never bounded or named
- `ScheduledExecutorService executor = Executors.newScheduledThreadPool(2)` in `AngelTickStreamClient`. Threads are unnamed → useless in thread dumps under load.
- `broadcastExecutor` is presumably a single-thread executor (called from `ShadowExecutionEngine`). Unbounded queue.

### 7.4 `HeartbeatMonitor.monitorHealth` runs every 2 s and unconditionally writes the snapshot
- Causes a state broadcast every 2 s even when nothing changed. Not a bug, but pointless network chatter.

---

## 8. DATA INTEGRITY / PERSISTENCE

### 8.1 No `@Transactional` boundaries on multi-step trade lifecycle updates
- `closeTrade` does in this exact order:
  1. `liveMetricsLogger.logShadowExecution(...)` (DB insert)
  2. `broadcastTradeData(...)` (WS fan-out)
  3. `edgeTracker.addTradeResult(...)` (in-memory)
  4. `adaptiveRegimeEngine.addTradeResult(...)` (in-memory)
  5. `strictValidationService.recordTradeExecution(...)` (in-memory)
  6. `riskEngine.recordClosedTrade(...)` (in-memory + DB read in refresh)
  7. `ntfyNotificationService.notifyTradeExit(...)` (third-party HTTP)
  8. `finalizePersistedTrade(...)` (DB update)
  9. `stateManager.update(...)` (in-memory)
- A crash between steps 1 and 8 logs an "executed trade" with exit telemetry, but the `Trade` entity is still `status=ACTIVE`. On restart, `restoreActiveTrade` rehydrates a phantom open position that already closed.

### 8.2 `cancelPersistedActiveTrade("STALE_RECOVERY")` is called from inside `persistOpenedTrade`
- Race: if a stale active trade exists in DB at the moment a new trade is being persisted, the system cancels the prior trade with reason `"STALE_RECOVERY"` and creates a new one. There is **no audit trail** of why the prior trade was cancelled (e.g. crash vs duplicate signal vs operator action).

### 8.3 No optimistic locking (`@Version`) on `Trade`
- Two threads running `tradeRepository.save(entity)` against the same row will perform last-write-wins. Given that `syncPersistedActiveTrade` is called from the WS path and `finalizePersistedTrade` from the same path on different ticks, plus restore on startup, this is reachable.

### 8.4 `CandleRecord` persistence on `finalizeCandle`
- Fires under `synchronized(this)` on the tick path. See 5.1.

### 8.5 No DB pool config inspected
- Without HikariCP tuning visible, the default pool size is 10. Under any contention, the tick path can starve.

---

## 9. OBSERVABILITY / OPERABILITY

### 9.1 Logging is verbose but not structured
- `log.info("Angel WS tick token={} seq={} price={} ...")` logs **every accepted tick** including the full hex packet preview (128 bytes). At 50 Hz × 24 fields × ~200 bytes each = ~10 KB/s of log output baseline, ~40 KB/s during bursts. Render's log retention will be exhausted within hours.

### 9.2 No metrics endpoint (Prometheus / Micrometer not configured here)
- `/health` returns a manually composed Map. No histograms for tick-to-decision latency, no counters per reject reason, no gauges for queue depth. **Operationally blind in a real outage.**

### 9.3 Alerting via `NtfyNotificationService` (presumed third-party HTTP)
- Single point of failure; if `ntfy.sh` is down, no alerts. No fallback.

### 9.4 No correlation-IDs on log lines
- Tracing a single trade through 6 log statements requires `grep` by trade ID — except trade ID is only printed in some lines.

### 9.5 The "audit trail" for a rejected signal is one log line
- Reject reasons like `"NON_TREND"` give no context: which regime, which OR range, which orderbook state. Post-mortem on a missed move is impossible.

### 9.6 `KillSwitchEngine` writes via `Files.write(KILL_PATH, ...)` with no fsync
- Crash between write call and flush → kill flag silently lost.

---

## 10. TESTING / VERIFICATION GAPS

### 10.1 Per the repo layout there is `riskpilot/src/test/...` but the strategy decision tree has no observable property-based test
- (Not reading test files in this audit pass; flagging that even if present, the things that need testing — deadlock under cron, partial DB write recovery, out-of-order tick handling, freeze-quantity guard, holiday-calendar — are exactly the things this code wouldn't pass.)

### 10.2 No replay harness
- A real quant system has a deterministic replay-from-pcap or replay-from-tick-log mode. This system can be exercised only by talking to live Angel during market hours. Catastrophically slow feedback loop for any strategy iteration.

### 10.3 No paper-trading reconciliation
- Even though the system is "shadow", there is no automated daily reconciliation that compares the simulated fills against real LTP-walk-forward — i.e. the shadow numbers cannot be validated against ground truth.

### 10.4 No staging / canary deployment workflow visible
- `render.yaml` (single service) suggests prod-only deploys.

---

## 11. WHAT WAS GOOD (one short paragraph, for honesty)

- Strict typed configuration in `RiskPilotProperties`.
- Clean separation of `ExecutionSimulator` from `ShadowExecutionEngine`.
- Use of immutable records for `MarketTick`, `ActiveTradeExecution`, `EquitySnapshot`, `RegimeMetrics`.
- Daily-reset cron is wired (even if the deadlock risk is real).
- The recent commit fixing NIFTY expiry to TUESDAY is correct per SEBI single-weekly framework.

That is the entire list.

---

## 12. FINAL VERDICT

### **SAFE FOR LIVE TRADING: NO.**

### **Confidence: 99%.**

### Top 5 reasons (any one of which is independently disqualifying):

1. **There is no broker order layer.** The system simulates fills against a configured slippage model and a constant spread. Switching `mode: LIVE` does not place real orders. It cannot lose money in a market order book, and it cannot make money there either. (Finding 1.1)

2. **The kill-switch does not work.** Relative file path, no internal trigger wiring, file-system stat on the hot path. Operators have no reliable way to halt the system mid-trade. (Finding 1.4)

3. **No NSE holiday calendar, no weekend gate.** The `MarketSessionService` is a clock-only stub. Any non-Angel feed source (test harness, replay, broker feed bug) on a closed day → trade. (Finding 1.5)

4. **Deadlock & data-integrity hazards on the hot path.** The trade decision pipeline runs synchronously on the WebSocket I/O thread, takes two locks in inconsistent order across paths, performs blocking JPA writes inside the lock, and has no transaction boundaries around multi-step lifecycle updates. (Findings 1.2, 2.1, 2.3, 5.1, 8.1)

5. **The strategy mathematics are wrong for the instrument.** `TrapEngine` reasons in NIFTY *index* points, but real positions are in *option* lots. Risk-per-trade is off by a factor of `1/delta` (~3×–5× for typical near-money weekly options). The position-sizer therefore systematically over-risks. P&L printed in shadow mode is non-monetary. (Findings 1.1, 3.3)

### Minimum work required before *even paper-trading* with confidence:
- Build a real broker order layer (REST place/modify/cancel + order-book sweep + reject handling + freeze-quantity guard + LPP/UPP guard).
- Add a real holiday calendar + weekend gate + special-session whitelist.
- Move trade decisions off the WS thread onto a bounded queue with single-consumer worker.
- Wrap multi-step DB updates in `@Transactional`. Add `@Version` to `Trade`.
- Replace the kill-switch with an absolute-path file + an internal scheduler that *actually evaluates and writes it* + a `cancelAllOpenOrders + flatten` action when triggered.
- Fix `validateFreshTick` to preserve the original `receivedAt`.
- Fix `CandleAggregator` for date rollover, out-of-order ticks, and the sticky-feedUnstable bug.
- Fix `restoreActiveTrade` to persist `initialRiskPoints` and rehydrate it, not recompute it.
- Fix `pnlInr` to use real option premium math (delta-aware) — or restrict the strategy explicitly to index futures and stop pretending it trades options.
- Add structured metrics (Micrometer + Prometheus), rate-limit per-tick logging.
- Add `@Transactional` boundaries and a reconciliation job.
- Add a deterministic tick-replay harness; require any new strategy parameter change to be backtested through it.

Until **all** of the above are done, capital should not touch this system.

— end of audit —
