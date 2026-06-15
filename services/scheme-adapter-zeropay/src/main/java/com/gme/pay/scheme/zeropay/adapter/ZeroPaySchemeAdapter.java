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
import com.gme.pay.scheme.zeropay.client.ZeroPaySchemeApiClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * ZeroPay implementation of {@link SchemeAdapter}.
 *
 * <p>Real-time payment path (MPM/CPM) is implemented against the ZeroPay scheme simulator
 * (sim-scheme, default :9102). The sim-scheme base URL is read from
 * {@code gmepay.scheme.zeropay.base-url} (see {@link ZeroPaySchemeApiClient}).
 *
 * <p>The bean is guarded by {@code @ConditionalOnProperty(name="adapter.zeropay.enabled",
 * havingValue="true")} so it can be omitted from test slices that do not need it.</p>
 */
@Service
@ConditionalOnProperty(name = "adapter.zeropay.enabled", havingValue = "true", matchIfMissing = false)
public class ZeroPaySchemeAdapter implements SchemeAdapter {

    private final ZeroPayAdapterProperties properties;
    private final ZeroPaySchemeApiClient schemeApiClient;

    public ZeroPaySchemeAdapter(ZeroPayAdapterProperties properties,
                                ZeroPaySchemeApiClient schemeApiClient) {
        this.properties = properties;
        this.schemeApiClient = schemeApiClient;
    }

    // -----------------------------------------------------------------------
    // Config / health
    // -----------------------------------------------------------------------

    @Override
    public SchemeConfig getSchemeConfig() {
        return new SchemeConfig(
                properties.getSchemeId(),
                properties.getSchemeName(),
                properties.getOperatorName(),
                properties.getPayoutCurrency(),
                List.of(SchemeConfig.SupportedMode.MPM, SchemeConfig.SupportedMode.CPM),
                List.of("KR"),
                properties.getRealtimeApiBaseUrl(),
                properties.getSftpHost(),
                properties.getSftpPort(),
                /* sftpUsername       */ "",
                /* sftpPrivateKeyRef  */ "",
                /* pgpPublicKeyRef    */ "",
                /* pgpPrivateKeyRef   */ "",
                /* inboundDir        */ "/gmepay/inbound/",
                /* outboundDir       */ "/gmepay/outbound/",
                /* fileRetentionDays */ 90,
                /* merchantFeeTable  */ java.util.Map.of(),
                /* vanFeeTable       */ java.util.Map.of(),
                java.math.BigDecimal.valueOf(70)
        );
    }

    @Override
    public AdapterHealth healthCheck() {
        String sftpHost = properties.getSftpHost();
        if (sftpHost == null || sftpHost.isBlank()) {
            return AdapterHealth.down("SFTP not configured");
        }
        // Phase 1: no live connections configured
        return AdapterHealth.degraded(false, false, "SFTP and API not yet configured");
    }

    @Override
    public List<FileTypeConfig> getSupportedFiletypes() {
        return List.of(
                new FileTypeConfig(BatchType.ZP0011, FileTypeConfig.FileDirection.OUTBOUND,
                        LocalTime.of(2, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0012, FileTypeConfig.FileDirection.INBOUND,
                        LocalTime.of(5, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0021, FileTypeConfig.FileDirection.OUTBOUND,
                        LocalTime.of(2, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0022, FileTypeConfig.FileDirection.INBOUND,
                        LocalTime.of(5, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0041, FileTypeConfig.FileDirection.INBOUND,
                        null, 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0043, FileTypeConfig.FileDirection.INBOUND,
                        null, 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0045, FileTypeConfig.FileDirection.INBOUND,
                        null, 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0047, FileTypeConfig.FileDirection.INBOUND,
                        null, 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0051, FileTypeConfig.FileDirection.INBOUND,
                        null, 90, true, FileTypeConfig.SyncFrequency.WEEKLY),
                new FileTypeConfig(BatchType.ZP0053, FileTypeConfig.FileDirection.INBOUND,
                        null, 90, true, FileTypeConfig.SyncFrequency.WEEKLY),
                new FileTypeConfig(BatchType.ZP0055, FileTypeConfig.FileDirection.INBOUND,
                        null, 90, true, FileTypeConfig.SyncFrequency.WEEKLY),
                new FileTypeConfig(BatchType.ZP0061, FileTypeConfig.FileDirection.OUTBOUND,
                        LocalTime.of(5, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0062, FileTypeConfig.FileDirection.INBOUND,
                        LocalTime.of(10, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0063, FileTypeConfig.FileDirection.OUTBOUND,
                        LocalTime.of(14, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0064, FileTypeConfig.FileDirection.INBOUND,
                        LocalTime.of(19, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0065, FileTypeConfig.FileDirection.OUTBOUND,
                        LocalTime.of(22, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY),
                new FileTypeConfig(BatchType.ZP0066, FileTypeConfig.FileDirection.OUTBOUND,
                        LocalTime.of(22, 0), 90, false, FileTypeConfig.SyncFrequency.DAILY)
        );
    }

    // -----------------------------------------------------------------------
    // Real-time payment path (MPM + CPM)
    // -----------------------------------------------------------------------

    /**
     * Decodes the QR payload via sim-scheme and returns a {@link MerchantIdentifier}.
     * The QR mode ("static" or "dynamic") is preserved in {@code merchantTypeCode}.
     */
    @Override
    public MerchantIdentifier parseMerchantQR(String rawQrPayload) {
        ZeroPaySchemeApiClient.DecodeQrResponse decoded =
                schemeApiClient.decodeQr(rawQrPayload);
        return new MerchantIdentifier(
                decoded.merchantId(),
                /* qrCodeId — not returned by decode; use payload hash as surrogate */
                Integer.toHexString(rawQrPayload.hashCode()),
                decoded.merchantName(),
                decoded.mode()   // "static" | "dynamic"
        );
    }

    /**
     * CPM prepare — issues a CPM token via sim-scheme {@code POST /cpm/token}.
     *
     * <p>The token ID (cpmToken) is stored in {@link PrepareToken#tokenId()} so that
     * {@link #authoriseCpm} can pass it as the {@code cpmToken} parameter to authorize.</p>
     */
    @Override
    public PrepareToken prepareCPM(MerchantIdentifier merchantIdentifier) {
        ZeroPaySchemeApiClient.CpmTokenResponse tokenResp = schemeApiClient.fetchCpmToken(
                merchantIdentifier.merchantId(),
                "WALLET"  // funding source placeholder for the sim
        );
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(tokenResp.expiresAt());
        } catch (Exception ignored) {
            expiresAt = Instant.now().plusSeconds(300);
        }
        return new PrepareToken(
                tokenResp.cpmToken(),       // tokenId — passed to authoriseCpm as qrCodeId
                merchantIdentifier.merchantId(),
                expiresAt
        );
    }

    /**
     * CPM authorise — authorises against sim-scheme using the CPM token embedded in the request.
     */
    @Override
    public CpmAuthResponse authoriseCpm(CpmAuthRequest request) {
        // Use the qrCodeId field as the CPM token (set by the caller from PrepareToken.tokenId)
        ZeroPaySchemeApiClient.AuthorizeResponse authResp = schemeApiClient.authorize(
                "CPM",
                null,
                request.qrCodeId(),
                request.amountKrw(),
                "KRW",
                request.partnerTxnRef()
        );
        // Commit immediately after authorize (CPM is a two-step single flow)
        ZeroPaySchemeApiClient.CommitResponse commitResp =
                schemeApiClient.commit(authResp.authId());

        return new CpmAuthResponse(
                authResp.authId(),          // approvalCode = authId from scheme
                commitResp.schemeTxnRef(),   // zeroPayTxnRef = final schemeTxnRef
                "00",
                "CAPTURED"
        );
    }

    /**
     * MPM submit: authorize then commit against sim-scheme.
     *
     * <p>For MPM_DYNAMIC: decodes the QR first to extract the embedded amount and uses
     * that for the authorize call (so the scheme never returns AMOUNT_MISMATCH).
     * For MPM_STATIC: the {@code request.amountKrw()} is used as-is.
     */
    @Override
    public MpmSubmitResponse submitMpm(MpmSubmitRequest request) {
        String qrPayload = request.qrPayload();
        // Decode the QR to discover mode (static/dynamic)
        ZeroPaySchemeApiClient.DecodeQrResponse decoded = schemeApiClient.decodeQr(qrPayload);

        String schemeMode;
        BigDecimal authorizeAmount;
        if ("dynamic".equals(decoded.mode())) {
            schemeMode = "MPM_DYNAMIC";
            // For dynamic QR the amount is embedded; use it to avoid AMOUNT_MISMATCH
            authorizeAmount = decoded.amount() != null
                    ? new BigDecimal(decoded.amount())
                    : request.amountKrw();
        } else {
            schemeMode = "MPM_STATIC";
            authorizeAmount = request.amountKrw();
        }

        // The decoded QR (tag 53) carries the scheme's authoritative currency; fall back to
        // it when the caller didn't pass one (sim-scheme rejects a blank currency).
        String currency = (request.currency() != null && !request.currency().isBlank())
                ? request.currency()
                : decoded.currency();

        ZeroPaySchemeApiClient.AuthorizeResponse authResp = schemeApiClient.authorize(
                schemeMode,
                qrPayload,
                null,
                authorizeAmount,
                currency,
                request.partnerTxnRef()
        );

        ZeroPaySchemeApiClient.CommitResponse commitResp =
                schemeApiClient.commit(authResp.authId());

        return new MpmSubmitResponse(
                commitResp.schemeTxnRef(),
                "00",
                "CAPTURED",
                authResp.authId(),
                commitResp.committedAt()
        );
    }

    /**
     * Commit a previously prepared CPM payment by its token ID.
     * The token carries the authId in its {@code tokenId} field.
     */
    @Override
    public SchemeResult commitPayment(PrepareToken token) {
        ZeroPaySchemeApiClient.CommitResponse commitResp =
                schemeApiClient.commit(token.tokenId());
        return new SchemeResult(
                "CAPTURED".equals(commitResp.status()),
                "00",
                commitResp.status(),
                commitResp.schemeTxnRef()
        );
    }

    /**
     * Cancels/refunds a previously committed payment via sim-scheme
     * {@code POST /payments/{authId}/refund}.
     *
     * <p>The {@code authId} parameter is the authorise-level authId stored in
     * {@code SubmitPaymentResponse.schemeApprovalCode} by the MPM / CPM submit path.
     * The caller (ZeroPaySchemeController) passes {@code request.schemeTxnRef} which
     * has been populated with the authId in our /cancel contract (see controller).</p>
     *
     * @param authId  the authorise-level authId (NOT the commit-level schemeTxnRef)
     * @return cancel result with refund ID
     */
    @Override
    public CancelResult cancelPayment(String authId) {
        ZeroPaySchemeApiClient.RefundResponse refundResp =
                schemeApiClient.refund(authId, null);
        return new CancelResult(
                "REFUNDED".equals(refundResp.status()),
                refundResp.status(),
                refundResp.refundId()
        );
    }

    @Override
    public BatchFile generatePaymentResultFile(BatchType fileType, LocalDate businessDate) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - generatePaymentResultFile");
    }

    @Override
    public BatchFile generateRefundResultFile(BatchType fileType, LocalDate businessDate) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - generateRefundResultFile");
    }

    @Override
    public BatchFile generateSettlementRequestFile(BatchType fileType, LocalDate businessDate) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - generateSettlementRequestFile");
    }

    @Override
    public List<BatchRecord> parseInboundFile(BatchType fileType, byte[] fileContent) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - parseInboundFile");
    }

    @Override
    public void validateInboundFile(BatchType fileType, byte[] fileContent) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - validateInboundFile");
    }

    @Override
    public TransferResult transferOutbound(BatchFile batchFile, String remotePath) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - transferOutbound");
    }

    @Override
    public FetchResult fetchInbound(String remotePath) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - fetchInbound");
    }

    @Override
    public SyncResult processMerchantSync(List<BatchRecord> records, LocalDate businessDate) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - processMerchantSync");
    }
}
