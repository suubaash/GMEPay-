-- V011 (GAP T2-2): per-day, per-scheme cross-border reconciliation summary — the artifact finance
-- reads. One row per (settlement_date, scheme); a re-run of the same date REPLACES the row, so the
-- summary is idempotent exactly like the exception rows it accompanies.
--
-- The load-bearing column is rate_basis_variance_usd: SIGNED sum over the day of
--   (USD deducted from the partner float)  −  (USD owed SendMN at its registered rate)
-- i.e. the spread between the two different USD bases the same payment is priced on
-- (hub: live USD/KRW or its fallback; scheme: SendMN-registered MNT/USD). Positive = USD retained,
-- negative = USD shortfall (GME owes the scheme more than it collected). cumulative_variance_usd
-- carries the running signed total across all summarised days so a small systematic drift is
-- visible even when no single day breaks.
--
-- scheme_feed_available records whether a real partner settlement/recon FILE was used. It is FALSE
-- today for every scheme: SendMN publishes no recon file format (external gate O4) and 9Pay has no
-- hub orchestration yet (T4-7), so the tie-out is internal-only — three sources GME itself owns.
-- Money NUMERIC(20,8) per MONEY_CONVENTION. PostgreSQL + H2 (MODE=PostgreSQL) portable.

CREATE TABLE corridor_recon_summary (
    id                       BIGSERIAL     NOT NULL,
    settlement_date          DATE          NOT NULL,
    scheme                   VARCHAR(32)   NOT NULL,
    corridor                 VARCHAR(32)   NOT NULL,   -- e.g. 'KRW->MNT'
    batch_id                 VARCHAR(64)   NOT NULL,   -- recon run id, ties to recon_exceptions.batch_id
    txn_count                INTEGER       NOT NULL,
    charged_krw              NUMERIC(20,8) NOT NULL,   -- Σ KRW charged to the wallet (amount + fee)
    local_paid               NUMERIC(20,8) NOT NULL,   -- Σ local currency paid to merchants (MNT)
    local_currency           VARCHAR(3)    NOT NULL,
    usd_deducted             NUMERIC(20,8) NOT NULL,   -- Σ USD taken from the partner float
    usd_owed_scheme          NUMERIC(20,8) NOT NULL,   -- Σ USD owed the scheme at its registered rate
    rate_basis_variance_usd  NUMERIC(20,8) NOT NULL,   -- SIGNED: usd_deducted − usd_owed_scheme
    cumulative_variance_usd  NUMERIC(20,8) NOT NULL,   -- SIGNED running total through this date
    fallback_rate_basis_count INTEGER      NOT NULL,   -- txns priced off the hardcoded USD/KRW fallback
    break_count              INTEGER       NOT NULL,
    break_value_usd          NUMERIC(20,8) NOT NULL,   -- Σ |break| in USD terms
    scheme_feed_available    BOOLEAN       NOT NULL,   -- true only once a real partner file is parsed
    generated_at             TIMESTAMP     NOT NULL,
    CONSTRAINT pk_corridor_recon_summary PRIMARY KEY (id),
    CONSTRAINT uq_corridor_recon_summary_date_scheme UNIQUE (settlement_date, scheme)
);

CREATE INDEX idx_corridor_recon_summary_scheme_date
    ON corridor_recon_summary (scheme, settlement_date);
