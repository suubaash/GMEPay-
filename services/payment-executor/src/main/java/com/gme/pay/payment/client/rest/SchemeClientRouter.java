package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.client.SchemeClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Scheme-keyed dispatch across the per-scheme adapter clients.
 *
 * <p>Historically the orchestrator autowired a single {@link SchemeClient} — the ZeroPay
 * {@link RestSchemeClient} (base-url {@code gmepay.scheme-adapter-zeropay.base-url}). With a
 * second live adapter ({@code scheme-adapter-nepal}) the client is no longer single-scheme,
 * so this router (now the {@code @Primary} {@link SchemeClient}) picks a delegate by the
 * scheme code carried on each request and forwards the call.
 *
 * <h2>Routing</h2>
 * <ul>
 *   <li>{@code NEPAL} &rarr; {@link NepalRestSchemeClient} (Nepal adapter, single-phase submit).</li>
 *   <li>{@code SENDMN} &rarr; {@link SendmnRestSchemeClient} (SendMN/QPay adapter,
 *       verify-qr + Confirm two-step folded into one submit).</li>
 *   <li>anything else / unknown / null &rarr; the default {@link RestSchemeClient}
 *       (ZeroPay). Its behaviour and base-url default are <strong>unchanged</strong>.</li>
 * </ul>
 *
 * <p>This router is no longer the {@code @Primary} {@link SchemeClient}: {@link ResilientSchemeClient}
 * decorates it (per-scheme circuit breaker + bulkhead) and is the primary bean. The router's own
 * scheme-keyed dispatch behaviour is unchanged.
 *
 * <p>The scheme code is read from {@code request.schemeId()} on submit and from the
 * explicit {@code schemeId} arg on {@code checkBalance}.
 *
 * <h2>Cancel routing (T2-7)</h2>
 * {@code cancelPayment(CancelRequest)} now carries the scheme code and routes exactly like
 * {@code submitMpm}, so a Nepal/SendMN cancel or refund reaches ITS OWN adapter client and raises a
 * structured {@link com.gme.pay.payment.domain.SchemeOperationNotSupportedException} (both schemes are
 * single-shot and expose no cancel) instead of silently posting to
 * {@code /internal/scheme/zeropay/cancel} and returning a ZeroPay decline. The legacy scheme-less
 * two-arg {@code cancelPayment} still routes to the ZeroPay default, unchanged, for callers that
 * genuinely do not know the scheme.
 *
 * <p>Per-scheme adapter base-urls are configured as {@code gmepay.scheme-adapters.<CODE>.base-url}
 * (e.g. {@code gmepay.scheme-adapters.NEPAL.base-url=http://localhost:18091}); each keyed
 * client reads its own key. ZeroPay keeps its legacy
 * {@code gmepay.scheme-adapter-zeropay.base-url} key untouched.
 */
@Component
public class SchemeClientRouter implements SchemeClient {

    private final SchemeClient defaultClient;
    private final Map<String, SchemeClient> byScheme;

    public SchemeClientRouter(RestSchemeClient zeropayClient,
                              NepalRestSchemeClient nepalClient,
                              SendmnRestSchemeClient sendmnClient) {
        this.defaultClient = zeropayClient;
        this.byScheme = Map.of(
                NepalRestSchemeClient.SCHEME_CODE, nepalClient,
                SendmnRestSchemeClient.SCHEME_CODE, sendmnClient);
    }

    /** Resolve the delegate for a scheme code; falls back to the ZeroPay default. */
    private SchemeClient route(String schemeId) {
        if (schemeId == null || schemeId.isBlank()) {
            return defaultClient;
        }
        return byScheme.getOrDefault(schemeId.trim().toUpperCase(Locale.ROOT), defaultClient);
    }

    @Override
    public MpmSubmitResponse submitMpm(MpmSubmitRequest request) {
        return route(request.schemeId()).submitMpm(request);
    }

    @Override
    public CpmSubmitResponse submitCpm(CpmSubmitRequest request) {
        return route(request.schemeId()).submitCpm(request);
    }

    @Override
    public BalanceCheckResult checkBalance(String schemeId, BigDecimal amount, String currency) {
        return route(schemeId).checkBalance(schemeId, amount, currency);
    }

    @Override
    public void cancelPayment(String schemeTxnRef, String reason) {
        // Legacy scheme-less call: no code to route on, so it keeps hitting the ZeroPay default.
        // Scheme-aware callers must use cancelPayment(CancelRequest) — see the class doc (T2-7).
        defaultClient.cancelPayment(schemeTxnRef, reason);
    }

    /** T2-7: route the cancel/refund by scheme code, exactly like {@link #submitMpm}. */
    @Override
    public void cancelPayment(CancelRequest request) {
        route(request.schemeId()).cancelPayment(request.schemeTxnRef(), request.reason());
    }

    @Override
    public LookupStatus lookupStatus(String schemeId, String reference) {
        // Route the anti-double-charge probe (ADR-016 §4) to the scheme's own adapter.
        return route(schemeId).lookupStatus(schemeId, reference);
    }
}
