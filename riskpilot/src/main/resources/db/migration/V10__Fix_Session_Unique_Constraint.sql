-- V10: Fix unique constraint on trading_sessions so each (symbol, session_date)
-- pair is unique — not just session_date alone.
--
-- BEFORE: session_date DATE NOT NULL UNIQUE
--   → Only one symbol per day. A BANKNIFTY session on a day that already has
--     a NIFTY session causes a unique constraint violation and the insert fails,
--     crashing session creation for the second symbol entirely.
--
-- AFTER: UNIQUE (symbol, session_date)
--   → NIFTY and BANKNIFTY each get their own session row per day, as intended.
--
-- Safe to run multiple times (IF EXISTS / IF NOT EXISTS guards throughout).

DO $$
BEGIN
    -- Step 1: Drop the old single-column unique constraint (created by V1 as the column default).
    -- The constraint name Postgres auto-generates for column-level UNIQUE is
    -- <table>_<column>_key.  Drop it if it exists.
    IF EXISTS (
        SELECT 1
        FROM   pg_constraint
        WHERE  conname = 'trading_sessions_session_date_key'
          AND  conrelid = 'trading_sessions'::regclass
    ) THEN
        ALTER TABLE trading_sessions
            DROP CONSTRAINT trading_sessions_session_date_key;
        RAISE NOTICE 'Dropped old unique constraint trading_sessions_session_date_key';
    ELSE
        RAISE NOTICE 'Old unique constraint already absent — skipping drop';
    END IF;

    -- Step 2: Add the composite unique index (symbol, session_date).
    -- CREATE UNIQUE INDEX IF NOT EXISTS is idempotent.
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sessions_symbol_date
    ON trading_sessions (symbol, session_date);
