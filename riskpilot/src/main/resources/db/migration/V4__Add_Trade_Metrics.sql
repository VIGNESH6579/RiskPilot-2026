-- V4: Add trade metrics and edge tracking
-- Created: 2026-05-02

CREATE TABLE IF NOT EXISTS trade_metrics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_id BIGINT NOT NULL,
    realized_r DECIMAL(10, 4),
    tp1_hit BOOLEAN DEFAULT FALSE,
    runner_captured BOOLEAN DEFAULT FALSE,
    entry_slippage DECIMAL(10, 2) DEFAULT 0,
    runner_slippage DECIMAL(10, 2) DEFAULT 0,
    mfe DECIMAL(10, 2) DEFAULT 0,
    mae DECIMAL(10, 2) DEFAULT 0,
    regime_score INTEGER,
    or_range DECIMAL(10, 2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (trade_id) REFERENCES trades(id) ON DELETE CASCADE,
    INDEX idx_trade_id (trade_id)
);

CREATE TABLE IF NOT EXISTS edge_metrics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    expectancy DECIMAL(10, 4) DEFAULT 0,
    tp1_rate DECIMAL(5, 4) DEFAULT 0,
    runner_rate DECIMAL(5, 4) DEFAULT 0,
    avg_entry_slippage DECIMAL(10, 2) DEFAULT 0,
    avg_runner_slippage DECIMAL(10, 2) DEFAULT 0,
    tail_contribution DECIMAL(5, 4) DEFAULT 0,
    decay_score INTEGER DEFAULT 0,
    window_size INTEGER DEFAULT 0,
    timestamp TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_timestamp (timestamp)
);
