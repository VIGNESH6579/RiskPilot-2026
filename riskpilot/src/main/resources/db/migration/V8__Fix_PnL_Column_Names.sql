-- V8: Ensure PnL column names match Hibernate expectations and V1 schema
-- Hibernate with default naming strategy expects realized_pn_l for realizedPnL
-- V1 already created realized_pn_l, unrealized_pn_l, and total_pn_l
-- This migration is a safety measure to ensure consistency

DO $$
BEGIN
    -- Fix realized_pnl -> realized_pn_l if it exists (some older versions might have used it)
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='trades' AND column_name='realized_pnl'
    ) THEN
        ALTER TABLE trades RENAME COLUMN realized_pnl TO realized_pn_l;
    END IF;

    -- Fix unrealized_pnl -> unrealized_pn_l
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='trades' AND column_name='unrealized_pnl'
    ) THEN
        ALTER TABLE trades RENAME COLUMN unrealized_pnl TO unrealized_pn_l;
    END IF;

    -- Fix total_pnl -> total_pn_l in trading_sessions
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='trading_sessions' AND column_name='total_pnl'
    ) THEN
        ALTER TABLE trading_sessions RENAME COLUMN total_pnl TO total_pn_l;
    END IF;
END $$;
