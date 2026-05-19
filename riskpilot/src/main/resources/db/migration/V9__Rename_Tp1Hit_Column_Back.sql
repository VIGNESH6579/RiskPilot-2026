-- V9: Rename tp1_hit column back to tp1hit in trades table

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name='trades' AND column_name='tp1_hit'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name='trades' AND column_name='tp1hit'
    ) THEN
        ALTER TABLE trades RENAME COLUMN tp1_hit TO tp1hit;
    END IF;
END $$;
