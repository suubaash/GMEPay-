package com.gme.pay.payment.web;

import com.gme.pay.payment.alert.DeclineSpikeMonitor;
import com.gme.pay.payment.domain.CumulativeLimitExceededException;
import com.gme.pay.payment.domain.FailoverPaymentRouter;
import com.gme.pay.payment.domain.GmeremitPaymentService;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.LimitCheckUnavailableException;
import com.gme.pay.payment.domain.OperationalGate;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.QrSchemeClassifier;
import com.gme.pay.payment.domain.QrSchemeClassifier.Classification;
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
 * unchanged, keeping the ₩500 fee. No KRW→foreign FX happens here.
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

        // Operations operational gate: refuse NEW payments while the platform is paused / in
        // maintenance, or when THIS payment's partner alias / classified network (route) is suspended.
        // Runs at the START, before any merchant lookup / scheme submit, so nothing irreversible fires
        // on a rejected payment. Confirm/refund of an in-flight txn does not enter this controller.
        Classification gateQr = QrSchemeClassifier.classify(req.qrPayload());
        if (operationalGate != null) {
            operationalGate.checkNewAuthorization(
                    req.partner(),
                    null,
                    gateQr.isKnown() ? gateQr.networkIdentifier() : null);
        }

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

        // T4-2: the regulatory limit subject is the WALLET partner (the issuer charging the customer),
        // whose alias IS its config-registry partner code — that is the licence whose per-txn /
        // cumulative caps apply, not the receiving partner a QR routes to.
        WalletPartnerRef limitSubject =
                WalletPartnerRef.of(req.partner(), resolvePartnerId(req.partner()));

        try {
            if (routeViaFailover) {
                // Non-ZeroPay networks routed via failover are cross-border (OVERSEAS) in this sandbox.
                // The wallet-supplied pay currency (default KRW when absent) is authoritative: a Fonepay
                // scan arrives as NPR and is executed in NPR, not mis-treated as KRW. The hub does NOT do
                // KRW→foreign FX — `amountKrw` is the amount already in `currency`.
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
                 | LimitCheckUnavailableException ex) {
            // A limit refusal IS a decline: feed the DECLINE_SPIKE monitor before the structured error
            // leaves the controller, so a burst of cap rejections is as visible as a scheme decline.
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
                result.payCurrency(),
                result.payCurrency() != null && result.payAmountKrw() != null
                        ? result.payAmountKrw().toPlainString() : null
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

    /** True when the classified QR network is ZeroPay (domestic path stays on the existing services). */
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

        if (schemeClient == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, "Scheme client not configured", "SCHEME_CLIENT_UNCONFIGURED"));
        }

        try {
            schemeClient.cancelPayment(new SchemeClient.CancelRequest(authId, reason, schemeId));
        } catch (SchemeOperationNotSupportedException ex) {
            // NOT a decline: the corridor has no scheme refund round-trip at all. Surface it verbatim
            // with its stable code so the caller stops retrying and escalates.
            log.warn("Refund unsupported by scheme {} for schemeTxnRef={} authId={}: {}",
                    ex.schemeId(), schemeTxnRef, authId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, ex.getMessage(), ex.code()));
        } catch (RuntimeException ex) {
            log.warn("Refund failed for schemeTxnRef={} authId={}: {}", schemeTxnRef, authId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, ex.getMessage(), "SCHEME_REFUND_FAILED"));
        }

        // Record the reversal in transaction-mgmt (resilient)
        if (transactionClient != null) {
            try {
                transactionClient.commitStatus(schemeTxnRef,
                        new TransactionClient.StatusPatch(
                                PaymentStatus.REVERSED, schemeTxnRef, authId, null, null));
            } catch (RuntimeException ex) {
                log.warn("transaction-mgmt REVERSED update failed for {}: {}", schemeTxnRef, ex.getMessage());
            }
        }

        // Reverse revenue-ledger entry (resilient)
        if (revenueLedgerClient != null) {
            try {
                revenueLedgerClient.postRoundingResidual(schemeTxnRef + "-REFUND",
                        java.math.BigDecimal.ZERO, "KRW");
            } catch (RuntimeException ex) {
                log.warn("revenue-ledger refund post failed for {}: {}", schemeTxnRef, ex.getMessage());
            }
        }

        return ResponseEntity.ok(new WalletRefundResponse(
                "REFUNDED",
                schemeTxnRef,
                authId,
                Instant.now().toString(),
                null
        ));
    }
}
