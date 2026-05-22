-- V12: Add initial_risk_points and realized_r to trades table.
--
-- initial_risk_points: total risk in index points at trade open = |entry-sl| * positionSize
--   Used to calculate R-multiples accurately on the frontend.
--
-- realized_r: final R-multiple for the trade = (tp1Pnl + exitPnl) / initial_risk_points
--   Stored so the dashboard history tab shows accurate R values for DB-loaded rows
--   without having to recompute them client-side from raw points.
--
-- Both columns are NOT NULL with DEFAULT 0 so existing rows get a safe neutral value
-- and no manual data migration is required.

ALTER TABLE trades
    ADD COLUMN IF NOT EXISTS initial_risk_points DECIMAL(10, 4) NOT NULL DEFAULT 0;

ALTER TABLE trades
    ADD COLUMN IF NOT EXISTS realized_r DECIMAL(10, 4) NOT NULL DEFAULT 0;
