package com.gme.pay.scheme.zeropay.adapter;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
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
import com.gme.pay.scheme.zeropay.batch.Zp0011FileFormatter;
import com.gme.pay.scheme.zeropay.batch.Zp0011Record;
import com.gme.pay.scheme.zeropay.batch.Zp0012FileParser;
import com.gme.pay.scheme.zeropay.batch.Zp0012Record;
import com.gme.pay.scheme.zeropay.batch.Zp0021FileFormatter;
import com.gme.pay.scheme.zeropay.batch.Zp0021Record;
import com.gme.pay.scheme.zeropay.batch.Zp0022FileParser;
import com.gme.pay.scheme.zeropay.batch.Zp0022Record;
import com.gme.pay.scheme.zeropay.batch.Zp0065FileFormatter;
import com.gme.pay.scheme.zeropay.batch.Zp0065Record;
import com.gme.pay.scheme.zeropay.batch.Zp0066FileFormatter;
import com.gme.pay.scheme.zeropay.batch.Zp0066Record;
import com.gme.pay.scheme.zeropay.batch.ZpBatchDataPort;
import com.gme.pay.scheme.zeropay.batch.ZpSettlementRequestFormatter;
import com.gme.pay.scheme.zeropay.batch.ZpSettlementRequestRecord;
import com.gme.pay.scheme.zeropay.batch.ZpSettlementResultParser;
import com.gme.pay.scheme.zeropay.batch.ZpSettlementResultRecord;
import com.gme.pay.scheme.zeropay.client.ZeroPaySchemeApiClient;
import com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMpm420000;
import com.gme.pay.scheme.zeropay.sftp.SftpTransport;
import com.gme.pay.scheme.zeropay.sftp.SftpTransportException;
import com.gme.pay.scheme.zeropay.transport.ZeroPayTcpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final Logger log = LoggerFactory.getLogger(ZeroPaySchemeAdapter.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** Rolling 8-digit message trace number (전문 field 10) for the TCP path. */
    private static final AtomicLong TRACE_SEQ = new AtomicLong(0);

    private final ZeroPayAdapterProperties properties;
    private final ZeroPaySchemeApiClient schemeApiClient;
    private final SftpTransport sftpTransport;
    private final ZpBatchDataPort batchDataPort;
    /** Lazily built when {@code adapter.zeropay.transport=TCP}; null on the default REST/sim path. */
    private volatile ZeroPayTcpTransport tcpTransport;

    public ZeroPaySchemeAdapter(ZeroPayAdapterProperties properties,
                                ZeroPaySchemeApiClient schemeApiClient,
                                SftpTransport sftpTransport,
                                ZpBatchDataPort batchDataPort) {
        this.properties    = properties;
        this.schemeApiClient = schemeApiClient;
        this.sftpTransport = sftpTransport;
        this.batchDataPort = batchDataPort;
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
        // Step 8: when configured for the real ZeroPay 전문/TCP relay, frame + send the 0200
        // payment 전문 over a socket instead of the REST sim. Config-only switch; same contract.
        if ("TCP".equalsIgnoreCase(properties.getTransport())) {
            return submitMpmViaTcp(request);
        }
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
     * MPM submit over the real ZeroPay 전문/TCP transport (Step 8). Encodes the 0200 변동형 MPM
     * (420000) payment 전문 from the request, exchanges it over the socket, and maps the decoded
     * 0210 response to {@link MpmSubmitResponse}. A transport failure propagates (never silently
     * treated as approval); a decoded decline returns a non-CAPTURED status.
     *
     * <p>Spec caveat: the QR-detail fields (registrar id / serial / check char, 전문 fields 35-37)
     * are populated from the parsed ZeroPay QR at real-KFTC integration — the same caveat the codec
     * carries for the 응답코드 table. The transport, framing, amount/merchant/fee fields are wired.
     */
    private MpmSubmitResponse submitMpmViaTcp(MpmSubmitRequest request) {
        String txnUniqueNo = request.partnerTxnRef() != null && request.partnerTxnRef().length() > 13
                ? request.partnerTxnRef().substring(0, 13)
                : request.partnerTxnRef();
        long amountKrw = request.amountKrw() == null
                ? 0L
                : request.amountKrw().setScale(0, RoundingMode.HALF_UP).longValueExact();
        LocalDateTime now = LocalDateTime.now(KST);
        String traceNo = String.format("%08d", Math.floorMod(TRACE_SEQ.incrementAndGet(), 100_000_000L));

        ZeroPayMpm420000.PaymentRequest payment = new ZeroPayMpm420000.PaymentRequest(
                txnUniqueNo, properties.getRequestingOrg(),
                amountKrw, 0L,
                "",   // qr_registrar_id  (field 35) — parsed from the QR at real integration
                "",   // qr_serial        (field 36)
                "",   // qr_check_char    (field 37)
                request.merchantId(), "",
                true, traceNo,
                now.toLocalDate(), now);

        ZeroPayMpm420000.Response resp = tcpTransport().submitMpm(payment);
        log.info("ZeroPay 전문 submit txnUniqueNo={} responseCode={} approved={} merchantFeeKrw={}",
                resp.txnUniqueNo(), resp.responseCode(), resp.approved(), resp.merchantFeeKrw());

        return new MpmSubmitResponse(
                resp.txnUniqueNo() == null ? txnUniqueNo : resp.txnUniqueNo().trim(),
                resp.approved() ? "00" : resp.responseCode(),
                resp.approved() ? "CAPTURED" : "DECLINED",
                txnUniqueNo,
                Instant.now().toString());
    }

    /** Lazily build (and cache) the 전문/TCP transport from config — only on the TCP path. */
    private ZeroPayTcpTransport tcpTransport() {
        ZeroPayTcpTransport t = tcpTransport;
        if (t == null) {
            synchronized (this) {
                t = tcpTransport;
                if (t == null) {
                    t = new ZeroPayTcpTransport(
                            properties.getTcpHost(), properties.getTcpPort(),
                            properties.getTcpConnectTimeoutMs(), properties.getTcpReadTimeoutMs());
                    tcpTransport = t;
                }
            }
        }
        return t;
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

    // -----------------------------------------------------------------------
    // Batch file generation (outbound) — Phase 2
    // -----------------------------------------------------------------------

    /**
     * Generates a ZP0011 (payment result) outbound file for the given KST business date.
     * Only ZP0011 is accepted; ZP0065 has its own method.
     */
    @Override
    public BatchFile generatePaymentResultFile(BatchType fileType, LocalDate businessDate) {
        if (fileType == BatchType.ZP0011) {
            List<Zp0011Record> records = batchDataPort.fetchPaymentRecords(businessDate);
            Zp0011FileFormatter fmt = new Zp0011FileFormatter(properties.getInstitutionCode());
            byte[] content = fmt.format(businessDate, records);
            BigDecimal controlSum = records.stream()
                    .map(Zp0011Record::payoutAmountKrw)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            log.info("Generated {} for {} — {} records, controlSum={}",
                    fileType, businessDate, records.size(), controlSum);
            return new BatchFile(fileType, businessDate, 1, content, records.size(), controlSum);
        }
        if (fileType == BatchType.ZP0065) {
            List<Zp0065Record> records = batchDataPort.fetchPaymentDetailRecords(businessDate);
            Zp0065FileFormatter fmt = new Zp0065FileFormatter(properties.getInstitutionCode());
            byte[] content = fmt.format(businessDate, records);
            BigDecimal controlSum = records.stream()
                    .map(Zp0065Record::payoutAmountKrw)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            log.info("Generated {} for {} — {} records, controlSum={}",
                    fileType, businessDate, records.size(), controlSum);
            return new BatchFile(fileType, businessDate, 1, content, records.size(), controlSum);
        }
        throw new ApiException(ErrorCode.VALIDATION_ERROR,
                "generatePaymentResultFile does not support fileType: " + fileType);
    }

    /**
     * Generates a ZP0021 (refund result) or ZP0066 (refund detail) outbound file.
     */
    @Override
    public BatchFile generateRefundResultFile(BatchType fileType, LocalDate businessDate) {
        if (fileType == BatchType.ZP0021) {
            List<Zp0021Record> records = batchDataPort.fetchRefundRecords(businessDate);
            Zp0021FileFormatter fmt = new Zp0021FileFormatter(properties.getInstitutionCode());
            byte[] content = fmt.format(businessDate, records);
            BigDecimal controlSum = records.stream()
                    .map(Zp0021Record::refundAmountKrw)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            log.info("Generated {} for {} — {} records, controlSum={}",
                    fileType, businessDate, records.size(), controlSum);
            return new BatchFile(fileType, businessDate, 1, content, records.size(), controlSum);
        }
        if (fileType == BatchType.ZP0066) {
            List<Zp0066Record> records = batchDataPort.fetchRefundDetailRecords(businessDate);
            Zp0066FileFormatter fmt = new Zp0066FileFormatter(properties.getInstitutionCode());
            byte[] content = fmt.format(businessDate, records);
            BigDecimal controlSum = records.stream()
                    .map(Zp0066Record::refundAmountKrw)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            log.info("Generated {} for {} — {} records, controlSum={}",
                    fileType, businessDate, records.size(), controlSum);
            return new BatchFile(fileType, businessDate, 1, content, records.size(), controlSum);
        }
        throw new ApiException(ErrorCode.VALIDATION_ERROR,
                "generateRefundResultFile does not support fileType: " + fileType);
    }

    /**
     * Generates a ZP0061 (morning, ~05:00 KST) or ZP0063 (afternoon, ~14:00 KST)
     * settlement request file.
     */
    @Override
    public BatchFile generateSettlementRequestFile(BatchType fileType, LocalDate businessDate) {
        if (fileType != BatchType.ZP0061 && fileType != BatchType.ZP0063) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "generateSettlementRequestFile does not support fileType: " + fileType);
        }
        List<ZpSettlementRequestRecord> records = batchDataPort.fetchSettlementRecords(businessDate);
        ZpSettlementRequestFormatter fmt =
                new ZpSettlementRequestFormatter(properties.getInstitutionCode());
        byte[] content = fmt.format(fileType, businessDate, records);
        BigDecimal controlSum = records.stream()
                .map(ZpSettlementRequestRecord::netAmountKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("Generated {} for {} — {} records, netControlSum={}",
                fileType, businessDate, records.size(), controlSum);
        return new BatchFile(fileType, businessDate, 1, content, records.size(), controlSum);
    }

    // -----------------------------------------------------------------------
    // Inbound file parsing / validation — Phase 2
    // -----------------------------------------------------------------------

    /**
     * Parses an inbound ZP0012, ZP0022, ZP0062, or ZP0064 file into generic BatchRecords.
     */
    @Override
    public List<BatchRecord> parseInboundFile(BatchType fileType, byte[] fileContent) {
        return switch (fileType) {
            case ZP0012 -> parseZp0012(fileContent);
            case ZP0022 -> parseZp0022(fileContent);
            case ZP0062 -> parseSettlementResult("ZP0062", fileContent);
            case ZP0064 -> parseSettlementResult("ZP0064", fileContent);
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "parseInboundFile does not support fileType: " + fileType);
        };
    }

    /**
     * Validates an inbound file (same as parseInboundFile but discards the result).
     * Throws {@link ApiException} with VALIDATION_ERROR if the file is malformed.
     */
    @Override
    public void validateInboundFile(BatchType fileType, byte[] fileContent) {
        // Validation is performed as a side effect of parsing — any structural error throws.
        parseInboundFile(fileType, fileContent);
    }

    // -----------------------------------------------------------------------
    // SFTP file transfer — Phase 2
    // -----------------------------------------------------------------------

    /**
     * Transfers an outbound batch file using the configured {@link SftpTransport}.
     * For the stub transport, this writes to the local outbound directory.
     */
    @Override
    public TransferResult transferOutbound(BatchFile batchFile, String remotePath) {
        String fileName = remotePath != null && !remotePath.isBlank()
                ? remotePath
                : defaultOutboundPath(batchFile);
        try {
            TransferResult result = sftpTransport.put(fileName, batchFile.contentBytes());
            log.info("Transferred outbound {} to {} ({} bytes)",
                    batchFile.fileType(), result.remoteFilePath(), result.remoteSizeBytes());
            return result;
        } catch (SftpTransportException e) {
            log.error("Failed to transfer outbound {}: {}", batchFile.fileType(), e.getMessage(), e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "SFTP transfer failed for " + batchFile.fileType() + ": " + e.getMessage());
        }
    }

    /**
     * Fetches an inbound file using the configured {@link SftpTransport}.
     * For the stub transport, this reads from the local inbound directory.
     */
    @Override
    public FetchResult fetchInbound(String remotePath) {
        try {
            FetchResult result = sftpTransport.get(remotePath);
            log.info("Fetched inbound {} ({} bytes)", result.remoteFilePath(), result.sizeBytes());
            return result;
        } catch (SftpTransportException e) {
            log.error("Failed to fetch inbound {}: {}", remotePath, e.getMessage(), e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "SFTP fetch failed for " + remotePath + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Merchant sync — Phase 2
    // -----------------------------------------------------------------------

    /**
     * Processes inbound merchant/QR sync records.
     *
     * <p>The actual merchant-store upsert belongs to the merchant-qr-data service.
     * Here we count record types and return a summary; the caller is responsible for
     * forwarding the records to merchant-qr-data.</p>
     */
    @Override
    public SyncResult processMerchantSync(List<BatchRecord> records, LocalDate businessDate) {
        int insertCount = 0;
        int updateCount = 0;
        int deactivateCount = 0;

        for (BatchRecord r : records) {
            String action = r.fields().getOrDefault("action", "UPDATE");
            switch (action.toUpperCase()) {
                case "INSERT", "NEW"    -> insertCount++;
                case "DELETE", "REMOVE" -> deactivateCount++;
                default                 -> updateCount++;
            }
        }

        log.info("processMerchantSync businessDate={} total={} insert={} update={} deactivate={}",
                businessDate, records.size(), insertCount, updateCount, deactivateCount);

        return new SyncResult(insertCount, updateCount, deactivateCount, false, null);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private List<BatchRecord> parseZp0012(byte[] fileContent) {
        Zp0012FileParser parser = new Zp0012FileParser();
        List<Zp0012Record> raw = parser.parse(fileContent);
        List<BatchRecord> result = new ArrayList<>(raw.size());
        for (Zp0012Record r : raw) {
            Map<String, String> fields = new HashMap<>();
            fields.put("gmeTxnId",      r.gmeTxnId());
            fields.put("zeroPayTxnRef", r.zeroPayTxnRef());
            fields.put("merchantId",    r.merchantId());
            fields.put("resultCode",    r.resultCode());
            fields.put("resultMessage", r.resultMessage());
            result.add(new BatchRecord(BatchType.ZP0012, "D", r.businessDate(), fields,
                    r.payoutAmountKrw()));
        }
        return result;
    }

    private List<BatchRecord> parseZp0022(byte[] fileContent) {
        Zp0022FileParser parser = new Zp0022FileParser();
        List<Zp0022Record> raw = parser.parse(fileContent);
        List<BatchRecord> result = new ArrayList<>(raw.size());
        for (Zp0022Record r : raw) {
            Map<String, String> fields = new HashMap<>();
            fields.put("gmeTxnId",      r.gmeTxnId());
            fields.put("zeroPayTxnRef", r.zeroPayTxnRef());
            fields.put("merchantId",    r.merchantId());
            fields.put("resultCode",    r.resultCode());
            fields.put("resultMessage", r.resultMessage());
            result.add(new BatchRecord(BatchType.ZP0022, "D", r.businessDate(), fields,
                    r.refundAmountKrw()));
        }
        return result;
    }

    private List<BatchRecord> parseSettlementResult(String typeCode, byte[] fileContent) {
        ZpSettlementResultParser parser = new ZpSettlementResultParser(typeCode);
        List<ZpSettlementResultRecord> raw = parser.parse(fileContent);
        BatchType batchType = BatchType.valueOf(typeCode);
        List<BatchRecord> result = new ArrayList<>(raw.size());
        for (ZpSettlementResultRecord r : raw) {
            Map<String, String> fields = new HashMap<>();
            fields.put("merchantId",       r.merchantId());
            fields.put("paymentCount",     String.valueOf(r.paymentCount()));
            fields.put("refundCount",      String.valueOf(r.refundCount()));
            fields.put("confirmedGrossKrw",r.confirmedGrossKrw().toPlainString());
            fields.put("confirmedRefundKrw", r.confirmedRefundKrw().toPlainString());
            fields.put("merchantFeeKrw",   r.merchantFeeKrw().toPlainString());
            fields.put("vanFeeKrw",        r.vanFeeKrw().toPlainString());
            fields.put("resultCode",       r.resultCode());
            result.add(new BatchRecord(batchType, "D", r.businessDate(), fields,
                    r.confirmedNetKrw()));
        }
        return result;
    }

    /**
     * Builds a default outbound file name when the caller does not supply one.
     * Format: {@code ZP0011_20260615_001.dat}
     */
    private static String defaultOutboundPath(BatchFile batchFile) {
        return batchFile.fileType().name()
                + "_" + batchFile.businessDate().format(DATE_FMT)
                + "_" + String.format("%03d", batchFile.sequenceNo())
                + ".dat";
    }
}
