package com.gme.pay.registry.kyb;

import com.gme.pay.internalauth.InternalAuthHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} both kyb-adapter seams use, carrying the
 * service-to-service internal-auth token (gap T1-4).
 *
 * <p>kyb-adapter's {@code /v1/kyb/screen}, {@code /v1/kyb/verify} and
 * {@code /v1/kyb/result/**} routes are now behind the shared internal-auth filter
 * — they accept a partner's legal names, tax id and full UBO set and return a
 * compliance verdict. config-registry, the only legitimate caller, presents the
 * shared secret from {@code gmepay.internal-auth.secret} in the
 * {@code X-Gme-Internal} header.
 *
 * <p>Same idiom as {@code RestPrefundingCreditLimitClient}: a blank secret adds no
 * header and logs a WARN naming the environment variable, so the failure surfaces
 * as an honest 401 on a screening run (which the caller reports to the operator)
 * rather than as a silent bypass.
 */
final class KybInternalAuth {

    private static final Logger log = LoggerFactory.getLogger(KybInternalAuth.class);

    private KybInternalAuth() {
    }

    static RestClient gated(String baseUrl, String internalSecret, Class<?> caller) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            builder.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.internal-auth.secret is blank — {} will call kyb-adapter without the {}"
                            + " header and every screening / verification run will be rejected with"
                            + " 401. Set GMEPAY_INTERNAL_AUTH_SECRET to the platform's shared"
                            + " internal token.",
                    caller.getSimpleName(), InternalAuthHeaders.INTERNAL_TOKEN);
        }
        return builder.build();
    }
}
