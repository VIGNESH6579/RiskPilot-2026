CREATE TABLE IF NOT EXISTS trading_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_date DATE NOT NULL UNIQUE,
    symbol VARCHAR(20) NOT NULL,
    daily_open NUMERIC(10, 2) NOT NULL,
    or_high NUMERIC(10, 2) NOT NULL,
    or_low NUMERIC(10, 2) NOT NULL,
    or_expansion NUMERIC(10, 2) NOT NULL,
    fast_candle_exists BOOLEAN NOT NULL DEFAULT FALSE,
    regime VARCHAR(20) NOT NULL DEFAULT 'DEAD',
    regime_locked BOOLEAN NOT NULL DEFAULT FALSE,
    trades_generated INTEGER NOT NULL DEFAULT 0,
    trades_executed INTEGER NOT NULL DEFAULT 0,
    trades_rejected INTEGER NOT NULL DEFAULT 0,
    total_pnl NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    max_drawdown NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    max_profit NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    session_active BOOLEAN NOT NULL DEFAULT TRUE,
    day_blocked_by_first_trade_failure BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    notes VARCHAR(500),
    session_start TIMESTAMP NOT NULL,
    session_end TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sessions_symbol_date ON trading_sessions (symbol, session_date);
CREATE INDEX IF NOT EXISTS idx_sessions_active ON trading_sessions (session_active);
CREATE INDEX IF NOT EXISTS idx_sessions_date ON trading_sessions (session_date);

CREATE TABLE IF NOT EXISTS trading_signals (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    expected_entry NUMERIC(10, 2) NOT NULL,
    stop_loss NUMERIC(10, 2) NOT NULL,
    target_price NUMERIC(10, 2) NOT NULL,
    confidence INTEGER NOT NULL DEFAULT 50,
    risk_amount NUMERIC(10, 2) NOT NULL,
    reward_amount NUMERIC(10, 2) NOT NULL,
    risk_reward_ratio NUMERIC(5, 2) NOT NULL,
    regime VARCHAR(20) NOT NULL,
    time_phase VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATED',
    rejection_reason VARCHAR(100),
    signal_time TIMESTAMP NOT NULL,
    execution_time TIMESTAMP NULL,
    execution_latency_seconds NUMERIC(8, 3) NULL,
    actual_entry NUMERIC(10, 2) NULL,
    entry_slippage NUMERIC(5, 2) NULL,
    strategy VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_signals_symbol_time ON trading_signals (symbol, signal_time);
CREATE INDEX IF NOT EXISTS idx_signals_status ON trading_signals (status);
CREATE INDEX IF NOT EXISTS idx_signals_regime ON trading_signals (regime);
CREATE INDEX IF NOT EXISTS idx_signals_strategy ON trading_signals (strategy);

CREATE TABLE IF NOT EXISTS trades (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    entry_price NUMERIC(10, 2) NOT NULL,
    stop_loss NUMERIC(10, 2) NOT NULL,
    target_price NUMERIC(10, 2) NOT NULL,
    position_size NUMERIC(5, 2) NOT NULL,
    remaining_size NUMERIC(5, 2) NOT NULL,
    realized_pnl NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    unrealized_pnl NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    max_favorable_excursion NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    max_adverse_excursion NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    tp1_hit BOOLEAN NOT NULL DEFAULT FALSE,
    runner_active BOOLEAN NOT NULL DEFAULT FALSE,
    tail_half_locked BOOLEAN NOT NULL DEFAULT FALSE,
    trailing_stop_loss NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    exit_reason VARCHAR(50),
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_trades_symbol_status ON trades (symbol, status);
CREATE INDEX IF NOT EXISTS idx_trades_entry_time ON trades (entry_time);
CREATE INDEX IF NOT EXISTS idx_trades_exit_time ON trades (exit_time);

CREATE TABLE IF NOT EXISTS candles (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    trade_date DATE NOT NULL,
    candle_time TIMESTAMP NOT NULL,
    open_price NUMERIC(10, 2) NOT NULL,
    high_price NUMERIC(10, 2) NOT NULL,
    low_price NUMERIC(10, 2) NOT NULL,
    close_price NUMERIC(10, 2) NOT NULL,
    volume BIGINT NOT NULL,
    price_range NUMERIC(10, 2) NOT NULL,
    timeframe INTEGER NOT NULL,
    is_bullish BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_candles_symbol_time ON candles (symbol, candle_time);
CREATE INDEX IF NOT EXISTS idx_candles_date ON candles (trade_date);
CREATE INDEX IF NOT EXISTS idx_candles_timeframe ON candles (timeframe);

INSERT INTO trading_sessions (
    session_date,
    symbol,
    daily_open,
    or_high,
    or_low,
    or_expansion,
    fast_candle_exists,
    regime,
    session_start
)
SELECT
    CURRENT_DATE,
    'NIFTY',
    46000.00,
    46150.00,
    45950.00,
    200.00,
    TRUE,
    'TREND',
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM trading_sessions WHERE session_date = CURRENT_DATE AND symbol = 'NIFTY'
);

CREATE OR REPLACE VIEW active_trades_summary AS
SELECT
    t.id,
    t.symbol,
    t.direction,
    t.entry_price,
    t.stop_loss,
    t.target_price,
    t.realized_pnl + t.unrealized_pnl AS total_pnl,
    t.max_favorable_excursion,
    t.max_adverse_excursion,
    t.entry_time,
    FLOOR(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - t.entry_time)) / 60) AS duration_minutes
FROM trades t
WHERE t.status = 'ACTIVE';

CREATE OR REPLACE VIEW daily_performance AS
SELECT
    DATE(t.entry_time) AS trade_date,
    t.symbol,
    COUNT(*) AS total_trades,
    SUM(t.realized_pnl + t.unrealized_pnl) AS total_pnl,
    SUM(CASE WHEN (t.realized_pnl + t.unrealized_pnl) > 0 THEN 1 ELSE 0 END) AS winning_trades,
    SUM(CASE WHEN (t.realized_pnl + t.unrealized_pnl) <= 0 THEN 1 ELSE 0 END) AS losing_trades,
    AVG(t.realized_pnl + t.unrealized_pnl) AS avg_trade_pnl,
    MAX(t.max_favorable_excursion) AS best_mfe,
    MAX(t.max_adverse_excursion) AS worst_mae
FROM trades t
GROUP BY DATE(t.entry_time), t.symbol
ORDER BY trade_date DESC;
