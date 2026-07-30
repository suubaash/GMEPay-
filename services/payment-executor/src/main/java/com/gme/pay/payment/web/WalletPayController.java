package com.gme.pay.payment.web;

import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.payment.alert.DeclineSpikeMonitor;
import com.gme.pay.payment.domain.CorridorPricingUnavailableException;
import com.gme.pay.payment.domain.CumulativeLimitExceededException;
import com.gme.pay.payment.domain.FailoverPaymentRouter;
import com.gme.pay.payment.domain.GmeremitPaymentService;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.LimitCheckUnavailableException;
import com.gme.pay.payment.domain.OperationalGate;
import com.gme.pay.payment.domain.PartialRefundNotSupportedException;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.QrSchemeClassifier;
import com.gme.pay.payment.domain.QrSchemeClassifier.Classification;
import com.gme.pay.payment.domain.RefundAmountInvalidException;
import com.gme.pay.payment.domain.SchemeOperationNotSupportedException;
import com.gme.pay.payment.domain.SendmnPaymentService;
import com.gme.pay.payment.domain.TransactionLimitExceededException;
import com.gme.pay.payment.domain.WalletPartnerRef;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.IdempotencyRecordEntity;
import com.gme.pay.payment.persistence.IdempotencyRecordRepository;
import com.gme.pay.payment.web.dto.WalletPaymentRequest;
import com.gme.pay.payment.web.dto.WalletPaymentResponse;
import com.gme.pay.payment.web.dto.WalletRefundRequest;
import com.gme.pay.payment.web.dto.WalletRefundResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Wallet payment entry point at {@code POST /v1/pay}.
 *
 * <p>Dispatches to the correct service based on the {@code partner} field:
 * <ul>
 *   <li>{@code partner=GMEREMIT} — domestic KRW→KRW path ({@link GmeremitPaymentService}).
 *   <li>{@code partner=SENDMN}   — overseas KRW→MNT path ({@link SendmnPaymentService}).
 *       Requires {@code amountKrw} to be present. The response adds FX fields
 *       ({@code fxApplied}, {@code fxRate}, {@code payAmountMnt}).
 * </ul>
 *
 * <p>Request:
 * <pre>
 * POST /v1/pay
 * Content-Type: application/json
 * {
 *   "qrPayload"  : "&lt;raw EMVCo QR string scanned by wallet&gt;",
 *   "amountKrw"  : "50000",      // amount in `currency` (name kept for wire compat)
 *   "partner"    : "GMEREMIT" | "SENDMN",
 *   "userRef"    : "&lt;wallet user ID&gt;",
 *   "currency"   : "NPR"          // OPTIONAL, defaults to KRW; drives the cross-border pay currency
 * }
 * </pre>
 *
 * <p>{@code currency} is additive and defaults to {@code KRW} (full back-compat). For a non-ZeroPay
 * scheme routed via {@link FailoverPaymentRouter} (e.g. Nepal Fonepay) the amount is executed in
 * {@code currency} (NPR) rather than assumed KRW; the response then carries {@code payCurrency} +
 * {@code payAmount}. The ZeroPay/GMEREMIT domestic KRW path (currency absent or {@code KRW}) is
 * unchanged, keeping the ₩500 fee.
 *
 * <p><b>T4-1.</b> The NEPAL corridor is priced by the hub: {@link FailoverPaymentRouter} delegates it
 * to {@code NepalPaymentService}, which applies the configured KRW→NPR FX margin and service fee,
 * debits USD prefunding and books revenue. {@code currency} selects the quoted leg (KRW = collection,
 * the default; NPR = merchant payout). Other schemes are still dispatched without hub-side FX.
 */
@RestController
@RequestMapping("/v1/pay")
public class WalletPayController {

    private static final Logger log = LoggerFactory.getLogger(WalletPayController.class);

    private static final String PARTNER_GMEREMIT = "GMEREMIT";
    private static final String PARTNER_SENDMN   = "SENDMN";

    /**
     * GMEREMIT sandbox partner ID. Matches the {@code X-Partner-Id} default of {@code 1} used by
     * {@link PaymentController}/{@link BalanceController}, so idempotency keys are scoped to the
     * same numeric partner across endpoints.
     */
    private static final long GMEREMIT_PARTNER_ID = 1L;

    /**
     * SENDMN sandbox partner ID. A real deployment would look this up from config-registry
     * but for the sandbox we use a well-known constant so no DB round-trip is needed.
     */
    private static final long SENDMN_PARTNER_ID = 2L;

    /** Idempotency-key retention window: recorded outcomes replay for 24h, then GC'd. */
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final GmeremitPaymentService gmeremitPaymentService;
    private final SendmnPaymentService sendmnPaymentService;
    @Nullable private final FailoverPaymentRouter failoverPaymentRouter;
    @Nullable private final SchemeClient schemeClient;
    @Nullable private final TransactionClient transactionClient;
    @Nullable private final RevenueLedgerClient revenueLedgerClient;
    /** Operations operational gate — checked at the START of every NEW wallet payment. */
    @Nullable private final OperationalGate operationalGate;
    /** DECLINE_SPIKE monitor (defect #5) — records each outcome; null when the feature is off. */
    @Nullable private final DeclineSpikeMonitor declineSpikeMonitor;
    /**
     * Request-level idempotency store ({@code idempotency_keys}). When null (minimal config) or the
     * {@code Idempotency-Key} header is absent, the endpoint keeps its exact pre-idempotency
     * behaviour — full back-compat.
     */
    @Nullable private final IdempotencyRecordRepository idempotencyRepository;
    /** Serialiser for the replayed response snapshot; paired with {@link #idempotencyRepository}. */
    @Nullable private final ObjectMapper objectMapper;

    /**
     * Production constructor — all collaborators injected.
     * Spring 6 two-constructor trap: @Autowired on the @Nullable-bearing ctor.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WalletPayController(GmeremitPaymentService gmeremitPaymentService,
                               SendmnPaymentService sendmnPaymentService,
                               FailoverPaymentRouter failoverPaymentRouter,
                               @Nullable SchemeClient schemeClient,
                               @Nullable TransactionClient transactionClient,
                               @Nullable RevenueLedgerClient revenueLedgerClient,
                               @Nullable OperationalGate operationalGate,
                               @Nullable DeclineSpikeMonitor declineSpikeMonitor,
                               @Nullable IdempotencyRecordRepository idempotencyRepository,
                               @Nullable ObjectMapper objectMapper) {
        this.gmeremitPaymentService = gmeremitPaymentService;
        this.sendmnPaymentService = sendmnPaymentService;
        this.failoverPaymentRouter = failoverPaymentRouter;
        this.schemeClient = schemeClient;
        this.transactionClient = transactionClient;
        this.revenueLedgerClient = revenueLedgerClient;
        this.operationalGate = operationalGate;
        this.declineSpikeMonitor = declineSpikeMonitor;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /** Backwards-compatible 2-arg constructor used by existing tests (no failover routing). */
    WalletPayController(GmeremitPaymentService gmeremitPaymentService,
                        SendmnPaymentService sendmnPaymentService) {
        this(gmeremitPaymentService, sendmnPaymentService, null, null, null, null, null, null, null, null);
    }

    /**
     * POST /v1/pay — dispatches to GMEREMIT domestic or SENDMN overseas path.
     *
     * <p>Optional request-level idempotency (Stripe-style). The client MAY send an
     * {@code Idempotency-Key} (or {@code X-Idempotency-Key}) header so that a retry — a network
     * timeout, a double-tap — NEVER creates a second payment. When the header is <b>absent</b> the
     * behaviour is byte-for-byte identical to before (the existing {@code partner_txn_ref} dedup
     * still applies downstream); the key is <b>not</b> required.
     *
     * <p>When present, an insert-first claim over the {@code UNIQUE(partner_id, idempotency_key)}
     * constraint serialises concurrent retries:
     * <ul>
     *   <li>Claim SUCCEEDS → this is the first request: {@link #execute(WalletPaymentRequest)} runs,
     *       then the response (status + JSON body + txnRef) is recorded onto the row.
     *   <li>Claim FAILS (duplicate key) → a prior request used this key:
     *     <ul>
     *       <li>Different {@code request_hash} → 422 {@code idempotency_key_reuse} (client bug: the
     *           same key was reused for a different payload — we must not mis-serve it).
     *       <li>Same hash + recorded response → REPLAY it verbatim (same HTTP status + body),
     *           re-executing NOTHING (no second scheme submit / txn / ledger entry).
     *       <li>Same hash but no response yet → 409 {@code idempotency_in_progress} (a concurrent
     *           in-flight first request; the client retries shortly).
     *     </ul>
     * </ul>
     *
     * <p>Server-error key handling: if the first execution throws (5xx), the claim row is deleted so
     * the key is NOT poisoned — a genuine retry can re-claim and execute. Only a completed response
     * (2xx/4xx business outcome) finalises the key for replay.
     */
    @PostMapping
    public ResponseEntity<?> pay(
            @RequestBody WalletPaymentRequest req,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String idempotencyKeyAlt) {
        req.validate();

        String key = firstNonBlank(idempotencyKey, idempotencyKeyAlt);
        // No key (or store unavailable) → unchanged legacy path, full back-compat.
        if (key == null || idempotencyRepository == null || objectMapper == null) {
            return execute(req);
        }
        return payIdempotent(req, key.trim());
    }

    /**
     * Idempotent wrapper: claim → execute+record | replay | conflict. See {@link #pay} for the
     * full contract.
     */
    private ResponseEntity<?> payIdempotent(WalletPaymentRequest req, String key) {
        long partnerId = resolvePartnerId(req.partner());
        String requestHash = requestHash(req);

        // Insert-first claim. The UNIQUE(partner_id, idempotency_key) constraint is the concurrency
        // arbiter: exactly one caller inserts, everyone else collides.
        IdempotencyRecordEntity claim = new IdempotencyRecordEntity(
                partnerId, key, requestHash, Instant.now());
        claim.setExpiresAt(Instant.now().plus(IDEMPOTENCY_TTL));
        try {
            idempotencyRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException dup) {
            return replayOrConflict(partnerId, key, requestHash);
        }

        // We own the claim — execute exactly once.
        final ResponseEntity<WalletPaymentResponse> response;
        try {
            response = execute(req);
        } catch (RuntimeException ex) {
            // Server-error safety: do NOT poison the key. Drop the claim so a genuine retry can
            // re-execute. (Business declines return normally as a 422 body and DO finalise below.)
            try {
                idempotencyRepository.delete(claim);
            } catch (RuntimeException cleanupEx) {
                log.warn("failed to release idempotency claim after error (partner={} key={}): {}",
                        partnerId, key, cleanupEx.getMessage());
            }
            throw ex;
        }

        // Record the completed outcome for future replays.
        try {
            claim.setTxnRef(response.getBody() != null ? response.getBody().txnRef() : null);
            claim.recordOutcome(
                    toPaymentStatus(response.getStatusCode().value()),
                    objectMapper.writeValueAsString(response.getBody()));
            idempotencyRepository.saveAndFlush(claim);
        } catch (Exception recordEx) {
            // Recording failed but the payment already happened; return the real response rather
            // than error. A later retry with the same key hits the recorded-but-null path (409) or
            // this same row without a body → conflict, never a second payment.
            log.warn("failed to record idempotency outcome (partner={} key={}): {}",
                    partnerId, key, recordEx.getMessage());
        }
        return response;
    }

    /** Handles a claim collision: reuse-detection, verbatim replay, or in-progress conflict. */
    private ResponseEntity<?> replayOrConflict(long partnerId, String key, String requestHash) {
        Optional<IdempotencyRecordEntity> existing =
                idempotencyRepository.findByPartnerIdAndIdempotencyKey(partnerId, key);
        if (existing.isEmpty()) {
            // Extremely rare race (row vanished between collision and read) — treat as in-progress.
            return conflict();
        }
        IdempotencyRecordEntity row = existing.get();
        if (!requestHash.equals(row.getRequestHash())) {
            // Same key, different payload → client bug. 422; never re-serve.
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(java.util.Map.of("error", "idempotency_key_reuse"));
        }
        if (row.getResponseBody() == null || row.getResponseStatus() == null) {
            // First request still in-flight — nothing to replay yet.
            return conflict();
        }
        // Verbatim replay: same HTTP status + stored body, ZERO side effects.
        try {
            WalletPaymentResponse body =
                    objectMapper.readValue(row.getResponseBody(), WalletPaymentResponse.class);
            return ResponseEntity.status(toHttpStatus(row.getResponseStatus()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (Exception ex) {
            log.warn("failed to deserialize recorded idempotent response (partner={} key={}): {}",
                    partnerId, key, ex.getMessage());
            return conflict();
        }
    }

    private ResponseEntity<?> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(java.util.Map.of("error", "idempotency_in_progress"));
    }

    /**
     * The original {@code POST /v1/pay} execution — dispatches to GMEREMIT domestic, SENDMN
     * overseas, or the failover router. Unchanged money semantics; called once per accepted request.
     */
    private ResponseEntity<WalletPaymentResponse> execute(WalletPaymentRequest req) {
        BigDecimal amountKrw = new BigDecimal(req.amountKrw());
        WalletResult result;

        Classification gateQr = QrSchemeClassifier.classify(req.qrPayload());

        // ADR-016: route the scanned MPM QR by its OWN network identifier, not by partner. A
        // non-ZeroPay network (Fonepay/NepalPay/Khalti…) arrives as partner=GMEREMIT but must NOT
        // go down the ZeroPay domestic path (it would 404 with MERCHANT_NOT_FOUND). We classify the
        // QR and, for a known non-ZeroPay network, dispatch through the FailoverPaymentRouter
        // (classify → resolve ordered candidates → failover). This subsumes the retired
        // NepalQrDetector: a Fonepay QR classifies to fonepay.com and resolves to the Nepal
        // candidate. ZeroPay QRs (com.zeropay / 5802KR) keep the unchanged GMEREMIT/SENDMN paths
        // so their merchant validation + fee behaviour is preserved exactly.
        Classification qr = gateQr;
        // partner=SENDMN is an EXPLICIT corridor selection: the wallet already ran
        // /v1/pay/classify, learned the QR resolves to SENDMN, and is paying a KRW amount
        // through the KRW→MNT FX corridor (SendmnPaymentService: FX + ₩500 fee + USD
        // prefunding). Routing that request through the failover pass-through would treat
        // the KRW amount as MNT and skip fee/prefunding — so an explicit SENDMN partner
        // always dispatches to its documented corridor below (a QPay/MN QR would otherwise
        // classify as a known non-ZeroPay network and be hijacked here).
        boolean routeViaFailover = failoverPaymentRouter != null
                && qr.isKnown()
                && !isZeroPayNetwork(qr.networkIdentifier())
                && !PARTNER_SENDMN.equalsIgnoreCase(req.partner());

        // Operations operational gate: refuse NEW payments while the platform is paused / in
        // maintenance, or when THIS payment's partner alias / classified network (route) is suspended.
        // Runs at the START, before any merchant lookup / scheme submit, so nothing irreversible fires
        // on a rejected payment. Confirm/refund of an in-flight txn does not enter this controller.
        //
        // T3-6: the same call now also enforces the scheme's SEEDED OPERATING WINDOW
        // (scheme_operating_hours, V024) — the wallet entry point gets it from the same gate as the
        // orchestrated authorize path, so the two cannot drift the way limit enforcement did in T4-2.
        // The scheme reference passed here is the one this request is ACTUALLY dispatched to and is
        // never guessed from the QR's network: the two direct corridors are known statically, and the
        // failover branch is gated per RESOLVED candidate inside FailoverPaymentRouter (which knows the
        // real scheme ids and can skip a closed candidate rather than failing the whole payment).
        //
        // T5-3: the same call also runs the counterparty sanctions/PEP screening seam. The payer
        // subject is `userRef` — a wallet account id / user UUID, i.e. an OPAQUE handle. No name, date
        // of birth or nationality is transmitted on POST /v1/pay, so the subject is not screenable and
        // the gate records NO_SUBJECT_IDENTITY. That is deliberate and is the actual finding: on the
        // busiest entry point the platform has, the originator's identity never reaches this service, so
        // wiring a name-matching vendor would still screen nobody here until the wallet contract carries
        // it. The merchant/beneficiary is resolved further down (inside the corridor services, after
        // this gate), so it is not offered here either rather than being guessed from the QR.
        // No payment reference exists at this point either — the wallet supplies none — so the coverage
        // row's evidence anchor is null for this path; also recorded in the fix report.
        if (operationalGate != null) {
            operationalGate.checkNewAuthorization(
                    req.partner(),
                    routeViaFailover ? null : directCorridorSchemeRef(req.partner()),
                    gateQr.isKnown() ? gateQr.networkIdentifier() : null,
                    null,
                    List.of(PaymentScreeningSubject.byReferenceOnly(PaymentParty.PAYER, req.userRef())));
        }

        // T4-2: the regulatory limit subject is the WALLET partner (the issuer charging the customer),
        // whose alias IS its config-registry partner code — that is the licence whose per-txn /
        // cumulative caps apply, not the receiving partner a QR routes to.
        WalletPartnerRef limitSubject =
                WalletPartnerRef.of(req.partner(), resolvePartnerId(req.partner()));

        try {
            if (routeViaFailover) {
                // Non-ZeroPay networks routed via failover are cross-border (OVERSEAS) in this sandbox.
                // The wallet-supplied currency (default KRW when absent) says which currency `amountKrw`
                // is denominated in.
                //
                // T4-1: for the NEPAL corridor the router delegates to NepalPaymentService, which DOES do
                // KRW→NPR FX (configured margin + fee + USD prefunding + revenue). `currency` then picks
                // the quoted leg: KRW = the collection, NPR = the merchant payout. For every other scheme
                // the router stays a dispatcher and the amount is submitted in `currency` as-is.
                result = failoverPaymentRouter.pay(
                        req.qrPayload(), amountKrw, req.userRef(), "OVERSEAS", req.payCurrency(),
                        limitSubject);
            } else if (PARTNER_SENDMN.equalsIgnoreCase(req.partner())) {
                result = sendmnPaymentService.pay(req.qrPayload(), amountKrw,
                        req.userRef(), SENDMN_PARTNER_ID);
            } else if (PARTNER_GMEREMIT.equalsIgnoreCase(req.partner())) {
                result = gmeremitPaymentService.pay(req.qrPayload(), amountKrw, req.userRef());
            } else {
                throw new IllegalArgumentException(
                        "Unsupported partner: " + req.partner()
                                + ". Supported: GMEREMIT, SENDMN");
            }
        } catch (TransactionLimitExceededException | CumulativeLimitExceededException
                 | LimitCheckUnavailableException | CorridorPricingUnavailableException ex) {
            // A limit refusal — and, since T4-1, an unpriceable-corridor refusal — IS a decline: feed the
            // DECLINE_SPIKE monitor before the structured error leaves the controller, so a burst of cap
            // rejections or a corridor that has lost its pricing is as visible as a scheme decline.
            if (declineSpikeMonitor != null) {
                declineSpikeMonitor.record(req.partner(),
                        qr.isKnown() ? qr.networkIdentifier() : null, false);
            }
            throw ex;
        }

        // DECLINE_SPIKE monitor (defect #5): record the outcome per partner + classified network so a
        // burst of declines on either dimension raises an ops alert. No-op when the feature is off.
        if (declineSpikeMonitor != null) {
            declineSpikeMonitor.record(
                    req.partner(),
                    qr.isKnown() ? qr.networkIdentifier() : null,
                    result.approved());
        }

        WalletPaymentResponse response = new WalletPaymentResponse(
                result.approved() ? "APPROVED" : "DECLINED",
                result.txnRef(),
                result.schemeTxnRef(),
                result.merchantName(),
                result.payAmountKrw() != null ? result.payAmountKrw().toPlainString() : null,
                result.feeKrw() != null ? result.feeKrw().toPlainString() : null,
                result.chargedKrw() != null ? result.chargedKrw().toPlainString() : null,
                result.committedAt(),
                result.declineReason(),
                result.fxApplied(),
                result.fxRate() != null ? result.fxRate().toPlainString() : null,
                result.payAmountMnt() != null ? result.payAmountMnt().toPlainString() : null,
                // Cross-border pay currency + amount (e.g. NPR) so the wallet shows the right figures;
                // null (omitted) for the domestic KRW path, leaving that response shape unchanged.
                //
                // T4-1: when FX was applied the foreign payout is the FX'd figure (payAmountMnt — the
                // generic "amount in the merchant's currency" slot), NOT the KRW leg. Before Nepal had
                // FX, payAmountKrw WAS the foreign amount (pass-through), so it was correct then and
                // would now report KRW under an NPR label. Non-FX cross-border schemes keep the old
                // mapping.
                result.payCurrency(),
                payAmountFor(result)
        );

        HttpStatus status = result.approved() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * POST /v1/pay/classify — GMEPay+ is the authority for what a scanned QR is. Given a raw QR
     * payload it returns the network, country, presentment mode and the currency GME will charge in
     * (Nepal Fonepay → NPR, ZeroPay → KRW), resolved through the same classifier + routing the pay
     * path uses. The wallet calls this BEFORE amount entry to display the corridor/currency that
     * GMEPay+ resolved, instead of guessing locally. No payment is executed and nothing is charged.
     *
     * <pre>{ "qrPayload": "&lt;raw EMVCo QR&gt;" }  →  { supported, network, country, currency, mode, scheme }</pre>
     */
    @PostMapping("/classify")
    public ResponseEntity<FailoverPaymentRouter.QrClassification> classify(
            @RequestBody WalletPaymentRequest req) {
        if (req.qrPayload() == null || req.qrPayload().isBlank()) {
            throw new IllegalArgumentException("qrPayload is required");
        }
        // Use the same direction the failover pay path uses (OVERSEAS) so classify resolves the
        // exact route the payment will take.
        if (failoverPaymentRouter != null) {
            return ResponseEntity.ok(
                    failoverPaymentRouter.classifyQr(req.qrPayload(), "OVERSEAS"));
        }
        // Router unavailable (minimal config): fall back to static classification, currency by
        // country. Still GMEPay+-sourced — the wallet must not hardcode it.
        Classification c = QrSchemeClassifier.classify(req.qrPayload());
        String currency = "NP".equalsIgnoreCase(c.country()) ? "NPR"
                : "MN".equalsIgnoreCase(c.country()) ? "MNT"
                : "KR".equalsIgnoreCase(c.country()) ? "KRW" : null;
        return ResponseEntity.ok(new FailoverPaymentRouter.QrClassification(
                c.isKnown(), c.networkIdentifier(), c.country(), currency,
                c.isKnown() ? c.mode().name() : null, null));
    }

    /**
     * The {@code payAmount} field: the amount in {@code payCurrency}. When the corridor applied FX
     * (Nepal KRW→NPR) that is the FX'd payout; otherwise the amount as submitted. Null for the
     * domestic KRW path, which omits both fields.
     */
    @Nullable
    private static String payAmountFor(WalletResult result) {
        if (result.payCurrency() == null) {
            return null;
        }
        BigDecimal amount = Boolean.TRUE.equals(result.fxApplied()) && result.payAmountMnt() != null
                ? result.payAmountMnt()
                : result.payAmountKrw();
        return amount == null ? null : amount.toPlainString();
    }

    /** True when the classified QR network is ZeroPay (domestic path stays on the existing services). */
    /**
     * T3-6: the scheme a NON-failover wallet payment is actually dispatched to, for the operating-hours
     * gate. Both direct corridors are statically known — {@code partner=SENDMN} is an explicit corridor
     * selection dispatched to {@link SendmnPaymentService} (scheme {@code SENDMN}) and
     * {@code partner=GMEREMIT} is the ZeroPay domestic path ({@code ZEROPAY}) — so no scheme code is
     * ever inferred from the QR's network identifier here. Any other partner value is rejected below as
     * unsupported, so it asserts no scheme.
     */
    @Nullable
    private static String directCorridorSchemeRef(String partner) {
        if (PARTNER_SENDMN.equalsIgnoreCase(partner)) {
            return "SENDMN";
        }
        if (PARTNER_GMEREMIT.equalsIgnoreCase(partner)) {
            return "ZEROPAY";
        }
        return null;
    }

    private static boolean isZeroPayNetwork(String networkIdentifier) {
        return networkIdentifier != null
                && networkIdentifier.toLowerCase(java.util.Locale.ROOT).contains("zeropay");
    }

    // ---- idempotency helpers ----

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    /**
     * Resolves a partner alias to the STABLE numeric partner id used to scope the idempotency key.
     * Reuses the well-known sandbox constants ({@code GMEREMIT=1}, {@code SENDMN=2}); any other
     * alias (e.g. a failover-routed cross-border partner) derives a stable positive id from the
     * upper-cased alias hash so keys stay scoped without a config-registry round-trip.
     */
    private static long resolvePartnerId(String partner) {
        if (PARTNER_GMEREMIT.equalsIgnoreCase(partner)) {
            return GMEREMIT_PARTNER_ID;
        }
        if (PARTNER_SENDMN.equalsIgnoreCase(partner)) {
            return SENDMN_PARTNER_ID;
        }
        String alias = partner == null ? "" : partner.toUpperCase(java.util.Locale.ROOT);
        // Fold the alias hash into a stable large positive id, kept clear of the 1/2 reserved ids.
        return 1_000_000L + (Integer.toUnsignedLong(alias.hashCode()));
    }

    /**
     * SHA-256 over a canonical serialization of the fields that DEFINE the payment: qrPayload,
     * amount, resolved currency, partner alias, userRef. Stable field order + explicit separators so
     * an identical retry hashes identically while any payload change flips the hash (→ 422 reuse).
     */
    private static String requestHash(WalletPaymentRequest req) {
        String canonical = String.join("\n",
                "qrPayload=" + nullSafe(req.qrPayload()),
                "amount=" + nullSafe(req.amountKrw()),
                "currency=" + req.payCurrency(),
                "partner=" + nullSafe(req.partner()),
                "userRef=" + nullSafe(req.userRef()));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a JVM
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Maps the recorded HTTP status back for replay. Only the two outcomes this endpoint produces
     * are stored: 201 CREATED (APPROVED) and 422 (business DECLINED). We persist an enum
     * ({@link PaymentStatus}) on the row, so APPROVED↔CREATED and everything else↔422.
     */
    private static PaymentStatus toPaymentStatus(int httpStatus) {
        return httpStatus == HttpStatus.CREATED.value() ? PaymentStatus.APPROVED : PaymentStatus.FAILED;
    }

    private static HttpStatus toHttpStatus(PaymentStatus status) {
        return status == PaymentStatus.APPROVED ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
    }

    /**
     * POST /v1/pay/{schemeTxnRef}/refund — refund a wallet payment.
     *
     * <p>Calls the scheme adapter's cancel/refund path with the {@code schemeApprovalCode}
     * (authorise-level authId) that was stored at payment time. The caller MUST pass the
     * {@code schemeApprovalCode} (authId) in the request body — this is what sim-scheme's
     * {@code /payments/{authId}/refund} endpoint requires, NOT the commit-level schemeTxnRef.
     *
     * <p>Response: 200 OK with {@link WalletRefundResponse}.
     * 422 if the scheme declines the refund (already refunded, etc.).
     *
     * <p>T2-7: the optional {@code schemeId} body field routes the scheme-side refund to the adapter the
     * payment was actually executed on. A cross-border corridor with no scheme refund path (SENDMN /
     * NEPAL — both single-shot) now answers 422 with the structured
     * {@code errorCode=SCHEME_OPERATION_UNSUPPORTED} — an explicit "this cannot be refunded at the
     * scheme, escalate to the manual reversal process" — instead of the ZeroPay decline the scheme-less
     * cancel used to produce. Nothing downstream (transaction status, revenue-ledger) is touched on that
     * path, so no half-applied refund is recorded.
     *
     * <h2>T2-6 — this path was recording the wrong thing twice</h2>
     * <ol>
     *   <li>It patched the transaction to <b>{@code REVERSED}</b>, not {@code REFUNDED}. {@code refundedAt}
     *       is stamped only on entry to {@code REFUNDED}, so every wallet refund was invisible to
     *       {@code GET /v1/transactions/refunded} — and therefore to settlement's cross-date claw-back. It
     *       now patches {@code REFUNDED}, which is also what emits the {@code payment.reversed} event that
     *       runs revenue reversal and notifies the partner.</li>
     *   <li>It posted a <b>ZERO rounding residual</b> ({@code postRoundingResidual(ref + "-REFUND", 0, KRW)})
     *       — an amount of nothing, in the wrong account, under a reference no other posting uses. Nothing
     *       was booked. It now posts a real reversal journal for the refunded amount via the existing
     *       {@code REVENUE_REVERSAL} / {@code RECEIVABLE_PARTNER} pair.</li>
     * </ol>
     * The refunded amount comes from the request, or from the original payment when the request omits it.
     * When neither is available nothing is journalled and the response carries no amount — an honest blank
     * rather than a zero that reads as "booked".
     */
    @PostMapping("/{schemeTxnRef}/refund")
    public ResponseEntity<WalletRefundResponse> refund(
            @PathVariable("schemeTxnRef") String schemeTxnRef,
            @RequestBody(required = false) WalletRefundRequest req) {

        // The authId (schemeApprovalCode) is passed in the request body.
        // If not provided, fall back to schemeTxnRef (best-effort for cases where authId == schemeTxnRef).
        String authId = (req != null && req.authId() != null && !req.authId().isBlank())
                ? req.authId()
                : schemeTxnRef;
        String reason = (req != null && req.reason() != null) ? req.reason() : "PARTNER_REFUND";
        String schemeId = (req != null && req.schemeId() != null && !req.schemeId().isBlank())
                ? req.schemeId()
                : null;
        java.math.BigDecimal requestedAmount = req != null ? req.amount() : null;
        String requestedCurrency = req != null ? req.currency() : null;

        if (schemeClient == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, "Scheme client not configured", "SCHEME_CLIENT_UNCONFIGURED"));
        }

        // T2-6: resolve the original payment BEFORE the scheme call so an over-refund is refused without
        // touching the scheme. A basis we cannot read only blocks a PARTIAL refund (see WalletRefundBasis).
        WalletRefundBasis basis;
        try {
            basis = resolveWalletRefundBasis(schemeTxnRef, requestedAmount, requestedCurrency);
        } catch (RefundAmountInvalidException ex) {
            log.warn("Wallet refund of {} rejected ({}): {}", schemeTxnRef, ex.code(), ex.getMessage());
            return ResponseEntity.status(ex.retryable()
                            ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, ex.getMessage(), ex.code()));
        }

        try {
            schemeClient.cancelPayment(new SchemeClient.CancelRequest(
                    authId, reason, schemeId, basis.schemePartialAmount(), basis.currency()));
        } catch (SchemeOperationNotSupportedException ex) {
            // NOT a decline: the corridor has no scheme refund round-trip at all. Surface it verbatim
            // with its stable code so the caller stops retrying and escalates.
            log.warn("Refund unsupported by scheme {} for schemeTxnRef={} authId={}: {}",
                    ex.schemeId(), schemeTxnRef, authId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, ex.getMessage(), ex.code()));
        } catch (PartialRefundNotSupportedException ex) {
            // The adapter cannot express a partial refund; a full cancel would over-refund at the scheme.
            log.warn("Partial refund unsupported by scheme {} for schemeTxnRef={}: {}",
                    ex.schemeId(), schemeTxnRef, ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, ex.getMessage(), ex.code()));
        } catch (RuntimeException ex) {
            log.warn("Refund failed for schemeTxnRef={} authId={}: {}", schemeTxnRef, authId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, ex.getMessage(), "SCHEME_REFUND_FAILED"));
        }

        // Record the refund in transaction-mgmt (resilient) — REFUNDED, carrying the cumulative refunded
        // KRW so refundedAt is stamped, findRefundedOn finds it, the claw-back has a magnitude, and the
        // payment.reversed event fires.
        if (transactionClient != null) {
            try {
                transactionClient.commitStatus(schemeTxnRef,
                        TransactionClient.StatusPatch.refund(
                                PaymentStatus.REFUNDED, schemeTxnRef, authId, null,
                                basis.cumulativeRefundedKrw()));
            } catch (RuntimeException ex) {
                log.warn("transaction-mgmt REFUNDED update failed for {}: {}", schemeTxnRef, ex.getMessage());
            }
        }

        // Book a REAL reversal for the refunded amount (was: a zero rounding residual).
        if (revenueLedgerClient != null && basis.amount() != null && basis.amount().signum() > 0
                && basis.currency() != null) {
            try {
                revenueLedgerClient.postReversalJournal(schemeTxnRef, basis.amount(), basis.currency());
            } catch (RuntimeException ex) {
                log.warn("revenue-ledger refund post failed for {}: {}", schemeTxnRef, ex.getMessage());
            }
        } else if (revenueLedgerClient != null) {
            log.warn("wallet refund of {} booked NO reversal journal: the refunded amount is unknown "
                    + "(request carried none and the original payment was unreadable). Nothing is posted "
                    + "rather than a zero that would read as booked.", schemeTxnRef);
        }

        return ResponseEntity.ok(new WalletRefundResponse(
                "REFUNDED",
                schemeTxnRef,
                authId,
                Instant.now().toString(),
                null,
                null,
                basis.amount(),
                basis.currency()
        ));
    }

    /**
     * Resolves and validates the wallet refund's amount against the original payment (T2-6).
     *
     * <p>Mirrors {@code PaymentOrchestrator.planRefund}'s rules on the wallet path, which has its own
     * (pre-orchestrator) refund flow: cumulative refunds may not exceed the original, the currency may not
     * differ from the collection currency, and an unreadable original blocks a PARTIAL refund but not a full
     * one. It deliberately does NOT move float — the wallet path never did, and adding a float leg here would
     * be a second, divergent money path rather than a fix.
     */
    private WalletRefundBasis resolveWalletRefundBasis(String txnRef,
                                                       java.math.BigDecimal requestedAmount,
                                                       String requestedCurrency) {
        TransactionClient.RefundBasis basis = transactionClient == null
                ? null
                : transactionClient.findRefundBasis(txnRef).orElse(null);

        if (basis == null || basis.collectionAmount() == null) {
            if (requestedAmount != null) {
                throw RefundAmountInvalidException.basisUnavailable(txnRef,
                        "the original wallet payment could not be read, so a partial refund cannot be "
                                + "validated");
            }
            return new WalletRefundBasis(null, null, null, true);
        }

        java.math.BigDecimal original = basis.collectionAmount();
        String currency = basis.collectionCurrency();
        java.math.BigDecimal already = basis.alreadyRefunded();

        if (requestedAmount == null) {
            java.math.BigDecimal remaining = original.subtract(already);
            if (remaining.signum() <= 0) {
                throw RefundAmountInvalidException.exceedsOriginal(txnRef,
                        java.math.BigDecimal.ZERO, already, original, currency);
            }
            return new WalletRefundBasis(remaining, currency, original, true);
        }

        if (requestedAmount.signum() <= 0) {
            throw RefundAmountInvalidException.invalid(txnRef,
                    "the refund amount must be positive, got " + requestedAmount.toPlainString());
        }
        if (requestedCurrency != null && currency != null
                && !requestedCurrency.equalsIgnoreCase(currency)) {
            throw RefundAmountInvalidException.invalid(txnRef,
                    "refund currency " + requestedCurrency + " is not the original collection currency "
                            + currency);
        }
        java.math.BigDecimal cumulative = already.add(requestedAmount);
        if (cumulative.compareTo(original) > 0) {
            throw RefundAmountInvalidException.exceedsOriginal(txnRef, requestedAmount, already,
                    original, currency);
        }
        return new WalletRefundBasis(requestedAmount, currency, cumulative,
                cumulative.compareTo(original) == 0);
    }

    /**
     * The validated wallet refund amounts.
     *
     * @param amount     this refund's amount, or null when it could not be determined
     * @param currency   the original collection currency
     * @param cumulative total refunded including this refund
     * @param full       true when this refund completes the transaction
     */
    private record WalletRefundBasis(java.math.BigDecimal amount, String currency,
                                     java.math.BigDecimal cumulative, boolean full) {

        /** Set ONLY for a partial refund, so an adapter that cannot express one refuses it. */
        java.math.BigDecimal schemePartialAmount() {
            return full ? null : amount;
        }

        /** Cumulative refunded amount, but only when it really is KRW (see the orchestrator's note). */
        java.math.BigDecimal cumulativeRefundedKrw() {
            return "KRW".equalsIgnoreCase(currency) ? cumulative : null;
        }
    }
}
