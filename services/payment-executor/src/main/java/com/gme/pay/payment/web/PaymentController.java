package com.gme.pay.payment.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentOrchestrator.CancelResult;
import com.gme.pay.payment.domain.PaymentOrchestrator.CpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.AuthorizeResult;
import com.gme.pay.payment.domain.PaymentOrchestrator.ConfirmContext;
import com.gme.pay.payment.domain.PaymentOrchestrator.MpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.PaymentResult;
import com.gme.pay.payment.domain.OperationalGate;
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
import com.gme.pay.payment.metrics.PaymentSliMetrics;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
    /** KST — the revenue (KST business-calendar) date carried on the payment.approved event. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /**
     * Default fee-share fraction recorded as metadata on the event. The authoritative two-sided split
     * is computed in revenue-ledger; this is record metadata only (mirrors
     * PaymentOrchestrator.DEFAULT_FEE_SHARE_PCT).
     */
    private static final BigDecimal DEFAULT_FEE_SHARE_PCT = new BigDecimal("0.70");

    private final PaymentOrchestrator orchestrator;
    private final PartnerConfigClient partnerConfigClient;
    private final PaymentAuthorizationRepository authorizationRepository;
    private final PaymentAuthorizationService authorizationService;
    private final EventPublisher eventPublisher;
    /** Operations operational gate — checked at the START of the orchestrated NEW authorize. */
    private final OperationalGate operationalGate;

    /**
     * Constructor injection. {@code partnerConfigClient} resolves the partner type from
     * config-registry; the authorization repository + service back the two-phase authorize/confirm
     * state machine; {@code eventPublisher} emits the lifecycle events this service EXPOSES
     * (payment.approved / payment.failed / payment.cancelled); {@code operationalGate} refuses new
     * authorizes while the platform / partner / scheme is paused or suspended.
     */
    public PaymentController(PaymentOrchestrator orchestrator,
                             PartnerConfigClient partnerConfigClient,
                             PaymentAuthorizationRepository authorizationRepository,
                             PaymentAuthorizationService authorizationService,
                             EventPublisher eventPublisher,
                             OperationalGate operationalGate) {
        this.orchestrator = orchestrator;
        this.partnerConfigClient = partnerConfigClient;
        this.authorizationRepository = authorizationRepository;
        this.authorizationService = authorizationService;
        this.eventPublisher = eventPublisher;
        this.operationalGate = operationalGate;
    }

    /**
     * Payment-path SLIs (T3-5). Injected by SETTER and optional, so the many unit slices that
     * construct this controller directly keep compiling and a missing registry simply means
     * "not measured" — a measurement concern must never be able to fail a payment.
     */
    @org.springframework.lang.Nullable private PaymentSliMetrics paymentSli;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPaymentSli(PaymentSliMetrics paymentSli) {
        this.paymentSli = paymentSli;
    }

    /** Times {@code call} as {@code entry} when the SLI bean is present; otherwise just runs it. */
    private <R extends ResponseEntity<?>> R sli(String entry, java.util.function.Supplier<R> call) {
        return paymentSli == null ? call.get() : paymentSli.record(entry, call);
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
        // Thin measured wrapper (T3-5). The implementation is untouched below; splitting it this
        // way keeps the SLI out of the money-path logic and leaves every existing direct-call
        // unit test working against the same public signature.
        return sli(PaymentSliMetrics.ENTRY_AUTHORIZE,
                () -> doAuthorizePayment(req, partnerId, partnerCode, partnerTypeHeader));
    }

    private ResponseEntity<AuthorizeResponse> doAuthorizePayment(
            MpmPaymentRequest req,
            long partnerId,
            String partnerCode,
            String partnerTypeHeader) {

        req.validate();

        Optional<PaymentAuthorizationEntity> existing =
                authorizationRepository.findByPartnerIdAndPartnerTxnRef(partnerId, req.partnerTxnRef());
        if (existing.isPresent()) {
            // Idempotent replay of an ALREADY-authorized (in-flight) txn — must complete even mid-pause,
            // so the operational gate is deliberately NOT applied to a replay.
            log.info("idempotent authorize replay for partner={} txnRef={}", partnerId, req.partnerTxnRef());
            return ResponseEntity.status(HttpStatus.CREATED).body(toAuthorizeResponse(existing.get()));
        }

        // Operations operational gate: refuse this NEW authorize while the platform is paused / in
        // maintenance, or when the resolved partner / scheme is suspended. Runs before any side effect
        // (quote agreement-check, merchant resolve, float reserve). Confirm/cancel/refund of an
        // existing authorization never reaches here.
        //
        // T5-3: the same call now also runs the counterparty sanctions/PEP screening seam. The subject
        // we can offer is the honest one and it is DELIBERATELY thin: `customer_ref` is an opaque
        // partner-side handle, so this subject is NOT screenable (no name, no DOB, no nationality) and
        // the gate records it as NO_SUBJECT_IDENTITY rather than pretending a reference was screened.
        // That is the finding, not a workaround — API-05's authorize contract carries no originator
        // identity at all, so no vendor purchase alone can produce screening coverage on this path.
        // The beneficiary is likewise unavailable HERE: the merchant is resolved inside the
        // orchestrator's step 2, after this gate. Both are recorded in the fix report as required work.
        operationalGate.checkNewAuthorization(partnerCode, req.schemeId(), req.direction(),
                req.partnerTxnRef(),
                List.of(PaymentScreeningSubject.byReferenceOnly(PaymentParty.PAYER, req.customerRef())));

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
        // See authorizePayment: thin measured wrapper, implementation unchanged.
        return sli(PaymentSliMetrics.ENTRY_CONFIRM, () -> doConfirmPayment(authId, req));
    }

    private ResponseEntity<MpmPaymentResponse> doConfirmPayment(
            String authId,
            ConfirmPaymentRequest req) {

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
                a.getDirection(), a.getMerchantFeeRate(), a.getCreatedAt(), a.getCollectionUsd());
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
     *
     * <p>T2-7: {@code X-Scheme-Id} (optional) carries the scheme CODE the payment was executed on so
     * the scheme cancel is dispatched to THAT adapter. Absent → the ZeroPay default (unchanged legacy
     * behaviour). A scheme with no cancel round-trip (NEPAL / SENDMN) answers
     * {@code 422 SCHEME_OPERATION_UNSUPPORTED} and nothing is mutated.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancelPayment(
            @PathVariable("id") String paymentId,
            @RequestBody(required = false) CancelPaymentRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader,
            @RequestHeader(value = "X-Txn-Ref", required = false) String txnRef,
            @RequestHeader(value = "X-Scheme-Txn-Ref", required = false) String schemeTxnRef,
            @RequestHeader(value = "X-Scheme-Id", required = false) String schemeIdHeader) {

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());
        String reason = (req != null && req.reason() != null) ? req.reason() : "PARTNER_INITIATED";
        String resolvedTxnRef = txnRef != null ? txnRef : paymentId;
        String resolvedSchemeTxnRef = schemeTxnRef != null ? schemeTxnRef : paymentId;
        String resolvedSchemeId = resolveSchemeId(schemeIdHeader, req);

        CancelResult result = orchestrator.cancelPayment(
                paymentId, resolvedSchemeTxnRef, partnerType, partnerId, resolvedTxnRef, reason,
                resolvedSchemeId);

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
     *
     * <p>T2-7: {@code X-Scheme-Id} routes the scheme-side refund the same way {@code /cancel} does.
     *
     * <p>T2-6: the optional body {@code amount} (+ {@code currency}) makes this a PARTIAL refund. It is
     * validated against the original payment and everything already refunded for it, so cumulative partial
     * refunds cannot exceed the original ({@code 422 REFUND_AMOUNT_EXCEEDS_ORIGINAL}); the float is credited
     * back pro-rata at the ORIGINAL locked rate; the cumulative refunded amount is persisted so settlement's
     * claw-back nets it; and the REFUNDED commit now emits {@code payment.reversed}, so revenue reversal runs
     * and the partner receives a refund webhook. Omitting {@code amount} is a full refund — unchanged.
     */
    @PostMapping("/{id}/refund")
    public ResponseEntity<RefundPaymentResponse> refundPayment(
            @PathVariable("id") String paymentId,
            @RequestBody(required = false) CancelPaymentRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader,
            @RequestHeader(value = "X-Txn-Ref", required = false) String txnRef,
            @RequestHeader(value = "X-Scheme-Txn-Ref", required = false) String schemeTxnRef,
            @RequestHeader(value = "X-Scheme-Id", required = false) String schemeIdHeader) {

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());
        String reason = (req != null && req.reason() != null) ? req.reason() : "PARTNER_INITIATED";
        String resolvedTxnRef = txnRef != null ? txnRef : paymentId;
        String resolvedSchemeTxnRef = schemeTxnRef != null ? schemeTxnRef : paymentId;
        String resolvedSchemeId = resolveSchemeId(schemeIdHeader, req);

        PaymentOrchestrator.RefundResult result = orchestrator.refundPayment(
                paymentId, resolvedSchemeTxnRef, partnerType, partnerId, resolvedTxnRef, reason,
                resolvedSchemeId,
                req != null ? req.amount() : null,
                req != null ? req.currency() : null);

        return ResponseEntity.ok(new RefundPaymentResponse(
                result.paymentId(),
                "refunded",
                result.refundedAt(),
                result.prefundReturnedUsd(),
                result.refundedAmount(),
                result.refundedCurrency(),
                result.cumulativeRefundedAmount(),
                result.fullyRefunded()
        ));
    }

    /**
     * T2-7 scheme resolution for cancel/refund: the {@code X-Scheme-Id} header wins, then the optional
     * {@code schemeId} body field, else null (ZeroPay default). Null/blank is preserved as null so the
     * router's legacy fallback is unchanged.
     */
    private static String resolveSchemeId(String schemeIdHeader, CancelPaymentRequest req) {
        if (schemeIdHeader != null && !schemeIdHeader.isBlank()) {
            return schemeIdHeader;
        }
        if (req != null && req.schemeId() != null && !req.schemeId().isBlank()) {
            return req.schemeId();
        }
        return null;
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

    /**
     * Emits the canonical {@code payment.approved} event (the revenue-bearing one consumed by
     * revenue-ledger + notification-webhook). The revenue fields are the ones snapshotted at authorize
     * on the authorization (margins, service charge) replayed here; {@code schemeId} is the numeric id
     * resolved from the carried scheme CODE via {@link com.gme.pay.payment.domain.SchemeId} (0 only when
     * the code is outside the platform roster).
     * {@code feeSharePct} is the default record-metadata share (the authoritative split is computed in
     * revenue-ledger). The carried {@link PaymentEvents.PaymentApproved} maps to {@code
     * PaymentApprovedPayload} for the wire via {@code payload()}.
     */
    private void publishApproved(PaymentAuthorizationEntity auth, PaymentResult r) {
        BigDecimal collMargin = auth.getCollectionMarginUsd() != null
                ? auth.getCollectionMarginUsd() : BigDecimal.ZERO;
        BigDecimal payMargin = auth.getPayoutMarginUsd() != null
                ? auth.getPayoutMarginUsd() : BigDecimal.ZERO;
        BigDecimal svcCharge = auth.getServiceCharge() != null
                ? auth.getServiceCharge() : BigDecimal.ZERO;
        LocalDate revenueDate = auth.getCreatedAt().atZone(KST).toLocalDate();
        // Resolve the numeric scheme id from the carried scheme CODE (was 0 — orchestrator/txn carry
        // the code, not config-registry's numeric id; see SchemeId).
        long schemeId = com.gme.pay.payment.domain.SchemeId.resolve(auth.getSchemeId());
        eventPublisher.publish(new PaymentEvents.PaymentApproved(
                r.paymentId(), Instant.now(), revenueDate, auth.getPartnerId(),
                schemeId, auth.getPartnerTxnRef(), auth.getTxnRef(),
                collMargin, payMargin, svcCharge, auth.getCollectionCurrency(),
                DEFAULT_FEE_SHARE_PCT));
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
