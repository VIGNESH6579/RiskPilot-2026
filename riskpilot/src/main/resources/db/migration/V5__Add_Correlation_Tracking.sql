-- V5: Add correlation ID tracking for trade operations
-- Created: 2026-05-02
-- BUG-044: Removed duplicate version column - using id as primary key only

CREATE TABLE IF NOT EXISTS trade_correlation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    correlation_id VARCHAR(16) NOT NULL,
    trade_id VARCHAR(50),
    operation VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_correlation (correlation_id),
    INDEX idx_timestamp (timestamp)
);

-- Add correlation tracking columns to trades table
ALTER TABLE trades 
ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(16) AFTER id,
ADD COLUMN IF NOT EXISTS initial_risk_points DECIMAL(10, 2) AFTER target_price;
