-- auth-identity service: durable nonce store for HMAC replay protection.
-- Per SEC-09 §3.3 and API-05 §3.6: a (partnerId, nonce) seen within the
-- nonce TTL window must be rejected with 401 REPLAY_DETECTED.
--
-- This table is the persistent backing for the JpaNonceStore (Phase 1).
-- A future Redis-backed store will provide low-latency lookups while
-- this table remains as the durable audit / fallback record.
--
-- nonce         : the X-Nonce header value (64 chars sufficient for UUID / random hex).
-- partner_id    : owning partner id as a string (matches PartnerCredentialPort shape).
-- used_at       : server-side timestamp at which the nonce was first observed.
CREATE TABLE used_nonces (
    nonce       VARCHAR(64)  PRIMARY KEY,
    partner_id  VARCHAR(32)  NOT NULL,
    used_at     TIMESTAMP    NOT NULL
);
