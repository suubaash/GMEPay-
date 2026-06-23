package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical read DTO for one gross-merchant-fee row
 * ({@code merchant_fee_schedule}, V032) — the configurable gross fee rate a
 * merchant pays on a QR transaction, by (scheme × merchant type). The INPUT to
 * the V031 commission split (gross = payout × {@code merchantFeePct}).
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the ROW; SCD-6 mints fresh rows on
 *       every save (audit reference, not a stable identifier).</li>
 *   <li>{@code schemeId} — the scheme CODE this row prices (e.g. {@code "ZEROPAY"}).</li>
 *   <li>{@code merchantType} — merchant category (e.g. {@code "RETAIL"},
 *       {@code "FOOD_BEVERAGE"}); {@code null} = the scheme's default rate.</li>
 *   <li>{@code merchantFeePct} — gross fee rate, decimal STRING (NUMERIC(7,4),
 *       e.g. {@code "0.0080"} = 0.80%).</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * same contract as {@link SchemeCommissionShareView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MerchantFeeScheduleView(
        Long id,
        String schemeId,
        String merchantType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal merchantFeePct,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
