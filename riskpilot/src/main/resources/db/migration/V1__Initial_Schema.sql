-- V1: Initial RiskPilot Schema
-- Created: 2026-05-02

-- Trading Sessions table
CREATE TABLE IF NOT EXISTS trading_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_date DATE NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    session_start TIMESTAMP NOT NULL,
    session_end TIMESTAMP,
    session_active BOOLEAN DEFAULT TRUE,
    total_pnl DECIMAL(19, 4) DEFAULT 0,
    max_profit DECIMAL(19, 4) DEFAULT 0,
    max_drawdown DECIMAL(19, 4) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_date_symbol (session_date, symbol)
);

-- Trades table
CREATE TABLE IF NOT EXISTS trades (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    entry_price DECIMAL(10, 2) NOT NULL,
    stop_loss DECIMAL(10, 2) NOT NULL,
    target_price DECIMAL(10, 2) NOT NULL,
    position_size DECIMAL(5, 2) NOT NULL,
    remaining_size DECIMAL(5, 2) NOT NULL,
    realized_pnl DECIMAL(10, 2) DEFAULT 0,
    unrealized_pnl DECIMAL(10, 2) DEFAULT 0,
    max_favorable_excursion DECIMAL(10, 2) DEFAULT 0,
    max_adverse_excursion DECIMAL(10, 2) DEFAULT 0,
    tp1_hit BOOLEAN DEFAULT FALSE,
    runner_active BOOLEAN DEFAULT FALSE,
    tail_half_locked BOOLEAN DEFAULT FALSE,
    trailing_stop_loss DECIMAL(10, 2),
    status VARCHAR(20) DEFAULT 'ACTIVE',
    exit_reason VARCHAR(50),
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_symbol_status (symbol, status),
    INDEX idx_entry_time (entry_time)
);

-- Candles table
CREATE TABLE IF NOT EXISTS candles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    date DATE NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    open_price DECIMAL(10, 2) NOT NULL,
    high_price DECIMAL(10, 2) NOT NULL,
    low_price DECIMAL(10, 2) NOT NULL,
    close_price DECIMAL(10, 2) NOT NULL,
    volume BIGINT NOT NULL,
    range DECIMAL(10, 2),
    timeframe INTEGER NOT NULL,
    is_bullish BOOLEAN,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_symbol_time (symbol, timestamp),
    INDEX idx_date (date)
);

-- Trading Signals table
CREATE TABLE IF NOT EXISTS trading_signals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    entry_price DECIMAL(10, 2) NOT NULL,
    stop_loss DECIMAL(10, 2) NOT NULL,
    target_price DECIMAL(10, 2) NOT NULL,
    confidence INTEGER,
    status VARCHAR(20) DEFAULT 'PENDING',
    execution_time TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_symbol_status (symbol, status),
    INDEX idx_execution_time (execution_time)
);

-- Flyway baseline marker
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
VALUES (1, '1', 'Initial Schema', 'SQL', 'V1__Initial_Schema.sql', 0, CURRENT_USER, CURRENT_TIMESTAMP, 0, TRUE)
ON CONFLICT DO NOTHING;
