package com.gme.pay.payment.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentOrchestrator.CancelResult;
import com.gme.pay.payment.domain.PaymentOrchestrator.CpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.AuthorizeResult;
import com.gme.pay.payment.domain.PaymentOrchestrator.ConfirmContext;
import com.gme.pay.payment.domain.PaymentOrchestrator.MpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.PaymentResult;
import com.gme.pay.payment.domain.PaymentNotFoundException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.event.PaymentEvents;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.payment.persistence.PaymentAuthorizationEntity;
import com.gme.pay.payment.persistence.PaymentAuthorizationRepository;
import com.gme.pay.payment.service.PaymentAuthorizationService;
import org.springframework.dao.DataIntegrityViolationException;
import com.gme.pay.payment.web.dto.CancelPaymentRequest;
import com.gme.pay.payment.web.dto.CancelPaymentResponse;
import com.gme.pay.payment.web.dto.CpmGenerateRequest;
import com.gme.pay.payment.web.dto.CpmGenerateResponse;
import com.gme.pay.payment.web.dto.RefundPaymentResponse;
import com.gme.pay.payment.web.dto.MpmPaymentRequest;
import com.gme.pay.payment.web.dto.MpmPaymentResponse;
import com.gme.pay.payment.web.dto.PaymentDetailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller exposing the Payment Executor API surface (API-05).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /v1/payments/authorize        — MPM two-phase phase 1 (reserve; no scheme call)
 *   <li>POST /v1/payments/{authId}/confirm — MPM two-phase phase 2 (submit + capture)
 *   <li>POST /v1/payments/cpm/generate     — CPM QR token generation
 *   <li>POST /v1/payments/{id}/cancel      — Same-day cancellation
 * </ul>
 *
 * <p>The legacy single-shot {@code POST /v1/payments} (deduct-before-submit) was retired in Step 4;
 * MPM is now exclusively the two-phase authorize/confirm flow.
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    /** How long a partner has to charge the customer + confirm before the authorization expires. */
    private static final long AUTHORIZATION_TTL_MINUTES = 15L;

    private final PaymentOrchestrator orchestrator;
    private final PartnerConfigClient partnerConfigClient;
    private final PaymentAuthorizationRepository authorizationRepository;
    private final PaymentAuthorizationService authorizationService;
    private final EventPublisher eventPublisher;

    /**
     * Constructor injection. {@code partnerConfigClient} resolves the partner type from
     * config-registry; the authorization repository + service back the two-phase authorize/confirm
     * state machine; {@code eventPublisher} emits the lifecycle events this service EXPOSES
     * (payment.approved / payment.failed / payment.cancelled).
     */
    public PaymentController(PaymentOrchestrator orchestrator,
                             PartnerConfigClient partnerConfigClient,
                             PaymentAuthorizationRepository authorizationRepository,
                             PaymentAuthorizationService authorizationService,
                             EventPublisher eventPublisher) {
        this.orchestrator = orchestrator;
        this.partnerConfigClient = partnerConfigClient;
        this.authorizationRepository = authorizationRepository;
        this.authorizationService = authorizationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * POST /v1/payments/authorize — Phase 1 of the two-phase MPM flow (SETTLEMENT_FLOW_SPEC §4/§7.1).
     *
     * <p>Validates + agreement-checks the quote, resolves the merchant, creates the PENDING txn, and
     * RESERVES (holds) the partner float. NOTHING irreversible happens — no scheme call. Returns an
     * {@code authId} plus the settlement amount the partner must charge the customer. The partner then
     * charges the customer's wallet and calls {@code POST /v1/payments/{authId}/confirm}.
     *
     * <p>Idempotent per (partner, partner_txn_ref): a repeat authorize replays the existing one.
     */
    @PostMapping("/authorize")
    public ResponseEntity<AuthorizeResponse> authorizePayment(
            @RequestBody MpmPaymentRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Code", required = false) String partnerCode,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader) {

        req.validate();

        Optional<PaymentAuthorizationEntity> existing =
                authorizationRepository.findByPartnerIdAndPartnerTxnRef(partnerId, req.partnerTxnRef());
        if (existing.isPresent()) {
            log.info("idempotent authorize replay for partner={} txnRef={}", partnerId, req.partnerTxnRef());
            return ResponseEntity.status(HttpStatus.CREATED).body(toAuthorizeResponse(existing.get()));
        }

        PartnerType partnerType = resolvePartnerType(partnerCode, partnerTypeHeader);
        MpmPaymentCommand cmd = new MpmPaymentCommand(
                partnerId, req.quoteId(), req.merchantQr(), req.schemeId(), req.direction(),
                req.customerRef(), req.partnerTxnRef(), partnerCode,
                new BigDecimal(req.collectionAmount()), req.collectionCurrency());

        AuthorizeResult auth = orchestrator.authorizeMpm(cmd, partnerType);
        try {
            PaymentAuthorizationEntity entity = persistAuthorization(cmd, partnerType, partnerCode, auth);
            return ResponseEntity.status(HttpStatus.CREATED).body(toAuthorizeResponse(entity));
        } catch (DataIntegrityViolationException dup) {
            // Concurrent duplicate authorize for the same (partner, partner_txn_ref): the unique index
            // rejected THIS loser's row. Compensate the side effects we just ran (release the hold +
            // fail the orphan txn) so nothing leaks, then replay the winner's authorization.
            log.warn("duplicate authorize partner={} txnRef={}; compensating loser + replaying winner",
                    partnerId, req.partnerTxnRef());
            orchestrator.voidAuthorization(partnerId, auth.txnRef(), partnerType);
            return authorizationRepository.findByPartnerIdAndPartnerTxnRef(partnerId, req.partnerTxnRef())
                    .map(e -> ResponseEntity.status(HttpStatus.CREATED).body(toAuthorizeResponse(e)))
                    .orElseThrow(() -> dup);
        }
    }

    /**
     * POST /v1/payments/{authId}/confirm — Phase 2. The partner calls this AFTER it has charged the
     * customer's wallet, passing the {@code wallet_charge_ref}. Only now does GME submit to the scheme
     * (the irreversible step) and capture the held float. Honours the non-negotiable: the scheme is
     * never hit before the customer-charge confirmation.
     */
    @PostMapping("/{authId}/confirm")
    public ResponseEntity<MpmPaymentResponse> confirmPayment(
            @PathVariable("authId") String authId,
            @RequestBody(required = false) ConfirmPaymentRequest req) {

        PaymentAuthorizationEntity auth = authorizationRepository.findById(authId)
                .orElseThrow(() -> new IllegalArgumentException("unknown authorization: " + authId));
        PartnerType partnerType = PartnerType.valueOf(auth.getPartnerType().toUpperCase());
        String walletChargeRef = req != null ? req.walletChargeRef() : null;

        // CLAIM the authorization atomically (AUTHORIZED -> CONFIRMING). Exactly one caller wins the
        // conditional UPDATE; this is what makes the irreversible scheme submit happen AT MOST ONCE
        // under concurrent or retried confirms. A loser (already confirming/confirmed/expired) is
        // rejected here, before any scheme call.
        if (!authorizationService.compareAndSetStatus(authId,
                PaymentAuthorizationEntity.STATUS_AUTHORIZED,
                PaymentAuthorizationEntity.STATUS_CONFIRMING)) {
            throw new IllegalArgumentException("authorization " + authId
                    + " is not claimable (already confirmed, expired, or in flight)");
        }

        // We now exclusively own this authorization. Handle an expired window under our claim:
        // void it (release the hold + fail the orphan txn) and stop.
        if (auth.isExpired(Instant.now())) {
            orchestrator.voidAuthorization(auth.getPartnerId(), auth.getTxnRef(), partnerType);
            authorizationService.markOutcome(authId,
                    PaymentAuthorizationEntity.STATUS_EXPIRED, walletChargeRef, null);
            throw new IllegalArgumentException("authorization " + authId + " has expired");
        }

        ConfirmContext ctx = toConfirmContext(auth, partnerType);
        try {
            PaymentResult result = orchestrator.confirmMpm(ctx);
            authorizationService.markOutcome(authId,
                    PaymentAuthorizationEntity.STATUS_CONFIRMED, walletChargeRef, Instant.now());
            publishApproved(auth, result);
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
        } catch (SchemeDeclinedException ex) {
            // Scheme declined (no payment); confirmMpm already released the hold + FAILED the txn.
            authorizationService.markOutcome(authId,
                    PaymentAuthorizationEntity.STATUS_FAILED, walletChargeRef, null);
            publishFailed(auth, ex.getMessage());
            throw ex;
        } catch (SchemeTimeoutException ex) {
            // Outcome unknown. Do NOT revert to AUTHORIZED — a retry must never re-submit to the
            // scheme. Park as UNCERTAIN for reconciliation (Step 3 resolves: confirm-capture if the
            // payment landed, else auto-refund the customer + release the hold).
            authorizationService.markOutcome(authId,
                    PaymentAuthorizationEntity.STATUS_UNCERTAIN, walletChargeRef, null);
            throw ex;
        }
        // Any OTHER failure after a successful submit leaves the auth in CONFIRMING (merchant paid,
        // capture/commit still pending) for the reconciler — it never reverts to AUTHORIZED, so the
        // /confirm gate will reject a naive retry rather than double-submitting.
    }

    // ---- two-phase helpers ----

    private PaymentAuthorizationEntity persistAuthorization(MpmPaymentCommand cmd, PartnerType partnerType,
                                                            String partnerCode, AuthorizeResult auth) {
        var q = auth.quote();
        var m = auth.merchant();
        PaymentAuthorizationEntity e = new PaymentAuthorizationEntity();
        e.setAuthId("AUTH-" + UUID.randomUUID());
        e.setPartnerId(cmd.partnerId());
        e.setPartnerCode(partnerCode);
        e.setPartnerType(partnerType.name());
        e.setPartnerTxnRef(cmd.partnerTxnRef());
        e.setQuoteId(cmd.quoteId());
        e.setSchemeId(cmd.schemeId());
        e.setDirection(cmd.direction());
        e.setMerchantQr(cmd.merchantQr());
        e.setCustomerRef(cmd.customerRef());
        e.setMerchantId(m.merchantId());
        e.setMerchantName(m.merchantName());
        e.setTargetPayout(q.targetPayout());
        e.setPayoutCurrency(q.payoutCurrency());
        e.setCollectionAmount(q.collectionAmount());
        e.setCollectionCurrency(q.collectionCurrency());
        e.setCollectionUsd(q.collectionUsd());
        e.setCollectionMarginUsd(q.collectionMarginUsd());
        e.setPayoutMarginUsd(q.payoutMarginUsd());
        e.setServiceCharge(q.serviceCharge());
        e.setMerchantFeeRate(auth.merchantFeeRate());
        e.setReservedUsd(auth.reservedUsd());
        e.setTxnRef(auth.txnRef());
        e.setPaymentId(auth.paymentId());
        e.setStatus(PaymentAuthorizationEntity.STATUS_AUTHORIZED);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setExpiresAt(now.plus(AUTHORIZATION_TTL_MINUTES, ChronoUnit.MINUTES));
        return authorizationRepository.save(e);
    }

    private static ConfirmContext toConfirmContext(PaymentAuthorizationEntity a, PartnerType partnerType) {
        return new ConfirmContext(
                a.getPartnerId(), partnerType, a.getPartnerCode(), a.getPartnerTxnRef(),
                a.getTxnRef(), a.getPaymentId(), a.getSchemeId(), a.getMerchantQr(),
                a.getMerchantId(), a.getMerchantName(), a.getTargetPayout(), a.getPayoutCurrency(),
                a.getCollectionAmount(), a.getCollectionCurrency(), a.getReservedUsd(), null,
                a.getCollectionMarginUsd(), a.getPayoutMarginUsd(), a.getServiceCharge(),
                a.getDirection(), a.getMerchantFeeRate(), a.getCreatedAt());
    }

    private static AuthorizeResponse toAuthorizeResponse(PaymentAuthorizationEntity e) {
        return new AuthorizeResponse(
                e.getAuthId(), e.getPaymentId(), e.getStatus().toLowerCase(),
                e.getCollectionAmount(), e.getCollectionCurrency(),
                e.getTargetPayout(), e.getPayoutCurrency(), e.getExpiresAt());
    }

    /**
     * POST /v1/payments/cpm/generate — execute a CPM (Consumer-Presented Mode) payment.
     *
     * <p>For CPM the customer presents a token-QR on their device; the merchant's terminal
     * scans it and POSTs here to authorise and capture. This delegates to the orchestrator
     * which calls the scheme-adapter-zeropay /cpm endpoint.</p>
     *
     * <p>The {@code X-Partner-Type} header controls prefunding: OVERSEAS deducts from
     * the prefunding pool, LOCAL (default for CPM) does not.</p>
     */
    @PostMapping("/cpm/generate")
    public ResponseEntity<CpmGenerateResponse> generateCpmToken(
            @RequestBody CpmGenerateRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "LOCAL") String partnerTypeHeader) {

        req.validate();

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());

        // For CPM the collectionAmount == payoutAmount (KRW domestic default)
        BigDecimal collectionAmount = new BigDecimal(req.collectionAmount());

        CpmPaymentCommand cmd = new CpmPaymentCommand(
                partnerId,
                req.partnerTxnRef(),
                req.schemeId(),
                req.quoteId(),           // quoteId field re-used as the cpmToken for CPM
                "UNKNOWN",               // merchantId unknown until scheme decode
                collectionAmount,
                req.collectionCurrency(),
                collectionAmount,
                req.collectionCurrency(),
                null                     // no USD prefunding amount for LOCAL
        );

        PaymentResult result = orchestrator.executeCpm(cmd, partnerType);

        CpmGenerateResponse response = new CpmGenerateResponse(
                result.paymentId(),
                result.schemeTxnId(),     // schemeTxnRef returned as the "qr_token" for CPM
                result.approvedAt(),
                req.schemeId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /v1/payments/{id}/cancel — cancel a same-day approved payment.
     *
     * <p>Only APPROVED or PENDING payments on the same calendar day (KST) may be cancelled.
     * For OVERSEAS partners the prefunding deduction is reversed.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancelPayment(
            @PathVariable("id") String paymentId,
            @RequestBody(required = false) CancelPaymentRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader,
            @RequestHeader(value = "X-Txn-Ref", required = false) String txnRef,
            @RequestHeader(value = "X-Scheme-Txn-Ref", required = false) String schemeTxnRef) {

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());
        String reason = (req != null && req.reason() != null) ? req.reason() : "PARTNER_INITIATED";
        String resolvedTxnRef = txnRef != null ? txnRef : paymentId;
        String resolvedSchemeTxnRef = schemeTxnRef != null ? schemeTxnRef : paymentId;

        CancelResult result = orchestrator.cancelPayment(
                paymentId, resolvedSchemeTxnRef, partnerType, partnerId, resolvedTxnRef, reason);

        eventPublisher.publish(new PaymentEvents.PaymentCancelled(
                result.paymentId(), Instant.now(), partnerId, reason, result.prefundReturnedUsd()));

        return ResponseEntity.ok(new CancelPaymentResponse(
                result.paymentId(),
                "cancelled",
                result.cancelledAt(),
                result.prefundReturnedUsd()
        ));
    }

    /**
     * POST /v1/payments/{id}/refund — refund an APPROVED payment (full reversal at the original
     * locked rate, SETTLEMENT_FLOW_SPEC). Distinct from /cancel (a same-day void): a refund reverses
     * an already-settled txn → REFUNDED. For OVERSEAS partners the captured prefund USD is credited
     * back; a reversal journal is booked on revenue-ledger.
     */
    @PostMapping("/{id}/refund")
    public ResponseEntity<RefundPaymentResponse> refundPayment(
            @PathVariable("id") String paymentId,
            @RequestBody(required = false) CancelPaymentRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader,
            @RequestHeader(value = "X-Txn-Ref", required = false) String txnRef,
            @RequestHeader(value = "X-Scheme-Txn-Ref", required = false) String schemeTxnRef) {

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());
        String reason = (req != null && req.reason() != null) ? req.reason() : "PARTNER_INITIATED";
        String resolvedTxnRef = txnRef != null ? txnRef : paymentId;
        String resolvedSchemeTxnRef = schemeTxnRef != null ? schemeTxnRef : paymentId;

        PaymentOrchestrator.RefundResult result = orchestrator.refundPayment(
                paymentId, resolvedSchemeTxnRef, partnerType, partnerId, resolvedTxnRef, reason);

        return ResponseEntity.ok(new RefundPaymentResponse(
                result.paymentId(),
                "refunded",
                result.refundedAt(),
                result.prefundReturnedUsd()
        ));
    }

    /**
     * GET /v1/payments/{id} — retrieve the full payment record for partner status polling
     * (API-05 §4, backlog 5.2-T16).
     *
     * <p>Scoped to the calling partner via {@code X-Partner-Id}: a payment owned by a different
     * partner (or no such payment) returns HTTP 404 {@code PAYMENT_NOT_FOUND} — never 403 — so
     * ownership is not leaked. {@code prefund_deducted_usd} is emitted only for OVERSEAS partners;
     * {@code approved_at}/{@code cancelled_at} stay null until the corresponding transition.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PaymentDetailResponse> getPayment(
            @PathVariable("id") String paymentId,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId) {

        PaymentAuthorizationEntity e = authorizationRepository
                .findByPaymentIdAndPartnerId(paymentId, partnerId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return ResponseEntity.ok(toDetailResponse(e));
    }

    /** Maps the persisted two-phase status to the lowercase API status (API-05 contract). */
    private static String toApiStatus(String entityStatus) {
        return switch (entityStatus) {
            case PaymentAuthorizationEntity.STATUS_CONFIRMED -> "approved";
            case PaymentAuthorizationEntity.STATUS_FAILED -> "failed";
            case PaymentAuthorizationEntity.STATUS_UNCERTAIN -> "uncertain";
            case PaymentAuthorizationEntity.STATUS_RELEASED,
                 PaymentAuthorizationEntity.STATUS_EXPIRED -> "cancelled";
            default -> "pending"; // AUTHORIZED / CONFIRMING
        };
    }

    private static PaymentDetailResponse toDetailResponse(PaymentAuthorizationEntity e) {
        boolean overseas = PartnerType.OVERSEAS.name().equalsIgnoreCase(e.getPartnerType());
        boolean approved = PaymentAuthorizationEntity.STATUS_CONFIRMED.equals(e.getStatus());
        boolean cancelled = PaymentAuthorizationEntity.STATUS_RELEASED.equals(e.getStatus())
                || PaymentAuthorizationEntity.STATUS_EXPIRED.equals(e.getStatus());
        // prefund_deducted_usd is meaningful only once captured (CONFIRMED) for an OVERSEAS partner.
        BigDecimal prefundDeducted = (overseas && approved) ? e.getReservedUsd() : null;
        return new PaymentDetailResponse(
                e.getPaymentId(),
                toApiStatus(e.getStatus()),
                e.getPartnerTxnRef(),
                e.getSchemeId(),
                e.getDirection(),
                e.getMerchantId(),
                e.getMerchantName(),
                e.getTargetPayout(),
                e.getPayoutCurrency(),
                e.getCollectionAmount(),
                e.getCollectionCurrency(),
                e.getServiceCharge(),
                prefundDeducted,
                e.getCreatedAt(),
                approved ? e.getConfirmedAt() : null,
                cancelled ? e.getConfirmedAt() : null);
    }

    // ---- partner-type resolution ----

    /**
     * Resolve the partner type from config-registry by partner code; fall back to the
     * {@code X-Partner-Type} header when no code is supplied or the lookup fails (fail-open
     * so a config-registry outage degrades to the header rather than rejecting payments).
     */
    private PartnerType resolvePartnerType(String partnerCode, String headerFallback) {
        if (partnerCode != null && !partnerCode.isBlank()) {
            try {
                PartnerConfigClient.PartnerConfigView cfg = partnerConfigClient.loadPartner(partnerCode);
                if (cfg != null && cfg.type() != null && !cfg.type().isBlank()) {
                    return PartnerType.valueOf(cfg.type().toUpperCase());
                }
            } catch (RuntimeException e) {
                log.warn("partner-type resolution from config-registry failed for code={}; "
                        + "falling back to X-Partner-Type header: {}", partnerCode, e.getMessage());
            }
        }
        return PartnerType.valueOf(headerFallback.toUpperCase());
    }

    // ---- event emission (payment.approved / payment.failed / payment.cancelled) ----

    private void publishApproved(PaymentAuthorizationEntity auth, PaymentResult r) {
        eventPublisher.publish(new PaymentEvents.PaymentApproved(
                r.paymentId(), Instant.now(), auth.getPartnerId(), auth.getPartnerTxnRef(),
                r.schemeTxnId(), r.merchantId(), r.targetPayout(), r.payoutCurrency(),
                r.collectionAmount(), r.collectionCurrency()));
    }

    private void publishFailed(PaymentAuthorizationEntity auth, String reason) {
        eventPublisher.publish(new PaymentEvents.PaymentFailed(
                auth.getPaymentId(), Instant.now(), auth.getPartnerId(), auth.getPartnerTxnRef(), reason));
    }

    // ---- mapping ----

    private static MpmPaymentResponse toResponse(PaymentResult r) {
        return new MpmPaymentResponse(
                r.paymentId(),
                r.status().name().toLowerCase(),
                r.schemeTxnId(),
                r.merchantName(),
                r.merchantId(),
                r.targetPayout(),
                r.payoutCurrency(),
                r.offerRate(),
                r.collectionAmount(),
                r.collectionCurrency(),
                r.serviceCharge(),
                r.serviceChargeCurrency(),
                r.prefundDeductedUsd(),
                r.partnerTxnRef(),
                r.createdAt(),
                r.approvedAt()
        );
    }

    // ---- two-phase DTOs ----

    /** Response to POST /authorize: the auth handle + the settlement amount the partner must charge. */
    public record AuthorizeResponse(
            @JsonProperty("auth_id") String authId,
            @JsonProperty("payment_id") String paymentId,
            @JsonProperty("status") String status,
            @JsonProperty("collection_amount") BigDecimal collectionAmount,
            @JsonProperty("collection_currency") String collectionCurrency,
            @JsonProperty("target_payout") BigDecimal targetPayout,
            @JsonProperty("payout_currency") String payoutCurrency,
            @JsonProperty("expires_at") Instant expiresAt) {}

    /** Request body for POST /{authId}/confirm: the partner's customer-wallet-charge reference. */
    public record ConfirmPaymentRequest(@JsonProperty("wallet_charge_ref") String walletChargeRef) {}
}
