-- payment-executor: the persisted DAY-CLOSE artifact (gap T2-5 / CFO#10).
--
-- CFO#10's "Done =" asked for a daily close artifact that is "persisted and exportable". A report that
-- only exists while an HTTP request is in flight is not something finance can sign: the numbers move as
-- soon as a late correction lands, so the version that was reviewed has to be the version that is kept.
-- One row per business date, overwritten on a re-run (the report is a DERIVED view of that day, so a
-- re-run is a correction, not a second day).
--
-- The full report rides as JSON in `report_json` rather than being normalised into columns. That is a
-- deliberate choice, not laziness: the report's job is to name variances, the set of variances will grow
-- (each new corridor and each resolved decision changes it), and normalising it would mean a Flyway
-- migration every time finance asks for another line. The columns that ARE broken out are the only ones
-- anything queries on: the date, whether it was clean, and the counts that drive an alert.
--
-- `unresolved_decision_count` is a first-class column on purpose. A day that is arithmetically tidy but
-- carries money whose accounting treatment is an OPEN DECISION (register items T2-10, T2-11) is NOT a
-- clean day, and the schema should make it impossible to report it as one by accident.
--
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for unit slices. Portable types only.

CREATE TABLE day_close_reports (
    id                        BIGSERIAL    PRIMARY KEY,
    -- The business date the report covers, in the configured close timezone (KST by default).
    business_date             DATE         NOT NULL,
    -- When this version of the report was produced.
    generated_at              TIMESTAMP    NOT NULL,
    -- SCHEDULER | OPERATOR -- who asked for this version.
    trigger_source            VARCHAR(16)  NOT NULL,
    -- True only when there are NO variances AND no unresolved-decision amount. See the view's javadoc:
    -- an unresolved decision holds this false while it carries money, exactly as revenue-ledger's
    -- journal-reconciliation `clean` flag does.
    clean                     BOOLEAN      NOT NULL,
    -- Number of named variances (a delta between two legs that the report could quantify).
    variance_count            INT          NOT NULL DEFAULT 0,
    -- Number of variances whose TREATMENT is an open finance decision rather than a defect.
    unresolved_decision_count INT          NOT NULL DEFAULT 0,
    -- Number of legs (transactions / journal / prefunding) that could NOT be read. A report built on a
    -- missing leg is not a clean day either, however tidy the legs that did load look.
    unavailable_leg_count     INT          NOT NULL DEFAULT 0,
    -- The whole report, as served by GET /internal/ops/day-close.
    report_json               TEXT         NOT NULL,
    -- One row per business date; a re-run overwrites it.
    CONSTRAINT uq_day_close_reports_date UNIQUE (business_date),
    CONSTRAINT ck_day_close_reports_trigger
        CHECK (trigger_source IN ('SCHEDULER', 'OPERATOR'))
);

-- The finance read pattern: most recent closes first.
CREATE INDEX idx_day_close_reports_date ON day_close_reports (business_date DESC);
