package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.contracts.PrefundingDeductionHistoryView;
import com.gme.pay.contracts.PrefundingReleaseRequest;
import com.gme.pay.contracts.PrefundingReserveRequest;
import com.gme.pay.contracts.PrefundingReserveResponse;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.payment.domain.CumulativeLimitExceededException;
import com.gme.pay.payment.domain.InsufficientPrefundingException;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.PrefundingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

/**
 * REST adapter that calls the Prefunding service for atomic deduct / reverse operations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /v1/prefunding/{partner}/deduct}
 *   <li>{@code POST /v1/prefunding/{partner}/reverse}
 * </ul>
 *
 * <p>Base URL is read from {@code gmepay.prefunding.base-url} (default
 * {@code http://prefunding:8080}). A 402 Payment Required response is mapped to
 * {@link InsufficientPrefundingException} so the orchestrator can short-circuit before
 * touching the scheme.
 *
 * <p><b>Internal auth (T0-5 / CISO#6):</b> prefunding's entire balance API is now behind the
 * service-to-service internal-auth gate ({@code com.gme.pay.internalauth}), so payment-executor —
 * a trusted in-cluster caller — presents the shared secret from
 * {@code gmepay.internal-auth.secret} in the {@code X-Gme-Internal} header on every call. A blank
 * secret sends no header (local dev against an ungated stub); against a real, gated prefunding it
 * yields 401 on every call, which is the intended fail-closed outcome of a missing
 * {@code GMEPAY_INTERNAL_AUTH_SECRET} rather than a silent bypass.
 */
@Component
@Primary
public class RestPrefundingClient implements PrefundingClient {

    private static final Logger log = LoggerFactory.getLogger(RestPrefundingClient.class);

    private final RestClient restClient;

    @Autowired
    public RestPrefundingClient(
            RestClient.Builder builder,
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret) {
        RestClient.Builder b = builder.baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.internal-auth.secret is blank — calls to prefunding will carry no {} "
                    + "header and a gated prefunding will refuse them (401). Set "
                    + "GMEPAY_INTERNAL_AUTH_SECRET.", InternalAuthHeaders.INTERNAL_TOKEN);
        }
        this.restClient = b.build();
    }

    RestPrefundingClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd) {
        try {
            DeductResponse body = restClient.post()
                    .uri("/v1/prefunding/{partner}/deduct", partnerId)
                    .body(new DeductRequest(txnRef, amountUsd))
                    .retrieve()
                    .body(DeductResponse.class);

            if (body == null) {
                throw new PaymentException("prefunding returned empty body for deduct " + txnRef);
            }
            // A 2xx means the FULL requested amount was debited (anything short answers 402),
            // so deductedUsd is the requested amount; the wire body only echoes the balance.
            return new DeductionResult(amountUsd, body.balance());
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == HttpStatus.PAYMENT_REQUIRED.value()) {
                throw new InsufficientPrefundingException(
                        nonNull(parseAvailable(ex)), nonNull(amountUsd));
            }
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/deduct failed: "
                            + status + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/deduct failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public ReverseResult reverse(long partnerId, String txnRef) {
        try {
            ReverseResponse body = restClient.post()
                    .uri("/v1/prefunding/{partner}/reverse", partnerId)
                    .body(new ReverseRequest(txnRef))
                    .retrieve()
                    .body(ReverseResponse.class);
            if (body == null) {
                // Tolerate an empty body (older/no-content responses): nothing recorded to reverse.
                return new ReverseResult(BigDecimal.ZERO, null);
            }
            return new ReverseResult(nonNull(body.reversedUsd()), body.balance());
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/reverse failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/reverse failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public ReservationResult reserve(long partnerId, String txnRef, BigDecimal amountUsd) {
        try {
            ReserveResponse body = restClient.post()
                    .uri("/v1/prefunding/{partner}/reserve", partnerId)
                    .body(new ReserveRequest(txnRef, amountUsd))
                    .retrieve()
                    .body(ReserveResponse.class);
            if (body == null) {
                throw new PaymentException("prefunding returned empty body for reserve " + txnRef);
            }
            return new ReservationResult(nonNull(body.reservedUsd()), body.available(), body.balance());
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == HttpStatus.PAYMENT_REQUIRED.value()) {
                throw new InsufficientPrefundingException(
                        nonNull(parseAvailable(ex)), nonNull(amountUsd));
            }
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/reserve failed: "
                            + status + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/reserve failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public CaptureResult capture(long partnerId, String txnRef) {
        try {
            CaptureResponse body = restClient.post()
                    .uri("/v1/prefunding/{partner}/capture", partnerId)
                    .body(new ReserveRequest(txnRef, null))
                    .retrieve()
                    .body(CaptureResponse.class);
            if (body == null) {
                throw new PaymentException("prefunding returned empty body for capture " + txnRef);
            }
            return new CaptureResult(nonNull(body.capturedUsd()), body.balance());
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/capture failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/capture failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public ReleaseResult release(long partnerId, String txnRef) {
        try {
            ReleaseResponse body = restClient.post()
                    .uri("/v1/prefunding/{partner}/release", partnerId)
                    .body(new ReserveRequest(txnRef, null))
                    .retrieve()
                    .body(ReleaseResponse.class);
            if (body == null) {
                return new ReleaseResult(BigDecimal.ZERO, null);
            }
            return new ReleaseResult(nonNull(body.releasedUsd()), body.balance());
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/release failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/release failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public void chargeCumulative(long partnerId, String txnRef, BigDecimal amountUsd,
                                 BigDecimal dailyCapUsd, BigDecimal monthlyCapUsd, BigDecimal annualCapUsd,
                                 Integer dailyTxnCountLimit) {
        try {
            restClient.post()
                    .uri("/v1/prefunding/{partner}/cumulative-charge", partnerId)
                    .body(new CumulativeChargeRequest(txnRef, amountUsd, dailyCapUsd, monthlyCapUsd,
                            annualCapUsd, dailyTxnCountLimit))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            if (body != null && body.contains("CUMULATIVE_LIMIT_EXCEEDED")) {
                throw new CumulativeLimitExceededException(
                        "partner " + partnerId + " txn " + txnRef + " breaches a cumulative cap: " + body);
            }
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/cumulative-charge failed: "
                            + ex.getStatusCode() + " " + body, ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /v1/prefunding/" + partnerId + "/cumulative-charge failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public BalanceSnapshot balance(String partnerCode) {
        try {
            BalanceResponse body = restClient.get()
                    .uri("/v1/prefunding/{partnerCode}/balance", partnerCode)
                    .retrieve()
                    .body(BalanceResponse.class);
            if (body == null) {
                throw new PaymentException("prefunding returned empty body for balance " + partnerCode);
            }
            return new BalanceSnapshot(body.balance(), body.threshold(), body.currency());
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "prefunding GET /v1/prefunding/" + partnerCode + "/balance failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding GET /v1/prefunding/" + partnerCode + "/balance failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public PrefundingDeductionHistoryView deductionHistory(String partnerCode, int limit) {
        try {
            PrefundingDeductionHistoryView body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/prefunding/{partnerCode}/deductions")
                            .queryParam("limit", limit)
                            .build(partnerCode))
                    .retrieve()
                    .body(PrefundingDeductionHistoryView.class);
            if (body == null) {
                throw new PaymentException(
                        "prefunding returned empty body for deductions " + partnerCode);
            }
            return body;
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "prefunding GET /v1/prefunding/" + partnerCode + "/deductions failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding GET /v1/prefunding/" + partnerCode + "/deductions failed: "
                            + ex.getMessage(), ex);
        }
    }

    /** Page size requested from the movements endpoint (its own maximum is 1000). */
    static final int MOVEMENTS_PAGE_SIZE = 500;

    /**
     * Paging guard. At {@value #MOVEMENTS_PAGE_SIZE} rows a page this allows 100,000 movements for one partner
     * in one window; more than that is a bug, not a business day, and looping forever against a misbehaving
     * upstream would hang the day-close.
     */
    static final int MOVEMENTS_MAX_PAGES = 200;

    /**
     * The balance-moving entry types. Holds ({@code RESERVE}/{@code RELEASE}) and the AML counters
     * ({@code CUM_CHARGE}/{@code CUM_REVERSE}) are excluded because they never move float, so including them
     * would add zero-delta rows to the netting for no gain. Same selection settlement-reconciliation's leg (b)
     * makes, so the two controls net the same set of entries.
     */
    static final String MOVEMENT_TYPES = "DEBIT,CREDIT,CAPTURE";

    /**
     * Reads one partner's signed float movements over {@code [from, to)}, paging until the window is exhausted
     * (gap T2-5, over the endpoint T2-8 added).
     *
     * <p><b>Fails HARD, and discards partial pages.</b> Half a window of movements would produce day-close
     * variances indistinguishable from real ones while the report looked successful. The caller marks the leg
     * UNAVAILABLE instead, which is visible.
     *
     * <p>Completeness is asserted, not assumed: the loop follows {@code hasNext} and cross-checks the row count
     * against {@code totalElements}, so a truncated read cannot be mistaken for a complete one.
     */
    @Override
    public java.util.List<FloatMovement> movements(String partnerCode, java.time.Instant from,
                                                  java.time.Instant to) {
        if (partnerCode == null || partnerCode.isBlank()) {
            throw new IllegalArgumentException("partnerCode required");
        }
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("movements window must be from < to, got from=" + from
                    + " to=" + to);
        }
        java.util.List<FloatMovement> out = new java.util.ArrayList<>();
        long totalElements = -1;
        int page = 0;
        while (page < MOVEMENTS_MAX_PAGES) {
            MovementsPageResponse body;
            try {
                final int currentPage = page;
                body = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/v1/prefunding/{partnerCode}/movements")
                                .queryParam("from", from.toString())
                                .queryParam("to", to.toString())
                                .queryParam("types", MOVEMENT_TYPES)
                                .queryParam("page", currentPage)
                                .queryParam("size", MOVEMENTS_PAGE_SIZE)
                                .build(partnerCode))
                        .retrieve()
                        .body(MovementsPageResponse.class);
            } catch (RuntimeException ex) {
                throw new PaymentException("prefunding GET /v1/prefunding/" + partnerCode
                        + "/movements failed on page " + page + "; the window is DISCARDED rather than "
                        + "reported partial: " + ex, ex);
            }
            if (body == null) {
                throw new PaymentException("prefunding returned an empty body for movements of "
                        + partnerCode + " page " + page);
            }
            totalElements = body.totalElements();
            if (body.movements() != null) {
                for (MovementView m : body.movements()) {
                    out.add(new FloatMovement(m.txnRef(), m.entryType(), m.balanceDeltaUsd(), m.at()));
                }
            }
            page++;
            if (!body.hasNext()) {
                break;
            }
        }
        if (totalElements >= 0 && out.size() != totalElements) {
            // The endpoint's own count disagrees with what we assembled: either paging stopped early (the
            // MAX_PAGES guard) or rows shifted mid-read. Either way the window is not provably complete.
            throw new PaymentException("prefunding movements for " + partnerCode + " read " + out.size()
                    + " rows but the endpoint reported totalElements=" + totalElements
                    + "; the window is DISCARDED rather than tied out against an incomplete float leg");
        }
        return out;
    }

    /** One page of prefunding's {@code GET /v1/prefunding/{code}/movements} (T2-8). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MovementsPageResponse(String partnerCode, long totalElements, boolean hasNext,
                                 java.util.List<MovementView> movements) {}

    /** The day-close projection of one movement row. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MovementView(String txnRef, String entryType, BigDecimal balanceDeltaUsd,
                        java.time.Instant at) {}

    @Override
    public PrefundingReserveResponse reserveCpm(long partnerId, BigDecimal amountUsd,
                                                String idempotencyKey, String txnRef) {
        try {
            PrefundingReserveResponse body = restClient.post()
                    .uri("/internal/v1/prefunding/{partner}/reserve", partnerId)
                    .body(new PrefundingReserveRequest(partnerId, amountUsd, idempotencyKey, txnRef))
                    .retrieve()
                    .body(PrefundingReserveResponse.class);
            if (body == null) {
                throw new PaymentException(
                        "prefunding returned empty body for CPM reserve " + idempotencyKey);
            }
            return body;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.PAYMENT_REQUIRED.value()) {
                throw new InsufficientPrefundingException(
                        nonNull(parseAvailable(ex)), nonNull(amountUsd));
            }
            throw new PaymentException(
                    "prefunding POST /internal/v1/prefunding/" + partnerId + "/reserve failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /internal/v1/prefunding/" + partnerId + "/reserve failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public void releaseCpm(long partnerId, String reservationId, String idempotencyKey, String reason) {
        try {
            restClient.post()
                    .uri("/internal/v1/prefunding/{partner}/release", partnerId)
                    .body(new PrefundingReleaseRequest(partnerId, reservationId, idempotencyKey, reason))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "prefunding POST /internal/v1/prefunding/" + partnerId + "/release failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "prefunding POST /internal/v1/prefunding/" + partnerId + "/release failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public void reverseCumulative(long partnerId, String txnRef) {
        try {
            restClient.post()
                    .uri("/v1/prefunding/{partner}/cumulative-reverse", partnerId)
                    .body(new ReverseRequest(txnRef))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            // Best-effort compensation: a failed reverse leaves cap consumed (fail-safe, over-restrictive,
            // not over-permissive). NEVER mask the original failure that triggered the reverse.
            log.warn("prefunding cumulative-reverse failed for partner {} txn {}: {}",
                    partnerId, txnRef, ex.getMessage());
        }
    }

    private static BigDecimal parseAvailable(RestClientResponseException ex) {
        // best-effort extraction; if absent we fall through to ZERO
        try {
            String body = ex.getResponseBodyAsString();
            if (body == null) return BigDecimal.ZERO;
            int idx = body.indexOf("\"available\"");
            if (idx < 0) return BigDecimal.ZERO;
            int colon = body.indexOf(':', idx);
            int end = body.indexOf(',', colon);
            if (end < 0) end = body.indexOf('}', colon);
            if (end < 0) return BigDecimal.ZERO;
            String raw = body.substring(colon + 1, end).trim().replace("\"", "");
            return new BigDecimal(raw);
        } catch (RuntimeException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal nonNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Wire format for {@code POST /v1/prefunding/{partner}/deduct} — the amount field is
     * named {@code amount} on prefunding's {@code PrefundingController.DeductRequest}
     * (found by the SENDMN hub-through E2E: sending {@code amountUsd} bound null and the
     * service rejected every deduct with 400 "amount must be positive").
     */
    record DeductRequest(String txnRef, BigDecimal amount) {}

    record ReverseRequest(String txnRef) {}

    record ReserveRequest(String txnRef, BigDecimal amount) {}

    record CumulativeChargeRequest(String txnRef, BigDecimal amountUsd,
                                   BigDecimal dailyCapUsd, BigDecimal monthlyCapUsd, BigDecimal annualCapUsd,
                                   Integer dailyTxnCountLimit) {}

    /** Wire format of prefunding's deduct answer ({@code BalanceResponse}: partnerId + balance). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeductResponse(String partnerId, BigDecimal balance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReverseResponse(String partnerId, BigDecimal reversedUsd, BigDecimal balance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReserveResponse(String partnerId, BigDecimal reservedUsd, BigDecimal available,
                           BigDecimal balance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CaptureResponse(String partnerId, BigDecimal capturedUsd, BigDecimal balance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReleaseResponse(String partnerId, BigDecimal releasedUsd, BigDecimal balance) {}

    /** Wire format for prefunding's {@code GET /v1/prefunding/{partnerCode}/balance} (BalanceView). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceResponse(String partnerCode, String currency, BigDecimal balance,
                           BigDecimal threshold, BigDecimal pctOfThreshold) {}
}
