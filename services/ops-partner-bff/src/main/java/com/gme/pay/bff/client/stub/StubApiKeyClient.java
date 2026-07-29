package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.ApiKeyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Standalone-boot fallback for {@link ApiKeyClient}: reports that NO key data is available.
 *
 * <h2>Why this returns nothing (gap register T1-3)</h2>
 *
 * <p>This class used to manufacture two credential-shaped rows per partner — a {@code PRIMARY} and a
 * {@code ROTATING} key with {@code gpk_live_<hash>} prefixes, a two-scope grant list and a
 * last-used timestamp — all derived from {@code partnerId.hashCode()}. Because no
 * {@code RestApiKeyClient} existed, that fiction was what <b>every</b> partner saw on the API Keys
 * page, and it was indistinguishable from real production credentials. A partner could reasonably
 * have tried to integrate against a prefix that authenticates nothing.
 *
 * <p>An empty list is the honest answer: in stub mode the BFF has no connection to the registry that
 * owns keys, so it knows of none. The Portal renders its "No API keys provisioned" empty state —
 * which is true — instead of two keys that do not exist. Real data requires
 * {@code gmepay.auth-identity.client=rest}, which activates
 * {@link com.gme.pay.bff.client.rest.RestApiKeyClient} against auth-identity; that selector is set
 * on every deploy target (docker-compose, Helm, run-fleet).
 *
 * <p>The bean is kept rather than deleted so the BFF and its unit slices still boot with no
 * auth-identity present. It logs at DEBUG so an operator asking "why is the API Keys page empty?"
 * finds the selector.
 */
@Component
public class StubApiKeyClient implements ApiKeyClient {

    private static final Logger log = LoggerFactory.getLogger(StubApiKeyClient.class);

    @Override
    public List<ApiKeyView> listForPartner(String partnerId) {
        log.debug("api-key list requested for partner '{}' but the BFF is in stub mode — reporting "
                + "no keys. Set GMEPAY_AUTH_IDENTITY_CLIENT=rest to read the real registry.",
                partnerId);
        return List.of();
    }
}
