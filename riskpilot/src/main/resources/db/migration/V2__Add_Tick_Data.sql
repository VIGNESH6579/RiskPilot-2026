-- V2: Add tick data tracking table
-- Created: 2026-05-02

CREATE TABLE IF NOT EXISTS tick_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    volume BIGINT NOT NULL,
    sequence_id BIGINT,
    received_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    feed_stable BOOLEAN DEFAULT TRUE,
    INDEX idx_symbol_received (symbol, received_at),
    INDEX idx_sequence (sequence_id)
);
