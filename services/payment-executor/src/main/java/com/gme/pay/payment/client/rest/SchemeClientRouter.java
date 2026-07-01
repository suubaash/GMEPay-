package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.client.SchemeClient;
import org.springframework.context.annotation.Primary;
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
 *   <li>anything else / unknown / null &rarr; the default {@link RestSchemeClient}
 *       (ZeroPay). Its behaviour and base-url default are <strong>unchanged</strong>.</li>
 * </ul>
 *
 * <p>The scheme code is read from {@code request.schemeId()} on submit and from the
 * explicit {@code schemeId} arg on {@code checkBalance}. {@code cancelPayment} carries no
 * scheme code and is single-phase-N/A for Nepal, so it always routes to the default
 * (ZeroPay) client — the only scheme with a cancel round-trip today.
 *
 * <p>Per-scheme adapter base-urls are configured as {@code gmepay.scheme-adapters.<CODE>.base-url}
 * (e.g. {@code gmepay.scheme-adapters.NEPAL.base-url=http://localhost:18091}); each keyed
 * client reads its own key. ZeroPay keeps its legacy
 * {@code gmepay.scheme-adapter-zeropay.base-url} key untouched.
 */
@Component
@Primary
public class SchemeClientRouter implements SchemeClient {

    private final SchemeClient defaultClient;
    private final Map<String, SchemeClient> byScheme;

    public SchemeClientRouter(RestSchemeClient zeropayClient, NepalRestSchemeClient nepalClient) {
        this.defaultClient = zeropayClient;
        this.byScheme = Map.of(NepalRestSchemeClient.SCHEME_CODE, nepalClient);
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
        // No scheme code on this call; cancel is a ZeroPay two-phase concept.
        defaultClient.cancelPayment(schemeTxnRef, reason);
    }
}
