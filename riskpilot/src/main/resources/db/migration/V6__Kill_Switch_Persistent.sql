-- V6__Kill_Switch_Persistent.sql
-- Makes kill switch state persistent across container restarts.
--
-- BEFORE: Kill switch was stored only in a container-local file (KILL_SWITCH.flag).
--         On Render free tier the container filesystem is ephemeral — wiped on every
--         restart. A kill switch written during a crash was lost → trading resumed unsafely.
--
-- AFTER: PostgreSQL is the primary store. cleared_at IS NULL means "still active".
--        On startup KillSwitchEngine.restoreFromDatabase() reads this table before
--        allowing any trade evaluation.

CREATE TABLE IF NOT EXISTS kill_switch_log (
    id           BIGSERIAL    PRIMARY KEY,
    reason       VARCHAR(256) NOT NULL,
    triggered_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    cleared_at   TIMESTAMPTZ  NULL,
    triggered_by VARCHAR(128) NOT NULL DEFAULT 'system',
    notes        TEXT         NULL
);

CREATE INDEX IF NOT EXISTS idx_kill_switch_active
    ON kill_switch_log (triggered_at DESC)
    WHERE cleared_at IS NULL;

COMMENT ON TABLE kill_switch_log IS
    'Persistent kill switch state. Active if any row has cleared_at IS NULL.';
COMMENT ON COLUMN kill_switch_log.cleared_at IS
    'NULL = kill switch still active. Operator sets this to re-enable trading.';
