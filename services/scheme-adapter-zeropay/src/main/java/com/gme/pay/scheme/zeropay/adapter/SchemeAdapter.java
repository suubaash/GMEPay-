package com.gme.pay.scheme.zeropay.adapter;

import com.gme.pay.scheme.zeropay.adapter.model.AdapterHealth;
import com.gme.pay.scheme.zeropay.adapter.model.BatchFile;
import com.gme.pay.scheme.zeropay.adapter.model.BatchRecord;
import com.gme.pay.scheme.zeropay.adapter.model.BatchType;
import com.gme.pay.scheme.zeropay.adapter.model.CancelResult;
import com.gme.pay.scheme.zeropay.adapter.model.CpmAuthRequest;
import com.gme.pay.scheme.zeropay.adapter.model.CpmAuthResponse;
import com.gme.pay.scheme.zeropay.adapter.model.FetchResult;
import com.gme.pay.scheme.zeropay.adapter.model.FileTypeConfig;
import com.gme.pay.scheme.zeropay.adapter.model.MerchantIdentifier;
import com.gme.pay.scheme.zeropay.adapter.model.MpmSubmitRequest;
import com.gme.pay.scheme.zeropay.adapter.model.MpmSubmitResponse;
import com.gme.pay.scheme.zeropay.adapter.model.PrepareToken;
import com.gme.pay.scheme.zeropay.adapter.model.SchemeConfig;
import com.gme.pay.scheme.zeropay.adapter.model.SchemeResult;
import com.gme.pay.scheme.zeropay.adapter.model.SyncResult;
import com.gme.pay.scheme.zeropay.adapter.model.TransferResult;

import java.time.LocalDate;
import java.util.List;

/**
 * Anti-Corruption Layer (ACL) interface for all payment scheme adapters.
 *
 * <p>IMPORTANT: Hub Core (payment-executor, smart-router, transaction-mgmt) must NOT import any
 * concrete class from the {@code zeropay} sub-package or any other scheme-specific package.
 * All scheme protocol logic is encapsulated here. The Transaction Orchestrator calls only this
 * interface; it never references ZeroPay or any concrete adapter class directly.</p>
 *
 * <p>Method groups correspond to SCH-06 §1.3.1 capability groups:</p>
 * <ul>
 *   <li>QR / CPM / MPM — real-time payment path (§3–§4)</li>
 *   <li>Batch file generation and parsing — daily ZP00xx files (§5–§8)</li>
 *   <li>Merchant sync — inbound delta/full files (§4)</li>
 *   <li>SFTP file transfer — outbound PUT / inbound GET (§2.3–§2.4)</li>
 *   <li>Config / health — registry and monitoring (§1.3.2)</li>
 * </ul>
 */
public interface SchemeAdapter {

    // -----------------------------------------------------------------------
    // QR / Merchant identification  (SCH-06 §3.4)
    // -----------------------------------------------------------------------

    /**
     * Parses and validates a raw EMVCo QR payload, extracts the merchant and QR-code identifiers,
     * and validates them against the local merchant store.
     *
     * @param rawQrPayload the raw EMVCo QR string scanned at POS
     * @return {@link MerchantIdentifier} containing merchantId, qrCodeId, merchantName, and merchantTypeCode
     * @see SCH-06 section 3.4 for CRC-16/CCITT validation and mandatory tag rules
     */
    MerchantIdentifier parseMerchantQR(String rawQrPayload);

    /**
     * Prepares a CPM (Consumer-Presented Mode) payment token for a previously resolved merchant.
     *
     * @param merchantIdentifier the merchant resolved by {@link #parseMerchantQR}
     * @return a one-time {@link PrepareToken} carrying scheme-side context for the subsequent commit
     * @see SCH-06 section 3.5
     */
    PrepareToken prepareCPM(MerchantIdentifier merchantIdentifier);

    /**
     * Authorises a CPM payment against the scheme's real-time API.
     *
     * @param request carries merchantId, qrCodeId, amount (KRW, BigDecimal), and idempotency key
     * @return {@link CpmAuthResponse} with approvalCode and scheme transaction reference
     * @see SCH-06 section 3.6
     */
    CpmAuthResponse authoriseCpm(CpmAuthRequest request);

    /**
     * Submits an MPM (Merchant-Presented Mode) payment request to the scheme.
     *
     * @param request carries merchantId, amount, currency, partner reference, and idempotency key
     * @return {@link MpmSubmitResponse} with scheme result code and reference
     * @see SCH-06 section 4.1
     */
    MpmSubmitResponse submitMpm(MpmSubmitRequest request);

    /**
     * Commits a previously prepared CPM payment, making it final.
     *
     * @param token the token returned by {@link #prepareCPM}
     * @return {@link SchemeResult} indicating success or failure with the scheme's final status code
     * @see SCH-06 section 3.7
     */
    SchemeResult commitPayment(PrepareToken token);

    /**
     * Cancels a payment that has been submitted but not yet settled.
     *
     * @param gmeTxnId GMEPay+ internal transaction ID
     * @return {@link CancelResult} with the scheme's cancellation confirmation
     * @see SCH-06 section 5.1
     */
    CancelResult cancelPayment(String gmeTxnId);

    // -----------------------------------------------------------------------
    // Batch file generation (SCH-06 §5–§8)
    // -----------------------------------------------------------------------

    /**
     * Generates an outbound batch file (e.g. ZP0011) for the given business date.
     *
     * @param fileType     the batch file type (e.g. {@link BatchType#ZP0011})
     * @param businessDate the KST business date the file covers
     * @return a {@link BatchFile} containing the file bytes, record count, and control sum
     * @see SCH-06 section 5.2 for ZP0011; section 8.1 for the full file schedule
     */
    BatchFile generatePaymentResultFile(BatchType fileType, LocalDate businessDate);

    /**
     * Generates a refund result batch file (e.g. ZP0021) for the given business date.
     *
     * @param fileType     the batch file type
     * @param businessDate the KST business date
     * @return {@link BatchFile} with content and metadata
     * @see SCH-06 section 6.2
     */
    BatchFile generateRefundResultFile(BatchType fileType, LocalDate businessDate);

    /**
     * Generates settlement request batch files (e.g. ZP0061) for the given business date.
     *
     * @param fileType     the batch file type
     * @param businessDate the KST business date; ZP0011 and ZP0012 must have completed first
     * @return {@link BatchFile} with net/gross settlement lines
     * @see SCH-06 section 7.1
     */
    BatchFile generateSettlementRequestFile(BatchType fileType, LocalDate businessDate);

    /**
     * Parses a raw inbound batch file (e.g. ZP0012) received from the scheme via SFTP.
     *
     * @param fileType    the batch file type
     * @param fileContent raw bytes of the inbound file (already PGP-decrypted)
     * @return list of {@link BatchRecord} domain objects
     * @see SCH-06 section 5.3
     */
    List<BatchRecord> parseInboundFile(BatchType fileType, byte[] fileContent);

    /**
     * Validates a raw inbound batch file: checks header/trailer record counts, control sum,
     * and field constraints.
     *
     * @param fileType    the batch file type
     * @param fileContent raw bytes of the inbound file
     * @throws com.gme.pay.errors.ApiException if validation fails
     * @see SCH-06 section 5.3
     */
    void validateInboundFile(BatchType fileType, byte[] fileContent);

    // -----------------------------------------------------------------------
    // SFTP file transfer (SCH-06 §2.3–§2.4)
    // -----------------------------------------------------------------------

    /**
     * Transfers an outbound batch file to the scheme SFTP server.
     *
     * @param batchFile    the file produced by a generator method
     * @param remotePath   target path under the scheme's outbound directory
     * @return {@link TransferResult} with remote file size and checksum confirmation
     * @see SCH-06 section 2.3
     */
    TransferResult transferOutbound(BatchFile batchFile, String remotePath);

    /**
     * Fetches an inbound file from the scheme SFTP server.
     *
     * @param remotePath remote path under the scheme's inbound directory
     * @return {@link FetchResult} carrying the raw (PGP-encrypted) bytes and transfer metadata
     * @see SCH-06 section 2.4
     */
    FetchResult fetchInbound(String remotePath);

    // -----------------------------------------------------------------------
    // Config / health / registry (SCH-06 §1.3.2)
    // -----------------------------------------------------------------------

    /**
     * Returns the supported batch file types and their configuration (direction, schedule, retention).
     *
     * @return immutable list of {@link FileTypeConfig} entries for this scheme
     * @see SCH-06 section 7.3
     */
    List<FileTypeConfig> getSupportedFiletypes();

    /**
     * Returns the static scheme configuration (SFTP coordinates, PGP key refs, fee tables, etc.).
     *
     * @return {@link SchemeConfig} for this adapter (scheme-specific, not modifiable at runtime)
     * @see SCH-06 section 1.3.2
     */
    SchemeConfig getSchemeConfig();

    /**
     * Performs a lightweight health probe: SFTP no-op and HTTP HEAD to the real-time API endpoint.
     * This method must never throw — all errors are captured in the returned {@link AdapterHealth}.
     *
     * @return current {@link AdapterHealth} including connectivity status for each channel
     * @see SCH-06 section 1.3.2
     */
    AdapterHealth healthCheck();

    /**
     * Processes a merchant synchronisation result (from ZP0041/ZP0045/ZP0047 or ZP0051 full sync)
     * and updates the local merchant store.
     *
     * @param records    list of merchant delta or full-sync records
     * @param businessDate the KST business date of the sync file
     * @return {@link SyncResult} with insert/update/deactivate counts and any mismatch flag
     * @see SCH-06 section 4.2
     */
    SyncResult processMerchantSync(List<BatchRecord> records, LocalDate businessDate);
}
