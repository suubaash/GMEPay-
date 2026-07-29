package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.client.SmartRouterClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * In-process fallback {@link SmartRouterClient} for tests and a no-router sandbox (ADR-016 §2).
 *
 * <p>Active only when the real {@link RestSmartRouterClient} is NOT wired — i.e. when
 * {@code gmepay.smart-router.base-url} is unset (see {@code @ConditionalOnMissingBean}). It
 * maps a handful of well-known network identifiers to a single-candidate list so existing
 * behaviour (one ZeroPay candidate, one Nepal candidate) is preserved without a running
 * smart-router: a single-candidate resolution makes the failover loop behave exactly like the
 * pre-ADR-016 direct dispatch.
 *
 * <h2>Fixture map</h2>
 * <ul>
 *   <li>{@code com.zeropay} &rarr; ZeroPay (schemeId {@code zeropay}, partner 0).</li>
 *   <li>{@code fonepay.com} / {@code nepalpay} / {@code khalti} &rarr; Nepal
 *       (schemeId {@code NEPAL}, partner 1).</li>
 *   <li>{@code qpay} / {@code mn.qpay} / {@code sendmn} &rarr; SendMN
 *       (schemeId {@code SENDMN}, partner 2 — placeholder QPay identifiers, see
 *       {@code QrSchemeClassifier}).</li>
 *   <li>anything else &rarr; empty (caller declines / SCHEME_UNAVAILABLE).</li>
 * </ul>
 */
@Component
@Primary
@ConditionalOnMissingBean(RestSmartRouterClient.class)
public class FixtureSmartRouterClient implements SmartRouterClient {

    @Override
    public List<PartnerSchemeView> resolve(String network, String country, String mode, String direction) {
        if (network == null) {
            return List.of();
        }
        String n = network.toLowerCase(Locale.ROOT);
        if (n.contains("zeropay")) {
            return List.of(new PartnerSchemeView(0L, "GMEREMIT/ZeroPay", "zeropay", 0));
        }
        if (n.contains("fonepay") || n.contains("nepalpay") || n.contains("npqr")
                || n.contains("khalti") || n.contains("mobank")) {
            return List.of(new PartnerSchemeView(1L, "Nepal", "NEPAL", 0));
        }
        if (n.contains("qpay") || n.contains("sendmn")) {
            // SendMN (Mongolia). "qpay" also matches the parsed EMVCo sub-tag-00 form
            // "mn.qpay..."; identifiers are placeholders until SendMN confirms the real
            // QPay AID (QR scheme plan Phase 2). Partner 2 = the SENDMN sandbox partner.
            return List.of(new PartnerSchemeView(2L, "SendMN", "SENDMN", 0));
        }
        return List.of();
    }
}
