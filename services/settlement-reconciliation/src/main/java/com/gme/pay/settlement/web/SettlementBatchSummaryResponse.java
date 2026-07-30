package com.gme.pay.settlement.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.transmission.SettlementTransmissionChannelStatus;
import com.gme.pay.settlement.transmission.SettlementTransmissionState;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One <b>persisted</b> settlement batch, as an operator or a partner reads it (GAP T4-5).
 *
 * <p>Every field comes off the {@code settlement_batches} row. Nothing here is recomputed and
 * nothing is synthesised: {@link #batchId} is the real primary key (the BFF used to build one out
 * of {@code merchantId-date-type} because no per-batch read existed), and {@link #status} is the
 * real lifecycle value (the BFF used to hardcode {@code COMPLETED}).
 *
 * <p><b>Transmission is reported separately and honestly.</b> {@link #transmissionState} answers
 * "did this file leave?" and is <em>not</em> derivable from {@link #status}: a batch can be
 * {@code RECONCILED} — a confirmation file really arrived and really tied out — while never having
 * been transmitted by GME, because no transmission channel exists (external gate). See
 * {@link SettlementTransmissionState}.
 *
 * @param batchId              the persisted batch id, e.g. {@code ZP0061-20260615-MORNING}
 * @param counterpartyId       the batch's counterparty ({@code partner_id}, e.g. ZEROPAY) — NOT a merchant
 * @param businessDate         the KST business date the batch settles
 * @param status               real {@code SettlementBatchStatus}: PENDING/GENERATED/TRANSMITTED/
 *                             RECEIVED/RECONCILED/ERROR
 * @param fileType             ZP0061 | ZP0063 | ZP0065 | ZP0066
 * @param direction            e.g. GME_TO_ZP
 * @param settlementWindow     MORNING | AFTERNOON | DETAIL
 * @param settlementType       'N' net/domestic, 'G' gross/international, null for a mixed batch
 * @param settleCurrency       settlement currency (KRW for ZeroPay)
 * @param netSettlementAmount  the booked net for the batch; null on a DETAIL batch, where a net figure
 *                             would be a category error (see SettlementBatchJobService)
 * @param merchantFeeTotal     Σ merchant fee withheld
 * @param roundingResidual     Addendum-001 residual at full precision
 * @param totalAmount          the batch's own total (the detail files' trailer total, for DETAIL batches)
 * @param recordCount          rows in the generated fixed-width file
 * @param fileChecksum         SHA-256 of the generated file
 * @param transmissionState    whether the file actually left — the T4-5 honesty axis
 * @param transmissionDetail   why it is in that state; non-null for every non-transmitted batch
 * @param transmissionChannel  the channel it went out over; null unless TRANSMITTED
 * @param transmittedAt        when it left; null unless TRANSMITTED (V013 CHECK enforces the pairing)
 * @param residualPostedAt     when the rounding residual reached revenue-ledger
 * @param errorDetail          failure detail for an ERROR batch
 * @param createdAt            row creation instant
 */
public record SettlementBatchSummaryResponse(
        String batchId,
        String counterpartyId,
        LocalDate businessDate,
        String status,
        String fileType,
        String direction,
        String settlementWindow,
        String settlementType,
        String settleCurrency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netSettlementAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal merchantFeeTotal,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal roundingResidual,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
        Integer recordCount,
        String fileChecksum,
        SettlementTransmissionState transmissionState,
        String transmissionDetail,
        String transmissionChannel,
        Instant transmittedAt,
        Instant residualPostedAt,
        String errorDetail,
        Instant createdAt) {

    /**
     * Map a persisted row onto the wire shape.
     *
     * @param channel the current channel status, used to explain a row whose
     *                {@code transmission_detail} predates the T4-5 column (a legacy row carries no
     *                reason of its own, and reporting no reason at all would leave the reader to
     *                guess). Never used to <em>upgrade</em> a state — a row that says not-sent stays
     *                not-sent regardless of whether a channel now exists.
     */
    public static SettlementBatchSummaryResponse from(SettlementBatchEntity e,
                                                      SettlementTransmissionChannelStatus channel) {
        SettlementTransmissionState state = e.getTransmissionState();
        return new SettlementBatchSummaryResponse(
                e.getBatchId(),
                e.getPartnerId(),
                e.getBusinessDate(),
                e.getStatus(),
                e.getFileType(),
                e.getDirection(),
                e.getSettlementWindow(),
                e.getSettlementType(),
                e.getSettleCurrency(),
                e.getNetSettlementAmount(),
                e.getMerchantFeeTotal(),
                e.getRoundingResidual(),
                e.getTotalAmount(),
                e.getRecordCount(),
                e.getFileChecksum(),
                state,
                transmissionDetail(e, channel, state),
                e.getTransmissionChannel(),
                e.getTransmittedAt(),
                e.getResidualPostedAt(),
                e.getErrorDetail(),
                e.getCreatedAt());
    }

    private static String transmissionDetail(SettlementBatchEntity e,
                                             SettlementTransmissionChannelStatus channel,
                                             SettlementTransmissionState state) {
        if (e.getTransmissionDetail() != null && !e.getTransmissionDetail().isBlank()) {
            return e.getTransmissionDetail();
        }
        if (state.isSent()) {
            return null;   // a real transmission needs no excuse
        }
        // Checked BEFORE the channel reason: a row whose stored state we could not interpret is a
        // data problem in its own right, and saying "no channel is configured" would hide it behind a
        // correct-sounding explanation.
        String raw = e.getTransmissionStateRaw();
        if (raw != null && !raw.isBlank() && !raw.equals(state.name())) {
            return "This batch carries the unrecognised transmission state '" + raw
                    + "', which is read as " + state + " — an uninterpretable value never means sent.";
        }
        if (channel != null && !channel.live()) {
            return channel.reason();
        }
        return "This batch has not been transmitted.";
    }
}
