package com.gme.pay.payment.domain.client;

import java.util.List;

/**
 * Resolves a classified QR into an <b>ordered list of candidate partner/scheme pairs</b>
 * (ADR-016 §2–3). Candidates are returned by ascending priority (best first); the
 * {@code FailoverPaymentRouter} walks them in order.
 *
 * <p>Backed by the {@code smart-router} service
 * ({@code GET {base}/v1/route/resolve?network=&country=&mode=&direction=}) when
 * {@code gmepay.smart-router.base-url} is configured; otherwise an in-process fixture
 * fallback ({@code FixtureSmartRouterClient}) resolves well-known networks so tests and a
 * no-router sandbox still route deterministically.
 */
public interface SmartRouterClient {

    /**
     * @param network   the QR's network identifier (e.g. {@code com.zeropay}, {@code fonepay.com})
     * @param country   ISO-3166 alpha-2 country from the QR (nullable)
     * @param mode      {@code MPM} / {@code CPM}
     * @param direction {@code DOMESTIC} / {@code OVERSEAS} (filter context)
     * @return ordered candidate list, highest-priority first; empty if none can serve it.
     */
    List<PartnerSchemeView> resolve(String network, String country, String mode, String direction);

    /**
     * A single routing candidate: a partner + the scheme code the payment-executor's
     * {@code SchemeClientRouter} dispatches on.
     *
     * @param partnerId   config-registry partner id
     * @param partnerName human-readable partner name (for the attempt trail / logs)
     * @param schemeId    scheme CODE used by {@code SchemeClientRouter} (e.g. {@code NEPAL},
     *                    {@code zeropay})
     * @param priority    ascending priority (0 = best)
     */
    record PartnerSchemeView(long partnerId, String partnerName, String schemeId, int priority) {
    }
}
