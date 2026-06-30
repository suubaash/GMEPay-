package com.gme.pay.merchant.sync;

/**
 * Enumeration of ZeroPay batch-file types ingested by the merchant sync path.
 *
 * <p>File naming follows the ZeroPay SFTP delivery convention:
 * {@code <TYPE>_YYYYMMDD[_SEQ].dat}, e.g. {@code ZP0041_20260615.dat}.
 *
 * <p>Layout references are INTERNAL APPROXIMATIONS documented from available
 * ZeroPay API guides. Fields marked {@code TODO(spec)} must be validated against
 * the final ZeroPay Merchant Data Interface Specification before production use.
 */
public enum ZeroPayFileType {

    /**
     * ZP0041 — Incremental merchant new / change (delta).
     *
     * <p>Fixed-width CSV layout (pipe-delimited):
     * <pre>
     *   Col  Field              Width   Notes
     *   1    record_type        2       "MN"=new, "MC"=change, "MD"=delete
     *   2    merchant_id        10      ZeroPay merchant identifier (CHAR 10)
     *   3    merchant_name      50      UTF-8 merchant display name         TODO(spec)
     *   4    merchant_type      20      e.g. RETAIL, FOOD_BEVERAGE          TODO(spec)
     *   5    fee_type           20      e.g. DOMESTIC, CROSSBORDER          TODO(spec)
     *   6    status             12      ACTIVE | INACTIVE | SUSPENDED | DEACTIVATED
     *   7    payout_currency    3       ISO 4217 (e.g. KRW)
     *   8    scheme_id          20      e.g. ZEROPAY                        TODO(spec)
     *   9    city               30      City / locality                     TODO(spec)
     *   10   mcc                4       ISO 18245 MCC                       TODO(spec)
     * </pre>
     */
    ZP0041("ZP0041", SyncMode.INCREMENTAL, RecordDomain.MERCHANT),

    /**
     * ZP0043 — QR code registration / deactivation (delta).
     *
     * <p>Pipe-delimited layout:
     * <pre>
     *   Col  Field              Notes
     *   1    record_type        "QR"=register, "QD"=deactivate
     *   2    qr_code            ZeroPay QR identifier (CHAR 20)
     *   3    merchant_id        Owning merchant (CHAR 10)
     *   4    status             ACTIVE | DEACTIVATED
     * </pre>
     */
    ZP0043("ZP0043", SyncMode.INCREMENTAL, RecordDomain.QR),

    /**
     * ZP0045 — Franchise group new / change (delta).                        TODO(spec)
     *
     * <p>Treated as MERCHANT domain changes; franchise rows are processed
     * identically to ZP0041 merchant records in this implementation.
     */
    ZP0045("ZP0045", SyncMode.INCREMENTAL, RecordDomain.MERCHANT),

    /**
     * ZP0047 — Franchise QR registration / deactivation (delta).            TODO(spec)
     *
     * <p>Treated identically to ZP0043 QR records.
     */
    ZP0047("ZP0047", SyncMode.INCREMENTAL, RecordDomain.QR),

    /**
     * ZP0051 — Full merchant list (periodic reconciliation).
     *
     * <p>Same pipe-delimited column layout as ZP0041, but WITHOUT a record_type
     * column — every row is an authoritative merchant record. Rows absent from
     * the full list compared to the local store are deactivated (soft-delete).
     *
     * <p>Full-list reconciliation (deactivating orphaned records absent from the list)
     * is performed by {@link MerchantSyncService} when
     * {@code gmepay.merchant-sync.reconcile-orphans=true}; otherwise the full list is
     * applied as upsert-only.
     */
    ZP0051("ZP0051", SyncMode.FULL_LIST, RecordDomain.MERCHANT),

    /**
     * ZP0053 — Full QR code list (periodic reconciliation).
     *
     * <p>Same pipe-delimited layout as ZP0043 without a record_type column.
     * Every row is treated as an active QR registration; QRs absent from the list
     * are deactivated when {@code gmepay.merchant-sync.reconcile-orphans=true}.
     */
    ZP0053("ZP0053", SyncMode.FULL_LIST, RecordDomain.QR);

    /** Prefix used in the data file name (e.g. {@code ZP0041}). */
    public final String filePrefix;

    /** Whether this file carries incremental deltas or the full authoritative list. */
    public final SyncMode syncMode;

    /** Whether rows in this file represent merchant records or QR code records. */
    public final RecordDomain domain;

    ZeroPayFileType(String filePrefix, SyncMode syncMode, RecordDomain domain) {
        this.filePrefix = filePrefix;
        this.syncMode = syncMode;
        this.domain = domain;
    }

    /** Returns the file type whose prefix matches the given filename, or {@code null}. */
    public static ZeroPayFileType fromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        String upper = filename.toUpperCase();
        for (ZeroPayFileType ft : values()) {
            if (upper.contains(ft.filePrefix)) {
                return ft;
            }
        }
        return null;
    }

    public enum SyncMode { INCREMENTAL, FULL_LIST }
    public enum RecordDomain { MERCHANT, QR }
}
