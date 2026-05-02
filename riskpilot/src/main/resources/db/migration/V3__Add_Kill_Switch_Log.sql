-- V3: Add kill switch log table
-- Created: 2026-05-02

CREATE TABLE IF NOT EXISTS kill_switch_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    triggered BOOLEAN NOT NULL,
    reasons VARCHAR(500),
    timestamp TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_timestamp (timestamp)
);
