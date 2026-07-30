package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.client.TransactionClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST adapter that persists transaction lifecycle changes via transaction-mgmt.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST  /v1/transactions} — create PENDING
 *   <li>{@code PATCH /v1/transactions/{ref}/status} — commit status change
 * </ul>
 *
 * <p>Base URL is read from {@code gmepay.transaction-mgmt.base-url} (default
 * {@code http://transaction-mgmt:8080}).
 */
@Component
@Primary
public class RestTransactionClient implements TransactionClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RestTransactionClient.class);

    private final RestClient restClient;

    @Autowired
    public RestTransactionClient(
            RestClient.Builder builder,
            @Value("${gmepay.transaction-mgmt.base-url:http://transaction-mgmt:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    RestTransactionClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CreateResult createPending(CreateRequest request) {
        try {
            TransactionCreatedResponse body = restClient.post()
                    .uri("/v1/transactions")
                    .body(new TransactionCreateRequest(
                            request.partnerId(),
                            request.partnerTxnRef(),
                            request.schemeId(),
                            request.direction(),
                            request.paymentMode(),
                            request.targetPayout(),
                            request.payoutCurrency(),
                            request.collectionAmount(),
                            request.collectionCurrency(),
                            request.merchantId(),
                            request.quoteId(),
                            request.merchantFeeRate(),
                            request.offerRateColl(),
                            request.crossRate(),
                            request.costRateColl(),
                            request.costRatePay(),
                            request.collectionUsd(),
                            request.payoutUsdCost(),
                            request.collectionMarginUsd(),
                            request.payoutMarginUsd(),
                            // T4-4: the merchant name the corridor resolved, so transaction-mgmt
                            // persists it (V012) instead of the receipt read finding nothing.
                            request.merchantName()))
                    .retrieve()
                    .body(TransactionCreatedResponse.class);

            if (body == null) {
                throw new PaymentException("transaction-mgmt returned empty body on create");
            }
            return new CreateResult(body.txnRef(), body.paymentId(), body.createdAt());
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "transaction-mgmt POST /v1/transactions failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "transaction-mgmt POST /v1/transactions failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void commitStatus(String txnRef, StatusPatch patch) {
        try {
            restClient.patch()
                    .uri("/v1/transactions/{ref}/status", txnRef)
                    .body(new StatusPatchRequest(
                            patch.newStatus(),
                            patch.schemeTxnRef(),
                            patch.schemeApprovalCode(),
                            patch.prefundDeductedUsd(),
                            patch.approvedAt(),
                            patch.bookedSettlementAmount(),
                            patch.settlementRoundingMode(),
                            patch.roundingResidual(),
                            patch.collectionMarginUsd(),
                            patch.payoutMarginUsd(),
                            patch.collectionUsd(),
                            patch.costRateColl(),
                            patch.costRatePay(),
                            patch.refundAmountKrw()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "transaction-mgmt PATCH /v1/transactions/" + txnRef + "/status failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "transaction-mgmt PATCH /v1/transactions/" + txnRef + "/status failed: "
                            + ex.getMessage(), ex);
        }
    }

    // ---- wire formats ----

    record TransactionCreateRequest(
            long partnerId,
            String partnerTxnRef,
            String schemeId,
            String direction,
            String paymentMode,
            BigDecimal targetPayout,
            String payoutCurrency,
            BigDecimal collectionAmount,
            String collectionCurrency,
            String merchantId,
            String quoteId,
            BigDecimal merchantFeeRate,
            // Wave-3 rate-lock pool fields (additive, IR-txn-2): nullable until the
            // executor populates them on create from the locked quote.
            BigDecimal offerRateColl,
            BigDecimal crossRate,
            BigDecimal costRateColl,
            BigDecimal costRatePay,
            BigDecimal collectionUsd,
            BigDecimal payoutUsdCost,
            BigDecimal collectionMarginUsd,
            BigDecimal payoutMarginUsd,
            // T4-4: merchant display name. The field name matches transaction-mgmt's
            // CreateTransactionRequest EXACTLY — Jackson binds by name, and a mismatch here would
            // silently POST null, i.e. reproduce the very gap this field closes.
            String merchantName
    ) {
        /** Backwards-compatible 12-arg constructor; pool + merchantName fields default null. */
        TransactionCreateRequest(
                long partnerId,
                String partnerTxnRef,
                String schemeId,
                String direction,
                String paymentMode,
                BigDecimal targetPayout,
                String payoutCurrency,
                BigDecimal collectionAmount,
                String collectionCurrency,
                String merchantId,
                String quoteId,
                BigDecimal merchantFeeRate) {
            this(partnerId, partnerTxnRef, schemeId, direction, paymentMode, targetPayout,
                    payoutCurrency, collectionAmount, collectionCurrency, merchantId, quoteId,
                    merchantFeeRate, null, null, null, null, null, null, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionCreatedResponse(
            String txnRef,
            String paymentId,
            Instant createdAt
    ) {}

    record StatusPatchRequest(
            PaymentStatus newStatus,
            String schemeTxnRef,
            String schemeApprovalCode,
            BigDecimal prefundDeductedUsd,
            Instant approvedAt,
            BigDecimal bookedSettlementAmount,
            String settlementRoundingMode,
            BigDecimal roundingResidual,
            // Wave-3 commit-margin fields (additive, FX1015 accuracy): nullable until the
            // executor carries the rate-lock pool's margins/cost rates on commit.
            BigDecimal collectionMarginUsd,
            BigDecimal payoutMarginUsd,
            BigDecimal collectionUsd,
            BigDecimal costRateColl,
            BigDecimal costRatePay,
            // T2-6: cumulative refunded KRW; field name matches transaction-mgmt's StatusPatchRequest
            // EXACTLY (Jackson binds by name — a mismatch would silently send null, which is precisely
            // the failure mode this field exists to fix).
            BigDecimal refundAmountKrw
    ) {
        /** Backwards-compatible 8-arg constructor; margin fields default null. */
        StatusPatchRequest(
                PaymentStatus newStatus,
                String schemeTxnRef,
                String schemeApprovalCode,
                BigDecimal prefundDeductedUsd,
                Instant approvedAt,
                BigDecimal bookedSettlementAmount,
                String settlementRoundingMode,
                BigDecimal roundingResidual) {
            this(newStatus, schemeTxnRef, schemeApprovalCode, prefundDeductedUsd, approvedAt,
                    bookedSettlementAmount, settlementRoundingMode, roundingResidual,
                    null, null, null, null, null, null);
        }
    }

    /**
     * T2-6: reads the refund basis from {@code GET /v1/transactions/{txnRef}}.
     *
     * <p>{@code sendAmount}/{@code sendCcy} on the response ARE the collection amount and currency (the
     * V003 create path assigns {@code sendAmount = collectionAmount}), and {@code refundAmountKrw} is the
     * cumulative refunded total.
     *
     * <p>Fails SOFT to {@link Optional#empty()} — including on 404 — because the caller
     * ({@code PaymentOrchestrator.refundPayment}) is what decides the consequence: a full refund proceeds
     * unchanged, a partial refund is refused with {@code REFUND_BASIS_UNAVAILABLE}. Throwing here would
     * break the full-refund path on a transaction-mgmt hiccup, which this gap is not about.
     */
    @Override
    public java.util.Optional<RefundBasis> findRefundBasis(String txnRef) {
        try {
            TransactionRefundBasisResponse body = restClient.get()
                    .uri("/v1/transactions/{ref}", txnRef)
                    .retrieve()
                    .body(TransactionRefundBasisResponse.class);
            if (body == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new RefundBasis(
                    body.txnRef() != null ? body.txnRef() : txnRef,
                    body.status(),
                    body.sendAmount(),
                    body.sendCcy(),
                    body.prefundingDeductedUsd(),
                    body.refundAmountKrw()));
        } catch (RuntimeException ex) {
            log.warn("transaction-mgmt GET /v1/transactions/{} unavailable for the refund basis: {}",
                    txnRef, ex.toString());
            return java.util.Optional.empty();
        }
    }

    /** The refund-relevant projection of transaction-mgmt's {@code TransactionResponse}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionRefundBasisResponse(
            String txnRef,
            String status,
            BigDecimal sendAmount,
            String sendCcy,
            BigDecimal prefundingDeductedUsd,
            BigDecimal refundAmountKrw
    ) {}

    // ---- day-close / FX-exposure read (gap T2-5) ----

    /** Page size requested from transaction-mgmt; its documented contract maximum. */
    static final int DAILY_PAGE_SIZE = 500;

    /**
     * Paging guard. At {@value #DAILY_PAGE_SIZE} rows a page this allows 250,000 approved transactions on one
     * day; more than that is a bug or an attack, not a business day, and looping forever against a misbehaving
     * upstream would hang the day-close.
     */
    static final int DAILY_MAX_PAGES = 500;

    /**
     * Reads one business date's APPROVED transactions, paged (gap T2-5).
     *
     * <p><b>Fails HARD, unlike {@link #findRefundBasis}.</b> A transport failure — including one part-way
     * through paging — throws, and the partial pages are discarded. Half a day of transactions would produce
     * day-close variances indistinguishable from real ones while the report looked successful; the caller marks
     * the leg UNAVAILABLE instead, which is visible.
     *
     * <p>The {@code status=APPROVED} filter and the date window are both applied server-side. Rows whose scheme
     * or currency is missing are KEPT and grouped under an explicit {@code UNKNOWN}/{@code ?} label — dropping
     * them would silently shrink the tie-out, which is the failure mode a reconciliation exists to prevent.
     */
    @Override
    public java.util.List<DailyTransaction> findApprovedForDate(java.time.LocalDate businessDate) {
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate required");
        }
        java.util.List<DailyTransaction> out = new java.util.ArrayList<>();
        int page = 0;
        while (page < DAILY_MAX_PAGES) {
            TransactionPageResponse body;
            try {
                final int currentPage = page;
                body = restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/v1/transactions")
                                .queryParam("from", businessDate.toString())
                                .queryParam("to", businessDate.toString())
                                .queryParam("status", "APPROVED")
                                .queryParam("page", currentPage)
                                .queryParam("size", DAILY_PAGE_SIZE)
                                .build())
                        .retrieve()
                        .body(TransactionPageResponse.class);
            } catch (RuntimeException ex) {
                throw new PaymentException("transaction-mgmt GET /v1/transactions for " + businessDate
                        + " failed on page " + page + "; the day's transaction leg is DISCARDED rather than "
                        + "reported partial: " + ex, ex);
            }
            if (body == null || body.content() == null || body.content().isEmpty()) {
                break;
            }
            for (DailyTransactionResponse r : body.content()) {
                out.add(new DailyTransaction(
                        r.txnRef(), r.qrSchemeId(), r.sendCcy(), r.sendAmount(), r.targetCcy(),
                        r.targetPayout(), r.prefundingDeductedUsd(), r.appliedFxRate(),
                        r.refundAmountKrw(), r.approvedAt()));
            }
            page++;
            if ((long) page * DAILY_PAGE_SIZE >= body.totalElements()) {
                break;
            }
        }
        return out;
    }

    /** One page of transaction-mgmt's paged {@code GET /v1/transactions}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionPageResponse(java.util.List<DailyTransactionResponse> content, long totalElements) {}

    /** The day-close projection of transaction-mgmt's {@code TransactionResponse}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DailyTransactionResponse(
            String txnRef,
            String qrSchemeId,
            String sendCcy,
            BigDecimal sendAmount,
            String targetCcy,
            BigDecimal targetPayout,
            BigDecimal prefundingDeductedUsd,
            BigDecimal appliedFxRate,
            BigDecimal refundAmountKrw,
            Instant approvedAt
    ) {}
}
