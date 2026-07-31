package com.gme.pay.payment.domain.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Interface to the Scheme Adapter Layer (e.g. scheme-adapter-zeropay).
 * Implementations send to ZeroPay over mTLS; tests use hand-written fakes.
 */
public interface SchemeClient {

    /**
     * Submits an MPM payment to the scheme for real-time approval.
     *
     * @param request the scheme submit request
     * @return approval result from the scheme
     * @throws SchemeDeclinedException if the scheme synchronously declines
     * @throws SchemeTimeoutException  if the scheme does not respond within SLA
     */
    MpmSubmitResponse submitMpm(MpmSubmitRequest request);

    /**
     * Cancels a previously approved payment at the scheme.
     *
     * <p><b>Per-scheme primitive.</b> Each adapter client implements this; it carries no scheme code,
     * so callers that know which scheme the payment was executed on MUST use
     * {@link #cancelPayment(CancelRequest)} instead so the call is dispatched to the right adapter
     * (see {@code SchemeClientRouter}). Calling this two-arg form on the router routes to the ZeroPay
     * default — correct only for ZeroPay.
     *
     * @param schemeTxnRef the scheme's own transaction reference
     * @param reason       cancellation reason text
     */
    void cancelPayment(String schemeTxnRef, String reason);

    /**
     * Scheme-routed cancel/refund (T2-7). Mirrors {@link #submitMpm}: the scheme code rides on the
     * request so {@code SchemeClientRouter} can dispatch to the per-scheme adapter client instead of
     * unconditionally hitting ZeroPay's {@code /internal/scheme/zeropay/cancel}.
     *
     * <p>The default delegates to the two-arg {@link #cancelPayment(String, String)} so every
     * per-scheme client and hand-written test fake stays valid without change; only the router and the
     * resilience decorator override it to route/guard by scheme.
     *
     * @param request the cancel request (scheme txn ref + reason + scheme code)
     * @throws com.gme.pay.payment.domain.SchemeOperationNotSupportedException when the resolved scheme
     *         has no cancel/refund round-trip (e.g. NEPAL, SENDMN — both single-shot)
     */
    default void cancelPayment(CancelRequest request) {
        cancelPayment(request.schemeTxnRef(), request.reason());
    }

    /**
     * Submits a CPM payment (customer-presented mode) to the scheme.
     *
     * @param request the CPM submit request
     * @return approval result
     */
    CpmSubmitResponse submitCpm(CpmSubmitRequest request);

    /**
     * Pre-submit balance inquiry (SETTLEMENT_FLOW_SPEC §7.2): does GME hold enough prepaid balance
     * (+ any per-scheme credit) WITH the scheme to fund {@code amount}? Called during AUTHORIZE so a
     * known-short scheme float is declined BEFORE the customer is charged — minimising the
     * scheme-outage-after-charge case at confirm time.
     *
     * <p>Default returns allowed (no gate) so existing hand-written test fakes remain valid;
     * {@code RestSchemeClient} performs the real inquiry against the scheme adapter.
     */
    default BalanceCheckResult checkBalance(String schemeId, BigDecimal amount, String currency) {
        return new BalanceCheckResult(true, null);
    }

    /**
     * Idempotent status lookup by our stable payment {@code reference} (ADR-016 §4,
     * anti-double-charge guard). On a technical failure (timeout / 5xx / connect / unavailable)
     * the payment outcome is <em>unknown</em>, so before failing over to another partner the
     * router asks the scheme whether this reference was in fact paid or is pending — and if so it
     * returns that outcome instead of retrying (which would double-charge).
     *
     * <p>Default returns {@link LookupStatus#NOT_FOUND} (best-effort) so existing hand-written
     * fakes and any adapter without a status endpoint remain valid; a scheme that cannot answer
     * definitively must NOT falsely report a payment as absent, so NOT_FOUND is the safe default
     * only for schemes that genuinely have no record.
     *
     * @param schemeId  scheme CODE (routes to the right adapter via {@link SchemeClient}).
     * @param reference our stable payment reference used at submit time.
     * @return the scheme's view of the reference.
     */
    default LookupStatus lookupStatus(String schemeId, String reference) {
        return LookupStatus.NOT_FOUND;
    }

    /**
     * Asks the scheme's adapter to decode a scanned QR into the merchant's DISPLAY NAME (T4-4).
     *
     * <p>Exists for corridors whose merchant identity lives ONLY at the scheme: the Nepal adapter
     * resolves the receiver from the QR itself ({@code POST /internal/scheme/nepal/decode}) and the hub
     * performs no merchant lookup on that path at all, so without this the Nepal receipt could never
     * carry a name. Schemes whose submit round-trip already reports the name use
     * {@link MpmSubmitResponse#merchantName()} instead, and the ZeroPay/GMEREMIT path uses its own
     * merchant-qr-data lookup — so the default here returns null and only Nepal overrides it.
     *
     * <p><b>Best-effort by contract.</b> Implementations MUST NOT throw: this is a display-only lookup
     * on the money path, and a decode hiccup must degrade to "name unknown" (null → the UI's em dash),
     * never to a failed or reversed payment.
     *
     * @param schemeId    scheme CODE (routes to the right adapter)
     * @param qrPayload   the raw scanned QR string
     * @return the merchant display name, or null when this scheme cannot answer
     */
    default String resolveMerchantName(String schemeId, String qrPayload) {
        return null;
    }

    /** Outcome of an idempotent {@link #lookupStatus} probe. */
    enum LookupStatus {
        /** The scheme confirms this reference was paid — treat as APPROVED, do NOT retry. */
        APPROVED,
        /** The scheme has the reference in-flight — outcome not yet final; do NOT retry. */
        PENDING,
        /** The scheme processed and rejected this reference — a business decline. */
        REJECTED,
        /** The scheme has no record of this reference — safe to fail over to another partner. */
        NOT_FOUND
    }

    // ---- request/response value objects ----

    record MpmSubmitRequest(
            String txnRef,
            String merchantId,
            BigDecimal payoutAmount,
            String payoutCurrency,
            String schemeId,
            /** Raw EMVCo QR payload — required for ZeroPay MPM authorize call. */
            String qrPayload
    ) {
        /**
         * Backwards-compatible 5-arg factory for callers that do not supply a QR payload
         * (OVERSEAS path, cancellation path, existing tests).
         */
        public static MpmSubmitRequest of(String txnRef, String merchantId,
                                          BigDecimal payoutAmount, String payoutCurrency,
                                          String schemeId) {
            return new MpmSubmitRequest(txnRef, merchantId, payoutAmount, payoutCurrency, schemeId, null);
        }
    }

    /**
     * @param merchantName T4-4: the merchant DISPLAY NAME the scheme itself reported while executing
     *                     this payment, when it reports one. SendMN's verify-qr step returns the
     *                     Mongolian merchant's business name and the adapter client used to discard it
     *                     between verify-qr and Confirm; carrying it here is what lets the corridor
     *                     persist the name the SCHEME will show on its own side. Null for schemes that
     *                     report no name on the submit round-trip (ZeroPay — the hub resolves it from
     *                     merchant-qr-data instead; Nepal — see {@link #resolveMerchantName}).
     */
    record MpmSubmitResponse(
            String schemeApprovalCode,
            String schemeTxnRef,
            Instant approvedAt,
            String merchantName
    ) {
        /** Back-compat 3-arg form for schemes/fakes that report no merchant name. */
        public MpmSubmitResponse(String schemeApprovalCode, String schemeTxnRef, Instant approvedAt) {
            this(schemeApprovalCode, schemeTxnRef, approvedAt, null);
        }
    }

    /**
     * Scheme-routed cancel/refund request (T2-7).
     *
     * @param schemeTxnRef the scheme's own transaction reference (or the authorise-level authId for
     *                     the wallet refund path)
     * @param reason       cancellation reason text
     * @param schemeId     scheme CODE the payment was executed on (e.g. {@code "SENDMN"}). Null/blank
     *                     keeps the legacy behaviour (ZeroPay default) for callers that genuinely do
     *                     not know the scheme.
     */
    /**
     * A scheme-side cancel / refund instruction.
     *
     * @param schemeTxnRef the scheme's own transaction reference
     * @param reason       cancellation / refund reason text
     * @param schemeId     scheme CODE the payment was executed on; null = the ZeroPay default (T2-7)
     * @param partialAmount   T2-6: set ONLY for a PARTIAL refund — the amount to refund, which is strictly
     *                        less than what the scheme approved. {@code null} means "reverse the whole
     *                        thing", which is the only shape any adapter contract can express today (the
     *                        ZeroPay adapter's {@code /internal/scheme/zeropay/cancel} body carries a
     *                        {@code schemeTxnRef} and nothing else). A non-null value therefore does NOT
     *                        become a full cancel: {@code RestSchemeClient} refuses it with
     *                        {@code PARTIAL_REFUND_UNSUPPORTED} rather than silently over-refunding the
     *                        customer at the scheme. See {@link com.gme.pay.payment.domain.PartialRefundNotSupportedException}.
     * @param partialCurrency ISO currency of {@code partialAmount}; null when the refund is full
     */
    record CancelRequest(String schemeTxnRef, String reason, String schemeId,
                         BigDecimal partialAmount, String partialCurrency) {

        /** Full cancel/refund on a known scheme (T2-7 shape). */
        public CancelRequest(String schemeTxnRef, String reason, String schemeId) {
            this(schemeTxnRef, reason, schemeId, null, null);
        }

        /** Legacy scheme-less cancel (routes to the ZeroPay default). */
        public static CancelRequest of(String schemeTxnRef, String reason) {
            return new CancelRequest(schemeTxnRef, reason, null);
        }

        /** True when this instruction asks the scheme to refund only part of the approved amount. */
        public boolean isPartial() {
            return partialAmount != null;
        }
    }

    record CpmSubmitRequest(
            String txnRef,
            String qrToken,
            BigDecimal payoutAmount,
            String payoutCurrency,
            String schemeId
    ) {}

    record CpmSubmitResponse(
            String schemeApprovalCode,
            String schemeTxnRef,
            Instant approvedAt
    ) {}

    /** Result of a pre-submit scheme balance inquiry: whether GME may fund the amount, and the available figure. */
    record BalanceCheckResult(boolean allowed, BigDecimal available) {}
}

// Pull exception imports to the right package so they compile with the interface
