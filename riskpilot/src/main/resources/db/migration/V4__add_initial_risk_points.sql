ALTER TABLE trades ADD COLUMN IF NOT EXISTS initial_risk_points
NUMERIC(10, 2) NOT NULL DEFAULT 0.00;

ALTER TABLE trades ADD COLUMN IF NOT EXISTS version
BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN trades.initial_risk_points IS
'Write-once at entry. Preserved across restarts so R-multiples remain
correct after TP1 SL move to breakeven.';
