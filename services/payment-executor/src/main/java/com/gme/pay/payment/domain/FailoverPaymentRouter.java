package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.QrSchemeClassifier.Classification;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.SchemeClient.LookupStatus;
import com.gme.pay.payment.domain.client.SmartRouterClient;
import com.gme.pay.payment.domain.client.SmartRouterClient.PartnerSchemeView;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.ExecutionAttemptEntity;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * QR-classified multi-partner routing with failover on the wallet MPM scan {@code /v1/pay} path
 * (ADR-016 §3–4). Retires the {@code NepalQrDetector} country-string stopgap: routing is driven by
 * the QR's own network identifier, resolved via {@link SmartRouterClient} into an ordered candidate
 * list, then dispatched with failover.
 *
 * <h2>Algorithm (ADR-016 §3)</h2>
 * <pre>
 * classify QR -> (network, country, mode)
 * candidates  = smartRouter.resolve(network, country, MPM, direction)   # ordered by priority
 * for candidate in candidates (bounded by max-hops):
 *     result = submitMpm(schemeId = candidate.schemeId, ...)
 *     APPROVED           -> return APPROVED
 *     business decline   -> return DECLINED         # TERMINAL, no failover
 *     technical failure  -> lookupStatus(candidate, reference)
 *                              APPROVED / PENDING -> return that (anti-double-charge)
 *                              else               -> continue to next candidate
 * exhausted -> SCHEME_UNAVAILABLE
 * </pre>
 *
 * <h2>Money-safety (ADR-016 §4)</h2>
 * <ul>
 *   <li><b>Business declines are terminal</b> — a decline is authoritative; retrying another
 *       partner cannot help and risks a double-charge. Canonical business-decline codes are the
 *       {@code invalid_qr / unsupported_qr / receiver_not_found / receiver_not_eligible /
 *       insufficient / duplicate_reference} family (matched case-insensitively).</li>
 *   <li><b>Anti-double-charge guard</b> — a technical failure (timeout / 5xx / SCHEME_UNAVAILABLE /
 *       connect) leaves the outcome unknown, so before failing over we call
 *       {@link SchemeClient#lookupStatus} by our stable {@code reference}; APPROVED/PENDING short-
 *       circuits the loop (no second submit) and we return that outcome. Only a genuine
 *       NOT_FOUND / REJECTED lets us move to the next candidate.</li>
 *   <li><b>Bounded</b> — walks at most {@code gmepay.routing.max-hops} (default 3) candidates.</li>
 *   <li>Every attempt (partner / outcome / reason) is recorded in the attempt trail (resilient).</li>
 * </ul>
 *
 * <p>A single-candidate resolution (e.g. a ZeroPay QR resolving to one ZeroPay candidate) walks the
 * loop exactly once, producing the same result as the pre-ADR-016 direct dispatch.
 */
@Service
public class FailoverPaymentRouter {

    private static final Logger log = LoggerFactory.getLogger(FailoverPaymentRouter.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(KST);

    /**
     * Canonical business-decline codes (ADR-016 §4): authoritative, never fail over. Normalised to
     * lower-case, non-alphanumerics stripped, and matched by containment so adapter variants like
     * {@code INVALID_QR}, {@code invalid-qr}, {@code RECEIVER_NOT_FOUND} all resolve.
     */
    private static final Set<String> BUSINESS_DECLINE_MARKERS = Set.of(
            "invalidqr",
            "unsupportedqr",
            "receivernotfound",
            "receivernoteligible",
            "insufficient",
            "duplicatereference",
            "merchantnotfound",
            "merchantinactive");

    private final SmartRouterClient smartRouterClient;
    private final SchemeClient schemeClient;
    private final ExecutionAttemptRepository attemptRepository;
    private final int maxHops;
    @Nullable private final TransactionClient transactionClient;
    /** T4-2: per-txn + cumulative regulatory limit gate. Never null (see {@link WalletLimitGate#disabled()}). */
    private final WalletLimitGate limitGate;

    @Autowired
    public FailoverPaymentRouter(SmartRouterClient smartRouterClient,
                                 SchemeClient schemeClient,
                                 ExecutionAttemptRepository attemptRepository,
                                 @Value("${gmepay.routing.max-hops:3}") int maxHops,
                                 @Nullable TransactionClient transactionClient,
                                 @Nullable WalletLimitGate limitGate) {
        this.smartRouterClient = smartRouterClient;
        this.schemeClient = schemeClient;
        this.attemptRepository = attemptRepository;
        this.maxHops = maxHops > 0 ? maxHops : 3;
        this.transactionClient = transactionClient;
        this.limitGate = limitGate != null ? limitGate : WalletLimitGate.disabled();
    }

    /** Test constructor — no transaction client, default max-hops. */
    FailoverPaymentRouter(SmartRouterClient smartRouterClient,
                          SchemeClient schemeClient,
                          ExecutionAttemptRepository attemptRepository) {
        this(smartRouterClient, schemeClient, attemptRepository, 3, null, null);
    }

    /** Test constructor with the T4-2 limit gate wired. */
    FailoverPaymentRouter(SmartRouterClient smartRouterClient,
                          SchemeClient schemeClient,
                          ExecutionAttemptRepository attemptRepository,
                          @Nullable WalletLimitGate limitGate) {
        this(smartRouterClient, schemeClient, attemptRepository, 3, null, limitGate);
    }

    /**
     * Executes a scanned-QR MPM payment with QR-classified failover routing.
     *
     * @param qrPayload raw EMVCo/JSON QR scanned by the wallet
     * @param amount    wallet amount, expressed in {@code payCurrency}
     * @param userRef   wallet user reference (for logging)
     * @param direction {@code DOMESTIC} / {@code OVERSEAS} filter context for resolution
     * @return {@link WalletResult} — approved with scheme refs, or declined with a reason.
     */
    public WalletResult pay(String qrPayload, BigDecimal amount, String userRef, String direction) {
        // Back-compat overload: no explicit pay currency → derive per resolved scheme (ZeroPay=KRW,
        // NEPAL=NPR). Preserves the exact pre-currency behaviour for existing callers/tests.
        return pay(qrPayload, amount, userRef, direction, null);
    }

    /**
     * As {@link #pay(String, BigDecimal, String, String)} but with an explicit wallet-supplied pay
     * {@code currency}. When non-null it is the authoritative pay currency (e.g. NPR for a Fonepay
     * scan); {@code amount} is then interpreted in that currency and passed straight through to the
     * scheme adapter (the Nepal adapter converts NPR→paisa). When null the currency is derived from
     * the resolved scheme, keeping the ZeroPay/KRW path identical. No KRW→foreign FX is done here.
     */
    public WalletResult pay(String qrPayload, BigDecimal amount, String userRef, String direction,
                            @Nullable String payCurrency) {
        // Back-compat overload — no known limit subject (legacy/test callers). The gate logs that it is
        // running unconstrained rather than pretending limits were checked.
        return pay(qrPayload, amount, userRef, direction, payCurrency, WalletPartnerRef.none());
    }

    /**
     * As {@link #pay(String, BigDecimal, String, String, String)} but carrying the limit subject —
     * the wallet partner whose {@code partner_limits} row (V020) governs this payment (T4-2).
     *
     * <p>The gate runs ONCE per payment, after routing candidates are resolved and before the first
     * (irreversible) scheme submit, keyed on a stable payment-level reference so a failover across
     * candidates cannot double-charge the cumulative cap. It is reversed when the payment ends in a
     * terminal decline or exhausts its candidates; an APPROVED/PENDING outcome keeps the cap consumed
     * (fail-safe: a payment that may have landed must not free cap).
     *
     * <p>This is the LIVE cross-border wallet path (a Fonepay/NepalPay scan arrives here, not at
     * {@code NepalPaymentService}), so it is the enforcement point that actually matters for Nepal.
     */
    public WalletResult pay(String qrPayload, BigDecimal amount, String userRef, String direction,
                            @Nullable String payCurrency, WalletPartnerRef partner) {

        Classification classification = QrSchemeClassifier.classify(qrPayload);
        if (!classification.isKnown()) {
            log.warn("Unclassifiable QR (userRef={}) — declining as invalid_qr", userRef);
            return WalletResult.declined(null, "invalid_qr");
        }

        List<PartnerSchemeView> candidates = smartRouterClient.resolve(
                classification.networkIdentifier(),
                classification.country(),
                classification.mode().name(),
                direction);

        if (candidates == null || candidates.isEmpty()) {
            log.warn("No routing candidates for network={} country={} (userRef={})",
                    classification.networkIdentifier(), classification.country(), userRef);
            return WalletResult.declined(null, "unsupported_qr");
        }

        // T4-2 regulatory gate — per-transaction min/max USD + cumulative daily/monthly/annual USD +
        // the daily velocity count, on the wallet partner's V020 limits row. Keyed on ONE stable
        // payment-level reference (candidate references change per hop) so failover cannot double-charge
        // the cap. The USD basis uses the currency the payment will execute in via the platform's single
        // USD-basis source (UsdAmountBasis); a configured cap that cannot be evaluated fails CLOSED.
        String limitRef = "FO-LIMIT-" + UUID.randomUUID();
        String gateCurrency = resolveCurrency(payCurrency, candidates.get(0).schemeId());
        WalletLimitGate.LimitCharge limitCharge;
        try {
            limitCharge = limitGate.enforce(partner, limitRef, amount, gateCurrency);
        } catch (TransactionLimitExceededException | CumulativeLimitExceededException
                 | LimitCheckUnavailableException ex) {
            log.warn("Failover limit gate refused partner={} ref={} amount={} {}: {}",
                    partner.code(), limitRef, amount, gateCurrency, ex.getMessage());
            // Persist the attempt the way other declines are, then surface the structured limit error.
            recordAttempt(candidates.get(0), limitRef, PaymentStatus.FAILED, null,
                    ex.getClass().getSimpleName());
            throw ex;
        }

        int hops = Math.min(candidates.size(), maxHops);
        String lastReason = "SCHEME_UNAVAILABLE";

        for (int i = 0; i < hops; i++) {
            PartnerSchemeView candidate = candidates.get(i);
            String reference = "FO-" + candidate.schemeId() + "-" + UUID.randomUUID();
            // Wallet-supplied pay currency is authoritative when present (e.g. NPR for Fonepay);
            // otherwise fall back to the scheme-derived currency (ZeroPay=KRW). No FX is applied.
            String currency = resolveCurrency(payCurrency, candidate.schemeId());

            try {
                SchemeClient.MpmSubmitResponse resp = schemeClient.submitMpm(
                        new SchemeClient.MpmSubmitRequest(
                                reference,
                                null,                 // merchant resolved by the adapter from the QR
                                amount,
                                currency,
                                candidate.schemeId(),
                                qrPayload));

                if (isApproved(resp)) {
                    recordAttempt(candidate, reference, PaymentStatus.APPROVED, resp.schemeTxnRef(), null);
                    return approvedResult(candidate, resp, amount, currency);
                }

                // Adapter returned a non-2xx-mapped-to-success but a non-APPROVED status body:
                // treat as a business decline (authoritative, terminal).
                String reason = resp.schemeApprovalCode() != null ? resp.schemeApprovalCode() : "declined";
                recordAttempt(candidate, reference, PaymentStatus.FAILED, resp.schemeTxnRef(), reason);
                log.warn("Candidate {} declined (status={}) — TERMINAL, no failover",
                        candidate.schemeId(), reason);
                limitGate.reverse(limitCharge);   // T4-2: terminal decline ⇒ do not consume cap
                return WalletResult.declined(null, reason);

            } catch (SchemeDeclinedException ex) {
                String code = ex.schemeErrorCode();
                if (isBusinessDecline(code)) {
                    // TERMINAL — a business decline is authoritative; failover cannot help and
                    // risks a double-charge.
                    recordAttempt(candidate, reference, PaymentStatus.FAILED, null, code);
                    log.warn("Candidate {} business-declined ({}) — TERMINAL, no failover",
                            candidate.schemeId(), code);
                    limitGate.reverse(limitCharge);   // T4-2: terminal decline ⇒ do not consume cap
                    return WalletResult.declined(null, code);
                }
                // A decline with a non-business code is treated as a technical failure: probe then
                // fail over.
                lastReason = code != null ? code : "SCHEME_UNAVAILABLE";
                LookupStatus guarded = guardBeforeFailover(candidate, reference, code);
                if (guarded != null) {
                    return guardedResult(candidate, reference, guarded);
                }
                recordAttempt(candidate, reference, PaymentStatus.FAILED, null, code);

            } catch (PaymentException ex) {
                // Technical failure (timeout / 5xx / SCHEME_UNAVAILABLE / connect). Outcome unknown.
                lastReason = "SCHEME_UNAVAILABLE";
                LookupStatus guarded = guardBeforeFailover(candidate, reference, ex.getMessage());
                if (guarded != null) {
                    return guardedResult(candidate, reference, guarded);
                }
                recordAttempt(candidate, reference, PaymentStatus.FAILED, null, ex.getMessage());
            }
            // else: continue to the next candidate
        }

        log.warn("All {} candidate(s) exhausted for userRef={} — SCHEME_UNAVAILABLE", hops, userRef);
        // T4-2: nothing was paid on any candidate (the anti-double-charge guard short-circuits when a
        // charge may have landed), so the cumulative cap is returned.
        limitGate.reverse(limitCharge);
        return WalletResult.declined(null, lastReason);
    }

    /**
     * Classifies a scanned QR without executing a payment: GMEPay+ is the authority for what the QR
     * <em>is</em> — its network, country, presentment mode, and the currency GME will charge in
     * (ZeroPay→KRW, Nepal Fonepay→NPR). The wallet calls this to display the corridor/currency that
     * GMEPay+ resolved, rather than guessing locally. Currency is derived from the resolved scheme,
     * so it reflects the actual route (incl. failover roster), not just the raw QR.
     *
     * @param direction {@code OUTBOUND} for a customer paying a merchant abroad (Nepal), etc.
     * @return a {@link QrClassification}; {@code supported=false} when unclassifiable or unroutable.
     */
    public QrClassification classifyQr(String qrPayload, String direction) {
        Classification c = QrSchemeClassifier.classify(qrPayload);
        if (!c.isKnown()) {
            return QrClassification.unsupported(null, null);
        }
        List<PartnerSchemeView> candidates = smartRouterClient.resolve(
                c.networkIdentifier(), c.country(), c.mode().name(), direction);
        if (candidates == null || candidates.isEmpty()) {
            // Recognised network but no live route → report the corridor, no chargeable currency.
            return QrClassification.unsupported(c.networkIdentifier(), c.country());
        }
        String schemeId = candidates.get(0).schemeId();
        return new QrClassification(true, c.networkIdentifier(), c.country(),
                currencyFor(schemeId), c.mode().name(), schemeId);
    }

    /**
     * GMEPay+'s authoritative answer to "what is this QR?" — surfaced to the wallet for display.
     */
    public record QrClassification(
            boolean supported,
            @Nullable String network,   // QR network identifier, e.g. "fonepay.com"
            @Nullable String country,   // ISO-3166 alpha-2, e.g. "NP"
            @Nullable String currency,  // GME-authoritative pay currency, e.g. "NPR" (null if unroutable)
            @Nullable String mode,      // presentment mode: "MPM" / "CPM"
            @Nullable String scheme     // resolved scheme id, e.g. "NEPAL" (null if unroutable)
    ) {
        static QrClassification unsupported(@Nullable String network, @Nullable String country) {
            return new QrClassification(false, network, country, null, null, null);
        }
    }

    /**
     * Anti-double-charge guard (ADR-016 §4). On a technical failure, ask the scheme whether the
     * reference was in fact paid/pending. Returns the {@link LookupStatus} that must short-circuit
     * the loop (APPROVED / PENDING), or {@code null} when it is safe to fail over
     * (NOT_FOUND / REJECTED).
     */
    @Nullable
    private LookupStatus guardBeforeFailover(PartnerSchemeView candidate, String reference, String cause) {
        LookupStatus status;
        try {
            status = schemeClient.lookupStatus(candidate.schemeId(), reference);
        } catch (RuntimeException ex) {
            // The guard itself failed — cannot confirm a payment, so best-effort NOT_FOUND
            // (fail over). The submit-side attempt is still recorded by the caller.
            log.warn("lookupStatus failed for {} ref={}: {} — proceeding to fail over",
                    candidate.schemeId(), reference, ex.getMessage());
            return null;
        }
        if (status == LookupStatus.APPROVED || status == LookupStatus.PENDING) {
            log.warn("Technical failure on {} (cause={}) but lookupStatus={} — NOT failing over "
                    + "(anti-double-charge)", candidate.schemeId(), cause, status);
            return status;
        }
        // NOT_FOUND / REJECTED — no charge landed; safe to fail over.
        return null;
    }

    /** Builds the result for a guard-confirmed APPROVED/PENDING outcome. */
    private WalletResult guardedResult(PartnerSchemeView candidate, String reference, LookupStatus status) {
        if (status == LookupStatus.APPROVED) {
            recordAttempt(candidate, reference, PaymentStatus.APPROVED, reference, "confirmed-by-lookup");
            return WalletResult.approved(reference, reference, candidate.partnerName(),
                    null, BigDecimal.ZERO, null, KST_FMT.format(Instant.now()));
        }
        // PENDING — record and surface as a (non-approved) pending outcome; do NOT retry.
        recordAttempt(candidate, reference, PaymentStatus.PENDING, reference, "pending-by-lookup");
        return WalletResult.declined(candidate.partnerName(), "PENDING");
    }

    private WalletResult approvedResult(PartnerSchemeView candidate,
                                        SchemeClient.MpmSubmitResponse resp,
                                        BigDecimal amount,
                                        String currency) {
        recordTransaction(candidate, resp, amount, currency);
        String committedAt = KST_FMT.format(resp.approvedAt() != null ? resp.approvedAt() : Instant.now());
        // A KRW (domestic ZeroPay) approval keeps the existing KRW-only response shape (payCurrency
        // omitted); a non-KRW scheme (Nepal/NPR) carries its pay currency + amount so the wallet
        // shows the right figures. No fee is applied on this cross-border pass-through.
        if ("KRW".equalsIgnoreCase(currency)) {
            return WalletResult.approved(
                    resp.schemeTxnRef(),
                    resp.schemeTxnRef(),
                    candidate.partnerName(),
                    amount,
                    BigDecimal.ZERO,
                    amount,
                    committedAt);
        }
        return WalletResult.approvedInCurrency(
                resp.schemeTxnRef(),
                resp.schemeTxnRef(),
                candidate.partnerName(),
                amount,
                BigDecimal.ZERO,
                amount,
                committedAt,
                currency);
    }

    private static boolean isApproved(SchemeClient.MpmSubmitResponse resp) {
        String code = resp.schemeApprovalCode();
        if (code == null) {
            // ZeroPay returns a distinct approval code + txnRef; a present txnRef with no explicit
            // status is an approval (the adapter maps declines to SchemeDeclinedException / 422).
            return resp.schemeTxnRef() != null;
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        // ZeroPay approval codes (e.g. "ZP_OK", "AP-...") are approvals; Nepal maps status->code
        // so "SUCCESS"/"APPROVED" are approvals and "FAILED"/"DECLINED" are not.
        if (c.equals("FAILED") || c.equals("DECLINED") || c.equals("REJECTED")) {
            return false;
        }
        return true;
    }

    /** True when the scheme decline code is an authoritative business decline (never fail over). */
    private static boolean isBusinessDecline(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String norm = code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String marker : BUSINESS_DECLINE_MARKERS) {
            if (norm.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** ZeroPay is KRW; other schemes (Nepal, SendMN) carry their local currency handled by the adapter. */
    private static String currencyFor(String schemeId) {
        if (schemeId != null && schemeId.toLowerCase(Locale.ROOT).contains("zeropay")) {
            return "KRW";
        }
        if (schemeId != null && schemeId.equalsIgnoreCase("NEPAL")) {
            return "NPR";
        }
        if (schemeId != null && schemeId.equalsIgnoreCase("SENDMN")) {
            return "MNT";
        }
        return "KRW";
    }

    /**
     * The wallet-supplied pay currency wins when present (authoritative intent from the scan);
     * otherwise derive it from the resolved scheme. Keeps the KRW/ZeroPay path unchanged when the
     * wallet sends no currency.
     */
    private static String resolveCurrency(@Nullable String payCurrency, String schemeId) {
        if (payCurrency != null && !payCurrency.isBlank()) {
            return payCurrency.toUpperCase(Locale.ROOT);
        }
        return currencyFor(schemeId);
    }

    private void recordTransaction(PartnerSchemeView candidate,
                                   SchemeClient.MpmSubmitResponse resp,
                                   BigDecimal amount,
                                   String currency) {
        if (transactionClient == null) {
            return;
        }
        try {
            String dir = "KRW".equalsIgnoreCase(currency) ? "DOMESTIC" : "OVERSEAS";
            TransactionClient.CreateResult created = transactionClient.createPending(
                    new TransactionClient.CreateRequest(
                            candidate.partnerId(), resp.schemeTxnRef(), candidate.schemeId(),
                            dir, "MPM", amount, currency, amount, currency, null, null, null));
            transactionClient.commitStatus(created.txnRef(),
                    new TransactionClient.StatusPatch(
                            PaymentStatus.APPROVED, resp.schemeTxnRef(), resp.schemeApprovalCode(),
                            null, resp.approvedAt() != null ? resp.approvedAt() : Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("transaction-mgmt unavailable for failover payment {} — continuing: {}",
                    resp.schemeTxnRef(), ex.getMessage());
        }
    }

    private void recordAttempt(PartnerSchemeView candidate, String reference,
                               PaymentStatus outcome, String schemeTxnRef, String reason) {
        try {
            ExecutionAttemptEntity entity = new ExecutionAttemptEntity(
                    reference,
                    candidate.partnerId(),
                    reference,
                    candidate.schemeId(),
                    PaymentMode.MPM,
                    outcome,
                    Instant.now());
            entity.setSchemeTxnRef(schemeTxnRef);
            entity.setFailureReason(reason);
            entity.setCompletedAt(Instant.now());
            attemptRepository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist failover attempt for {} ({}): {}",
                    candidate.schemeId(), reference, ex.getMessage());
        }
    }
}
