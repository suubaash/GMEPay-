-- V034: per-partner DAILY TRANSACTION-COUNT cap on partner_limits (WBS 13.8 — AML velocity control).
-- A count ceiling complements the V020 amount caps: it bounds how MANY transactions a partner may push
-- per KST day (anti-structuring — splitting one large remittance into many small ones evades an amount cap
-- but not a count cap). NULL = unconstrained, same convention as the *_usd caps. INT (a daily count needs
-- no more). Additive, all-nullable (Expand discipline, ADR-013); enforced race-free at authorize time by
-- prefunding's chargeCumulative under the per-partner row lock.
ALTER TABLE partner_limits ADD COLUMN daily_txn_count_limit INT;
