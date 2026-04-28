DROP INDEX IF EXISTS uk_sessions_date_symbol;
ALTER TABLE trading_sessions DROP CONSTRAINT IF EXISTS trading_sessions_session_date_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sessions_date_symbol ON trading_sessions (session_date, symbol);

ALTER TABLE trades ADD COLUMN IF NOT EXISTS exit_type VARCHAR(20) NOT NULL DEFAULT 'REAL';
ALTER TABLE trade_logs ADD COLUMN IF NOT EXISTS exit_type VARCHAR(20);
ALTER TABLE candles RENAME COLUMN volume TO tick_count;
