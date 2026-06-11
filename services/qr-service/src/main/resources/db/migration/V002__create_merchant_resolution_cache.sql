-- qr-service: merchant-resolution cache (17.2-G04).
-- Local read cache of merchant lookups served through MerchantQrDataPort. Rows are
-- written by the (future) merchant-qr-data sync/read-through; the port adapter only
-- reads. One row per qr_code_id.

CREATE TABLE merchant_resolution_cache (
    qr_code_id    VARCHAR(64)              NOT NULL,
    merchant_id   VARCHAR(50)              NOT NULL,
    merchant_name VARCHAR(200)             NOT NULL,
    scheme_id     VARCHAR(20)              NOT NULL,
    active        BOOLEAN                  NOT NULL,
    resolved_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_merchant_resolution_cache PRIMARY KEY (qr_code_id)
);

CREATE INDEX idx_merchant_resolution_cache_merchant_id ON merchant_resolution_cache(merchant_id);
