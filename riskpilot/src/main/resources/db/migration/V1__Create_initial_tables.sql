-- RiskPilot Database Schema - Version 1
-- Created for RiskPilot-2026 Algorithmic Trading System

-- Create trading_sessions table
CREATE TABLE trading_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_date DATE NOT NULL UNIQUE,
    symbol VARCHAR(20) NOT NULL,
    daily_open DECIMAL(10,2) NOT NULL,
    or_high DECIMAL(10,2) NOT NULL,
    or_low DECIMAL(10,2) NOT NULL,
    or_expansion DECIMAL(10,2) NOT NULL,
    fast_candle_exists BOOLEAN NOT NULL DEFAULT FALSE,
    regime VARCHAR(20) NOT NULL DEFAULT 'DEAD',
    regime_locked BOOLEAN NOT NULL DEFAULT FALSE,
    trades_generated INT NOT NULL DEFAULT 0,
    trades_executed INT NOT NULL DEFAULT 0,
    trades_rejected INT NOT NULL DEFAULT 0,
    total_pnl DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    max_drawdown DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    max_profit DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    session_active BOOLEAN NOT NULL DEFAULT TRUE,
    day_blocked_by_first_trade_failure BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    notes VARCHAR(500),
    session_start TIMESTAMP NOT NULL,
    session_end TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_sessions_symbol_date (symbol, session_date),
    INDEX idx_sessions_active (session_active),
    INDEX idx_sessions_date (session_date)
);

-- Create trading_signals table
CREATE TABLE trading_signals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    expected_entry DECIMAL(10,2) NOT NULL,
    stop_loss DECIMAL(10,2) NOT NULL,
    target_price DECIMAL(10,2) NOT NULL,
    confidence INT NOT NULL DEFAULT 50,
    risk_amount DECIMAL(10,2) NOT NULL,
    reward_amount DECIMAL(10,2) NOT NULL,
    risk_reward_ratio DECIMAL(5,2) NOT NULL,
    regime VARCHAR(20) NOT NULL,
    time_phase VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATED',
    rejection_reason VARCHAR(100),
    signal_time TIMESTAMP NOT NULL,
    execution_time TIMESTAMP NULL,
    execution_latency_seconds DECIMAL(8,3) NULL,
    actual_entry DECIMAL(10,2) NULL,
    entry_slippage DECIMAL(5,2) NULL,
    strategy VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_signals_symbol_time (symbol, signal_time),
    INDEX idx_signals_status (status),
    INDEX idx_signals_regime (regime),
    INDEX idx_signals_strategy (strategy)
);

-- Create trades table
CREATE TABLE trades (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    entry_price DECIMAL(10,2) NOT NULL,
    stop_loss DECIMAL(10,2) NOT NULL,
    target_price DECIMAL(10,2) NOT NULL,
    position_size DECIMAL(5,2) NOT NULL,
    remaining_size DECIMAL(5,2) NOT NULL,
    realized_pnl DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    unrealized_pnl DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    max_favorable_excursion DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    max_adverse_excursion DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    tp1_hit BOOLEAN NOT NULL DEFAULT FALSE,
    runner_active BOOLEAN NOT NULL DEFAULT FALSE,
    tail_half_locked BOOLEAN NOT NULL DEFAULT FALSE,
    trailing_stop_loss DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    exit_reason VARCHAR(50),
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_trades_symbol_status (symbol, status),
    INDEX idx_trades_entry_time (entry_time),
    INDEX idx_trades_exit_time (exit_time)
);

-- Create candles table
CREATE TABLE candles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    date DATE NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    open_price DECIMAL(10,2) NOT NULL,
    high_price DECIMAL(10,2) NOT NULL,
    low_price DECIMAL(10,2) NOT NULL,
    close_price DECIMAL(10,2) NOT NULL,
    volume BIGINT NOT NULL,
    range DECIMAL(10,2) NOT NULL,
    timeframe INT NOT NULL,
    is_bullish BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_candles_symbol_time (symbol, timestamp),
    INDEX idx_candles_date (date),
    INDEX idx_candles_timeframe (timeframe)
);

-- Insert sample trading session
INSERT INTO trading_sessions (
    session_date, symbol, daily_open, or_high, or_low, or_expansion, 
    fast_candle_exists, regime, session_start
) VALUES (
    CURDATE(), 'BANKNIFTY', 46000.00, 46150.00, 45950.00, 200.00,
    TRUE, 'TREND', NOW()
);

-- Create view for active trades summary
CREATE VIEW active_trades_summary AS
SELECT 
    t.id,
    t.symbol,
    t.direction,
    t.entry_price,
    t.current_price,
    t.realized_pnl + t.unrealized_pnl as total_pnl,
    t.max_favorable_excursion,
    t.max_adverse_excursion,
    t.entry_time,
    TIMESTAMPDIFF(MINUTE, t.entry_time, NOW()) as duration_minutes
FROM trades t
WHERE t.status = 'ACTIVE';

-- Create view for daily performance
CREATE VIEW daily_performance AS
SELECT 
    DATE(t.entry_time) as trade_date,
    t.symbol,
    COUNT(*) as total_trades,
    SUM(t.realized_pnl + t.unrealized_pnl) as total_pnl,
    SUM(CASE WHEN (t.realized_pnl + t.unrealized_pnl) > 0 THEN 1 ELSE 0 END) as winning_trades,
    SUM(CASE WHEN (t.realized_pnl + t.unrealized_pnl) <= 0 THEN 1 ELSE 0 END) as losing_trades,
    AVG(t.realized_pnl + t.unrealized_pnl) as avg_trade_pnl,
    MAX(t.max_favorable_excursion) as best_mfe,
    MAX(t.max_adverse_excursion) as worst_mae
FROM trades t
GROUP BY DATE(t.entry_time), t.symbol
ORDER BY trade_date DESC;
