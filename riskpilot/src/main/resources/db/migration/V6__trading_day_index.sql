-- Add index for trade_logs daily query to optimize cumulativeDailyLossR restoration
CREATE INDEX idx_trade_logs_trading_day ON trade_logs(trading_day);
