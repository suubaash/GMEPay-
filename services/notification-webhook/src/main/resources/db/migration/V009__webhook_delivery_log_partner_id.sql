-- notification-webhook: give a delivery row the partner it belongs to, so the drain can select
-- PER ENDPOINT instead of one global ORDER BY created_at across every partner.
--
-- The defect being closed (T3-11 defect 5, the half that concurrency did not fix): the drain read the
-- oldest `batch-size` PENDING rows regardless of partner. A partner whose endpoint was down therefore
-- (a) filled the page, so healthy partners' rows were never even selected, and (b) occupied the
-- delivery workers, so the rows that WERE selected waited behind it. Raising concurrency from 1 to 8
-- moved the number of simultaneously-stalled deliveries needed to stall everyone from 1 to 8; it did
-- not decouple partners from each other.
--
-- The partner id was already in the row -- inside the JSON payload, where SQL cannot fairly page by
-- it. Promoting it to a column is what makes a fair selection query possible at all.
--
-- NULLABLE, and NOT backfilled. Backfilling would mean parsing JSON in portable SQL across PostgreSQL
-- 16 and H2-in-PostgreSQL-mode, which is exactly the kind of migration that works on one and quietly
-- mangles the other. Rows written before this migration keep a NULL partner_id and are treated as one
-- "unattributed" group by the drain, which falls back to reading the partner out of the payload for
-- them at dispatch time -- so legacy rows are still delivered, still fairly, and simply drain away.
-- New rows carry the column from the moment WebhookPersistenceService enqueues them.
--
-- PostgreSQL-compatible SQL that also works under H2 PostgreSQL mode.

ALTER TABLE webhook_delivery_log ADD COLUMN partner_id BIGINT;

-- The fair-selection index: "the oldest PENDING rows for THIS partner". Leading (status, partner_id)
-- also serves the DISTINCT partner scan that decides how the batch is shared out.
CREATE INDEX idx_webhook_delivery_log_status_partner_created
    ON webhook_delivery_log (status, partner_id, created_at);
