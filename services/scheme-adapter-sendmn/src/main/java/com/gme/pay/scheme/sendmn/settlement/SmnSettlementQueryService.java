package com.gme.pay.scheme.sendmn.settlement;

import com.gme.pay.scheme.sendmn.dto.DailySettlementResponse;
import com.gme.pay.scheme.sendmn.fx.FxRateService;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnPaymentEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnPaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Read-only query over this adapter's own settlement record ({@code smn_payments} +
 * {@code smn_fx_rates}). Nothing here mutates state or calls SendMN.
 *
 * <p>Exists to give settlement-reconciliation the scheme leg of the SENDMN three-way tie-out
 * (GAP T2-2) without it reaching into this service's database — MSA rule: cross-service reads go
 * through an API. The hub side of that tie-out (transaction records, prefunding movements) is
 * queried from transaction-mgmt and prefunding respectively.
 *
 * <p><b>Date semantics:</b> a payment belongs to the settlement date its attempt row was
 * <em>created</em> on, in {@code gmepay.scheme.sendmn.settlement-zone} (KST by default).
 * VerifyQr → Confirm complete within seconds on this scheme, so created_at and the confirm
 * instant are the same business day; the row also carries {@code updatedAt} so a consumer can
 * re-window if SendMN's own statement ever keys on something else (open gate O4).
 */
@Service
public class SmnSettlementQueryService {

    /** Corridor local (payout) currency. */
    public static final String LOCAL_CUR_CODE = "MNT";

    /** Currency GME settles to SendMN in. */
    public static final String SETTLEMENT_CUR_CODE = "USD";

    private final SmnPaymentRepository payments;
    private final FxRateService fxRates;
    private final ZoneId settlementZone;

    public SmnSettlementQueryService(
            SmnPaymentRepository payments,
            FxRateService fxRates,
            @Value("${gmepay.scheme.sendmn.settlement-zone:Asia/Seoul}") String settlementZone) {
        this.payments = payments;
        this.fxRates = fxRates;
        this.settlementZone = ZoneId.of(settlementZone);
    }

    /**
     * Everything this adapter recorded as APPROVED (SendMN-confirmed) on {@code date}.
     *
     * @param date settlement business date in the configured settlement zone
     * @return the confirmed rows plus the newest registered rate for context (never null)
     */
    @Transactional(readOnly = true)
    public DailySettlementResponse confirmedOn(LocalDate date) {
        Instant from = date.atStartOfDay(settlementZone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(settlementZone).toInstant();

        List<DailySettlementResponse.Row> rows = payments
                .findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdAsc(
                        SmnPaymentEntity.Status.APPROVED, from, to)
                .stream()
                .map(SmnSettlementQueryService::toRow)
                .toList();

        BigDecimal latestRate = fxRates.latestRate(LOCAL_CUR_CODE, SETTLEMENT_CUR_CODE)
                .map(SmnFxRateEntity::getRate)
                .orElse(null);

        return new DailySettlementResponse(
                date.toString(),
                SmnPaymentEntity.Status.APPROVED.name(),
                LOCAL_CUR_CODE,
                SETTLEMENT_CUR_CODE,
                latestRate,
                rows.size(),
                rows);
    }

    private static DailySettlementResponse.Row toRow(SmnPaymentEntity p) {
        return new DailySettlementResponse.Row(
                p.getHubReference(),
                p.getTxTokenNo(),
                p.getMerchantId(),
                p.getLocalCurCode(),
                p.getLocalAmount(),
                p.getFxTickerNo(),
                p.getFxUsdBuyRate(),
                p.getSettlementCurCode(),
                p.getSettlementAmount(),
                p.getStatus() != null ? p.getStatus().name() : null,
                p.getPaymentNo(),
                p.getPaymentReceiptNo(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
