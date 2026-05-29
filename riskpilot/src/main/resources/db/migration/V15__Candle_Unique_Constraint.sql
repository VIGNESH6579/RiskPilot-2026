-- V15: Add UNIQUE constraint on candles(symbol, timestamp, timeframe)
--
-- PROBLEM (BUG-4):
--   No DB-level uniqueness guard on (symbol, timestamp, timeframe).
--   Application-level dedup in persistClosedCandle() uses a SELECT-then-INSERT
--   pattern with a TOCTOU race: two ticks arriving at the exact slot boundary
--   can both pass the SELECT check simultaneously and insert duplicate rows.
--   Duplicate candles cause:
--     - restoreSessionCandles() to re-ingest the same candle twice on restart,
--       doubling its weight in RegimeFilter and VolatilityNormalizer
--     - getValidHistory() returning duplicate timestamps
--     - Double-counted OR range calculations
--
-- FIX:
--   1. Remove existing duplicate rows (keep the most recent by id).
--   2. Add a UNIQUE INDEX on (symbol, timestamp, timeframe) so the DB rejects
--      duplicate inserts atomically.  The application-level dedup remains as a
--      fast-path optimisation (avoid the write round-trip) but the DB is now the
--      authoritative guard.
--
-- Note: timeframe can be NULL in legacy rows (before V1 schema set it NOT NULL).
--   The unique index must handle NULLs — in PostgreSQL, two NULLs are NOT equal
--   in a unique index, so we coalesce NULL → 0 in the index expression.

-- Step 1: Delete duplicate candles, keeping the row with the lowest id per slot.
DELETE FROM candles
WHERE id NOT IN (
    SELECT MIN(id)
    FROM candles
    GROUP BY symbol, timestamp, COALESCE(timeframe, 0)
);

-- Step 2: Add the unique index (CREATE UNIQUE INDEX is transactional in Postgres).
--   IF EXISTS guard makes re-runs idempotent.
DROP INDEX IF EXISTS uq_candles_symbol_ts_tf;
CREATE UNIQUE INDEX uq_candles_symbol_ts_tf
    ON candles (symbol, timestamp, COALESCE(timeframe, 0));
