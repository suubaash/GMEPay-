package com.gme.pay.reporting.hometax;

import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import com.gme.pay.contracts.VatTreatment;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.service.TransactionClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Application service for UC-04-04: monthly overseas-merchant-fee tax invoice.
 *
 * <h2>Aggregation logic</h2>
 * <ol>
 *   <li>Fetch all committed transactions for the calendar month via
 *       {@link TransactionClient} (MSA — never the DB).</li>
 *   <li>Keep only OUTBOUND KRW transactions (overseas-customer payments to a
 *       merchant), grouped by {@code partnerId}.</li>
 *   <li>Compute merchant fee = monthly KRW total × {@code feeRate} read from
 *       the merchant's regulatory config (V029).</li>
 *   <li>Apply VAT per {@code vat_treatment}:
 *     <ul>
 *       <li>{@code ZERO_RATED_EXPORT} — VAT = 0; invoice = fee.</li>
 *       <li>{@code STANDARD} — VAT = fee × 10 %; invoice = fee × 1.10.</li>
 *       <li>{@code EXEMPT} — VAT = 0; invoice = fee.</li>
 *     </ul>
 *   </li>
 *   <li>GME's own 2 % FX spread and KRW 500 per-transaction levy are GME
 *       revenue — they are NOT invoiced to the merchant and are excluded
 *       before the fee base is computed.</li>
 * </ol>
 *
 * <h2>Config keys (V029 partner_regulatory_config)</h2>
 * <ul>
 *   <li>{@code vat_treatment} — VatTreatment enum: ZERO_RATED_EXPORT |
 *       STANDARD | EXEMPT</li>
 *   <li>{@code fee_rate} — merchant fee rate, decimal string (e.g. "0.0150"
 *       = 1.5 %)</li>
 *   <li>{@code hometaxIssuerCertId} — lib-vault document id for the NTS mTLS
 *       cert, echoed into the invoice request.</li>
 * </ul>
 */
@Service
public class HometaxInvoiceService {

    /** KST = UTC+9. All KST timestamps, period boundaries in KST. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** Standard KR VAT rate. */
    static final BigDecimal VAT_RATE = new BigDecimal("0.10");

    /** GME FX spread excluded from the invoiceable fee base. */
    static final BigDecimal GME_FX_SPREAD = new BigDecimal("0.02");

    /** GME per-transaction KRW levy excluded from the invoiceable fee base. */
    static final BigDecimal GME_PER_TXN_KRW = new BigDecimal("500");

    private static final int SCALE = 0;   // KRW — no minor units
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final TransactionClient transactionClient;
    private final RegulatoryConfigClient regulatoryConfigClient;
    private final HometaxClient hometaxClient;

    public HometaxInvoiceService(
            TransactionClient transactionClient,
            RegulatoryConfigClient regulatoryConfigClient,
            HometaxClient hometaxClient) {
        this.transactionClient = Objects.requireNonNull(transactionClient);
        this.regulatoryConfigClient = Objects.requireNonNull(regulatoryConfigClient);
        this.hometaxClient = Objects.requireNonNull(hometaxClient);
    }

    /**
     * Builds monthly invoice summaries for all overseas merchants that had
     * OUTBOUND KRW transactions in the given period.
     *
     * <p>This method does NOT submit to Hometax; call
     * {@link #submitInvoicesForPeriod} to both aggregate and submit.
     *
     * @param period the calendar month to aggregate (KST)
     * @param feeRate decimal fee rate applied to the KRW volume (e.g. 0.0150)
     * @param partnerCode the merchant's business code used to look up
     *                    regulatory config (vat_treatment)
     * @return aggregated invoice summary for the merchant
     */
    public MerchantInvoiceSummary aggregateForMerchant(
            YearMonth period, BigDecimal feeRate, String partnerCode) {

        Objects.requireNonNull(period, "period must not be null");
        Objects.requireNonNull(feeRate, "feeRate must not be null");
        Objects.requireNonNull(partnerCode, "partnerCode must not be null");

        LocalDate from = period.atDay(1);
        LocalDate to = period.atEndOfMonth();

        List<CommittedTransaction> txns = transactionClient.fetchCommitted(from, to, null);

        // Sum OUTBOUND KRW gross collection amounts (the fee base).
        // GME FX spread (2%) and KRW 500/txn are revenue, not invoiceable —
        // we subtract them from each transaction's gross amount before summing.
        BigDecimal totalKrw = BigDecimal.ZERO;
        long merchantPartnerId = -1L;
        int txnCount = 0;

        for (CommittedTransaction txn : txns) {
            if (!isOutboundKrw(txn)) {
                continue;
            }
            merchantPartnerId = txn.getPartnerId();
            BigDecimal gross = txn.getCollectionAmount();
            // Exclude GME spread: invoiceableBase = gross / (1 + GME_FX_SPREAD)
            BigDecimal afterSpread = gross.divide(
                    BigDecimal.ONE.add(GME_FX_SPREAD), 10, RoundingMode.HALF_UP);
            // Exclude per-txn KRW 500 levy
            BigDecimal net = afterSpread.subtract(GME_PER_TXN_KRW);
            if (net.compareTo(BigDecimal.ZERO) > 0) {
                totalKrw = totalKrw.add(net);
            }
            txnCount++;
        }

        if (txnCount == 0) {
            // No OUTBOUND KRW transactions — zero invoice
            return new MerchantInvoiceSummary(
                    merchantPartnerId < 0 ? 0L : merchantPartnerId,
                    period,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    VatTreatment.ZERO_RATED_EXPORT);
        }

        totalKrw = totalKrw.setScale(SCALE, ROUNDING);

        PartnerRegulatoryConfigView config = regulatoryConfigClient.getRegulatory(partnerCode);
        VatTreatment vatTreatment = config != null && config.vatTreatment() != null
                ? config.vatTreatment() : VatTreatment.ZERO_RATED_EXPORT;

        BigDecimal feeAmount = totalKrw.multiply(feeRate).setScale(SCALE, ROUNDING);
        BigDecimal vatAmount = computeVat(feeAmount, vatTreatment);
        BigDecimal invoiceAmount = feeAmount.add(vatAmount);

        return new MerchantInvoiceSummary(
                merchantPartnerId,
                period,
                totalKrw,
                feeAmount,
                vatAmount,
                invoiceAmount,
                vatTreatment);
    }

    /**
     * Aggregates monthly invoice for one merchant and submits it to Hometax.
     *
     * @param period      billing period
     * @param feeRate     decimal fee rate
     * @param partnerCode merchant's business code (for regulatory config lookup)
     * @param certId      Hometax issuer cert id (gmepay.hometax.cert-id)
     * @return the NTS response containing invoiceId and NTS confirmation
     */
    public HometaxInvoiceResponse submitInvoicesForPeriod(
            YearMonth period, BigDecimal feeRate, String partnerCode, String certId) {

        MerchantInvoiceSummary summary = aggregateForMerchant(period, feeRate, partnerCode);

        HometaxInvoiceRequest request = new HometaxInvoiceRequest(
                certId,
                summary.getMerchantPartnerId(),
                summary.getPeriod(),
                summary.getFeeAmount(),
                summary.getVatAmount(),
                summary.getInvoiceAmount(),
                summary.getVatTreatment().name());

        return hometaxClient.submitInvoice(request);
    }

    /**
     * Computes the VAT amount from the supply (fee) amount per the partner's
     * VAT treatment:
     * <ul>
     *   <li>STANDARD — 10 % of supply amount, rounded to KRW (no minor units).</li>
     *   <li>ZERO_RATED_EXPORT — 0 (zero-rated export; 영세율).</li>
     *   <li>EXEMPT — 0 (exempt; 면세).</li>
     * </ul>
     */
    public static BigDecimal computeVat(BigDecimal feeAmount, VatTreatment treatment) {
        Objects.requireNonNull(feeAmount, "feeAmount must not be null");
        Objects.requireNonNull(treatment, "treatment must not be null");
        return switch (treatment) {
            case STANDARD -> feeAmount.multiply(VAT_RATE).setScale(SCALE, ROUNDING);
            case ZERO_RATED_EXPORT, EXEMPT -> BigDecimal.ZERO.setScale(SCALE);
        };
    }

    /**
     * Returns true if the transaction is an OUTBOUND collection in KRW.
     * DOMESTIC and HUB are excluded; same-currency short-circuit DOMESTIC
     * transactions are also excluded.
     */
    private static boolean isOutboundKrw(CommittedTransaction txn) {
        return txn.getDirection() == com.gme.pay.reporting.domain.TransactionDirection.OUTBOUND
                && "KRW".equals(txn.getCollectionCcy())
                && !txn.isSameCcyShortcircuit();
    }
}
