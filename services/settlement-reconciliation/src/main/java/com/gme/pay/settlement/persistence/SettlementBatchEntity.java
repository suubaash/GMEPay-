package com.gme.pay.settlement.persistence;

import com.gme.pay.settlement.transmission.SettlementTransmissionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping {@code settlement_batches}.
 *
 * <p>This is the settlement-reconciliation service's private persistence row for a ZP006x
 * batch lifecycle entry. Owned exclusively by this service per MSA rules.
 */
@Entity
@Table(name = "settlement_batches")
public class SettlementBatchEntity {

    @Id
    @Column(name = "batch_id", length = 64, nullable = false)
    private String batchId;

    @Column(name = "partner_id", length = 32, nullable = false)
    private String partnerId;

    /**
     * BIGINT surrogate partner FK introduced by V004 (Slice 1 schism resolution, Expand
     * phase per ADR-013). Nullable for now: application writes from Slice 2 onwards
     * will populate it alongside {@link #partnerId}; a future Contract migration drops
     * the String column and promotes this one. Until then, leaving it null on legacy
     * writes is intentional — the read path still uses the String column.
     */
    @Column(name = "partner_id_new")
    private Long partnerIdNew;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "total_amount", precision = 20, scale = 8)
    private BigDecimal totalAmount;

    @Column(name = "total_currency", length = 3)
    private String totalCurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // ----- Outbound settlement-file lifecycle (V006) -----

    @Column(name = "file_type", length = 8)
    private String fileType;                 // 'ZP0061' | 'ZP0063' | 'ZP0065' | 'ZP0066'

    @Column(name = "direction", length = 16)
    private String direction;                // 'GME_TO_ZP'

    @Column(name = "settlement_window", length = 16)
    private String settlementWindow;         // 'MORNING' | 'AFTERNOON' | 'DETAIL'

    @Column(name = "settlement_type", length = 1)
    private String settlementType;           // 'N' | 'G' | null (mixed multi-merchant batch)

    @Column(name = "net_settlement_amount", precision = 20, scale = 4)
    private BigDecimal netSettlementAmount;

    @Column(name = "merchant_fee_total", precision = 20, scale = 4)
    private BigDecimal merchantFeeTotal;

    @Column(name = "rounding_residual", precision = 20, scale = 8)
    private BigDecimal roundingResidual;     // Addendum-001 residual (full precision)

    @Column(name = "settlement_rounding_mode", length = 16)
    private String settlementRoundingMode;

    @Column(name = "settle_currency", length = 3)
    private String settleCurrency;

    @Column(name = "file_checksum", length = 64)
    private String fileChecksum;             // SHA-256 hex

    @Column(name = "record_count")
    private Integer recordCount;

    /**
     * When the file demonstrably left the platform. Never {@code null} without
     * {@link #transmissionState} being {@link SettlementTransmissionState#TRANSMITTED}, and vice
     * versa — enforced by {@link #markTransmitted} being the only writer (and by the V013 CHECK
     * constraint, so the invariant survives a hand-written UPDATE too). There is deliberately no
     * public {@code setTransmittedAt}: a bare timestamp setter is how a batch that was never sent
     * ends up looking sent.
     */
    @Column(name = "transmitted_at")
    private Instant transmittedAt;

    /**
     * GAP T4-5: whether this file actually left, as its own axis independent of {@link #status}.
     * Stored as a VARCHAR for H2/PG portability, like {@code status}. Defaults to
     * {@code NOT_TRANSMITTED} for every new row, so a row can never be silently absent an answer.
     */
    @Column(name = "transmission_state", length = 40, nullable = false)
    private String transmissionState = SettlementTransmissionState.NOT_TRANSMITTED.name();

    /** The channel the file went out over. Null whenever {@link #transmissionState} is not TRANSMITTED. */
    @Column(name = "transmission_channel", length = 64)
    private String transmissionChannel;

    /**
     * Why this batch is in its transmission state — for the two non-transmitted states, the reason
     * an operator or partner reads (e.g. "no channel configured, externally gated"). Kept on the row
     * rather than derived at read time so the batch carries its own provenance.
     */
    @Column(name = "transmission_detail", length = 512)
    private String transmissionDetail;

    @Column(name = "error_detail", length = 1024)
    private String errorDetail;

    /**
     * Set once the batch's {@link #roundingResidual} has been POSTed to revenue-ledger
     * ({@code /v1/journals/rounding-residual}). Once-per-batch guard (V009): a recon re-run that finds
     * this non-null never re-posts. Null = not yet posted.
     */
    @Column(name = "residual_posted_at")
    private Instant residualPostedAt;

    public SettlementBatchEntity() {
        // JPA no-arg constructor
    }

    public SettlementBatchEntity(String batchId,
                                 String partnerId,
                                 LocalDate businessDate,
                                 String status,
                                 BigDecimal totalAmount,
                                 String totalCurrency,
                                 Instant createdAt) {
        this.batchId = batchId;
        this.partnerId = partnerId;
        this.businessDate = businessDate;
        this.status = status;
        this.totalAmount = totalAmount;
        this.totalCurrency = totalCurrency;
        this.createdAt = createdAt;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(String partnerId) {
        this.partnerId = partnerId;
    }

    /** BIGINT surrogate FK (V004). See {@link #partnerIdNew} for Expand-phase semantics. */
    public Long getPartnerIdNew() {
        return partnerIdNew;
    }

    public void setPartnerIdNew(Long partnerIdNew) {
        this.partnerIdNew = partnerIdNew;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getTotalCurrency() {
        return totalCurrency;
    }

    public void setTotalCurrency(String totalCurrency) {
        this.totalCurrency = totalCurrency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getSettlementWindow() { return settlementWindow; }
    public void setSettlementWindow(String settlementWindow) { this.settlementWindow = settlementWindow; }

    public String getSettlementType() { return settlementType; }
    public void setSettlementType(String settlementType) { this.settlementType = settlementType; }

    public BigDecimal getNetSettlementAmount() { return netSettlementAmount; }
    public void setNetSettlementAmount(BigDecimal netSettlementAmount) { this.netSettlementAmount = netSettlementAmount; }

    public BigDecimal getMerchantFeeTotal() { return merchantFeeTotal; }
    public void setMerchantFeeTotal(BigDecimal merchantFeeTotal) { this.merchantFeeTotal = merchantFeeTotal; }

    public BigDecimal getRoundingResidual() { return roundingResidual; }
    public void setRoundingResidual(BigDecimal roundingResidual) { this.roundingResidual = roundingResidual; }

    public String getSettlementRoundingMode() { return settlementRoundingMode; }
    public void setSettlementRoundingMode(String settlementRoundingMode) { this.settlementRoundingMode = settlementRoundingMode; }

    public String getSettleCurrency() { return settleCurrency; }
    public void setSettleCurrency(String settleCurrency) { this.settleCurrency = settleCurrency; }

    public String getFileChecksum() { return fileChecksum; }
    public void setFileChecksum(String fileChecksum) { this.fileChecksum = fileChecksum; }

    public Integer getRecordCount() { return recordCount; }
    public void setRecordCount(Integer recordCount) { this.recordCount = recordCount; }

    public Instant getTransmittedAt() { return transmittedAt; }

    /**
     * The persisted transmission state, resolved to the enum. An unknown/absent value resolves to
     * {@link SettlementTransmissionState#NOT_TRANSMITTED} — the safe direction: a value we cannot
     * interpret never means "sent".
     */
    public SettlementTransmissionState getTransmissionState() {
        if (transmissionState == null) {
            return SettlementTransmissionState.NOT_TRANSMITTED;
        }
        try {
            return SettlementTransmissionState.valueOf(transmissionState);
        } catch (IllegalArgumentException e) {
            return SettlementTransmissionState.NOT_TRANSMITTED;
        }
    }

    /** The raw persisted value, for reporting an unrecognised state verbatim rather than hiding it. */
    public String getTransmissionStateRaw() { return transmissionState; }

    public String getTransmissionChannel() { return transmissionChannel; }

    public String getTransmissionDetail() { return transmissionDetail; }

    /**
     * Record that this batch's file was genuinely handed to the scheme. <b>The only way to set
     * {@code transmitted_at} or reach {@link SettlementTransmissionState#TRANSMITTED}.</b>
     *
     * <p>Not public API for general use: call it through
     * {@code SettlementTransmissionRecorder#recordTransmitted}, which refuses unless
     * {@code SettlementTransmissionChannelRegistry} shows a real configured channel. Both arguments
     * are mandatory — a transmission with no instant or no named channel is not evidence of anything.
     */
    public void markTransmitted(Instant at, String channelId) {
        if (at == null) {
            throw new IllegalArgumentException(
                    "transmission instant is required for batch " + batchId
                            + " — a TRANSMITTED batch with no timestamp is not evidence of a transmission");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException(
                    "transmission channel id is required for batch " + batchId
                            + " — a TRANSMITTED batch must name the channel it went out over");
        }
        this.transmittedAt = at;
        this.transmissionChannel = channelId;
        this.transmissionState = SettlementTransmissionState.TRANSMITTED.name();
        this.transmissionDetail = null;
    }

    /**
     * Record that this batch's file has NOT left — either not yet, or (the standing case) because
     * this deployment has no transmission channel at all. Clears {@code transmitted_at} and the
     * channel, so a batch cannot carry a stale send-timestamp alongside a not-sent state.
     *
     * @param state  {@link SettlementTransmissionState#NOT_TRANSMITTED},
     *               {@link SettlementTransmissionState#NOT_TRANSMITTED_CHANNEL_UNAVAILABLE} or
     *               {@link SettlementTransmissionState#TRANSMISSION_FAILED}
     * @param detail why — surfaced verbatim to operators and partners
     */
    public void markNotTransmitted(SettlementTransmissionState state, String detail) {
        if (state == null || state.isSent()) {
            throw new IllegalArgumentException(
                    "markNotTransmitted requires a non-sent state, got " + state
                            + " for batch " + batchId + " — use markTransmitted for a real transmission");
        }
        this.transmittedAt = null;
        this.transmissionChannel = null;
        this.transmissionState = state.name();
        this.transmissionDetail = detail;
    }

    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }

    public Instant getResidualPostedAt() { return residualPostedAt; }
    public void setResidualPostedAt(Instant residualPostedAt) { this.residualPostedAt = residualPostedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SettlementBatchEntity that)) return false;
        return Objects.equals(batchId, that.batchId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchId);
    }
}
