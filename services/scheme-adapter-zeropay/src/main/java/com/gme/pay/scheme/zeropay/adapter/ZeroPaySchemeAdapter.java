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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * ZeroPay implementation of {@link SchemeAdapter}.
 *
 * <p>@apiNote This adapter is Phase 2 pending KFTC spec (OI-SCH-01, OI-SCH-02).
 * All methods except {@link #healthCheck()} and {@link #getSchemeConfig()} throw
 * {@link UnsupportedOperationException} until then.</p>
 *
 * <p>The bean is guarded by {@code @ConditionalOnProperty(name="adapter.zeropay.enabled",
 * havingValue="true")} so it can be omitted from test slices that do not need it.</p>
 */
@Service
@ConditionalOnProperty(name = "adapter.zeropay.enabled", havingValue = "true", matchIfMissing = false)
public class ZeroPaySchemeAdapter implements SchemeAdapter {

    private final ZeroPayAdapterProperties properties;

    public ZeroPaySchemeAdapter(ZeroPayAdapterProperties properties) {
        this.properties = properties;
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
    // Phase 2 stubs
    // -----------------------------------------------------------------------

    @Override
    public MerchantIdentifier parseMerchantQR(String rawQrPayload) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - parseMerchantQR");
    }

    @Override
    public PrepareToken prepareCPM(MerchantIdentifier merchantIdentifier) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - prepareCPM");
    }

    @Override
    public CpmAuthResponse authoriseCpm(CpmAuthRequest request) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - authoriseCpm");
    }

    @Override
    public MpmSubmitResponse submitMpm(MpmSubmitRequest request) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - submitMpm");
    }

    @Override
    public SchemeResult commitPayment(PrepareToken token) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - commitPayment");
    }

    @Override
    public CancelResult cancelPayment(String gmeTxnId) {
        throw new UnsupportedOperationException(
                "TODO: ZeroPay Phase 2 - cancelPayment");
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
