-- V5: Runtime hardening — missing columns and indexes
-- Safe to run multiple times (all statements use IF NOT EXISTS / IF EXISTS)

-- Trade optimistic locking (required by @Version on Trade entity)
ALTER TABLE trades ADD COLUMN IF NOT EXISTS version
    BIGINT NOT NULL DEFAULT 0;

-- Expected entry price (written by persistOpenedTrade)
ALTER TABLE trades ADD COLUMN IF NOT EXISTS expected_entry_price
    NUMERIC(10,2);

-- Trade logs: exit timestamp and trading day for post-analysis filtering
ALTER TABLE trade_logs ADD COLUMN IF NOT EXISTS exit_time
    TIMESTAMP;

ALTER TABLE trade_logs ADD COLUMN IF NOT EXISTS trading_day
    DATE;

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_trades_symbol_entry
    ON trades (symbol, entry_time DESC);

CREATE INDEX IF NOT EXISTS idx_trade_logs_signal_time
    ON trade_logs (signal_time DESC);

CREATE INDEX IF NOT EXISTS idx_trade_logs_gate
    ON trade_logs (gate_decision, signal_time DESC);
