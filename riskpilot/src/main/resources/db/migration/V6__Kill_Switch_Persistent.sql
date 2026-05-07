-- V6__Kill_Switch_Persistent.sql
-- Upgrades kill_switch_log (created in V3) for persistent, restart-proof state.
--
-- V3 stored kill switch as a single row with triggered=true/false — not audit-friendly.
-- BEFORE: Container file was primary store; wiped on every Render restart.
-- AFTER:  Each trigger event is its own row. cleared_at IS NULL = still active.
--         KillSwitchEngine.restoreFromDatabase() reads this on every startup.

-- Add cleared_at column — NULL means the kill switch is still active for that reason
ALTER TABLE kill_switch_log
    ADD COLUMN IF NOT EXISTS cleared_at TIMESTAMP NULL;

-- Add reason column for per-reason tracking (V3 had a single combined reasons VARCHAR)
ALTER TABLE kill_switch_log
    ADD COLUMN IF NOT EXISTS reason VARCHAR(256);

-- Add triggered_by for audit trail
ALTER TABLE kill_switch_log
    ADD COLUMN IF NOT EXISTS triggered_by VARCHAR(128) DEFAULT 'system';

-- Back-fill reason from the old reasons column for existing rows
UPDATE kill_switch_log
SET reason = COALESCE(reasons, 'LEGACY_UNKNOWN')
WHERE reason IS NULL;

-- Partial index: fast lookup of currently active kill switch entries
CREATE INDEX IF NOT EXISTS idx_kill_switch_active
    ON kill_switch_log (timestamp DESC)
    WHERE cleared_at IS NULL;

COMMENT ON TABLE kill_switch_log IS
    'Persistent kill switch audit log. Active if any row has cleared_at IS NULL.';
COMMENT ON COLUMN kill_switch_log.cleared_at IS
    'NULL = kill switch still active. Operator sets this to re-enable trading.';
COMMENT ON COLUMN kill_switch_log.reason IS
    'Single reason code e.g. EXPECTANCY_NEGATIVE, FEED_UNSTABLE. One row per reason.';
