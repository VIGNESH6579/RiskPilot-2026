-- V13: Replace file-based option_chain_cache.json with a DB-backed table.
-- One row per cache key (currently just 'NIFTY').
-- Replaces the file written/read by OptionChainService so state survives
-- Render free-tier restarts without relying on the ephemeral filesystem.

CREATE TABLE IF NOT EXISTS option_chain_cache (
    cache_key     VARCHAR(50)    NOT NULL PRIMARY KEY,
    support       INTEGER        NOT NULL DEFAULT 0,
    resistance    INTEGER        NOT NULL DEFAULT 0,
    spot          DECIMAL(12, 2) NOT NULL DEFAULT 0,
    expiry        VARCHAR(20)    NOT NULL DEFAULT '',
    previous_close DECIMAL(12, 2) NOT NULL DEFAULT 0,
    source        VARCHAR(50)    NOT NULL DEFAULT '',
    updated_epoch_ms BIGINT      NOT NULL DEFAULT 0,
    live          BOOLEAN        NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
