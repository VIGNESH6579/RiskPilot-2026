-- V14: Fix adaptive_regime_config seed to align with application-prod.yml min-or-range: 60
--
-- PROBLEM:
--   V11 seeded min_or_range = 120.0 in adaptive_regime_config (id=1).
--   application-prod.yml sets riskpilot.filters.min-or-range: 60.
--   With zero prior trades the AdaptiveRegimeEngine never recalibrates, so the DB
--   value remains 120.  RiskGateEngine then rejects any day with OR between 60–119
--   with ADAPTIVE_OR_TOO_SMALL — blocking trades on low-volatility sessions that
--   the prod config explicitly permits.
--
-- FIX:
--   Lower the seed to 60.0.  RiskGateEngine now also enforces the more permissive
--   of (adaptiveConfig.minORRange, config.filters.minOrRange) so the config floor
--   can never be undercut by adaptive tightening either.
--
-- Safe to run multiple times: UPDATE on a fixed id=1 row is idempotent.

UPDATE adaptive_regime_config
SET    min_or_range = 60.0,
       notes        = 'v14-fix: aligned with application-prod min-or-range=60'
WHERE  id = 1
  AND  min_or_range = 120.0;  -- only update if still at the old seed value
