package com.gme.pay.gateway.partner;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The single place the partner edge's {@link PartnerCredentialService} is built (T0-7 / CISO#5).
 *
 * <h2>What was broken</h2>
 *
 * <p>{@code gateway.partner-credentials.source} defaulted to {@code stub} in both
 * {@code application.yml} and the properties class, and no deployment file overrode it — so every
 * environment authenticated partner API calls against {@code StubPartnerCredentialService}, a
 * hard-coded map whose api keys <em>and HMAC secrets</em> were published in this repository
 * ({@code pk_test_abc}/{@code sk_test_xyz}, {@code pk_test_no_mtls}/{@code sk_test_no_mtls}), with
 * {@code List.of()} as the IP allowlist (i.e. no IP restriction). Anyone holding a checkout could
 * sign valid requests as a real partner against the whole routed {@code /v1/**} surface.
 *
 * <h2>What it is now</h2>
 *
 * <ul>
 *   <li>The stub bean <b>does not exist in the shipped build</b>. It was deleted, not gated: a
 *       {@code @Profile} still ships the literals, and literals in a repo are literals an attacker
 *       has. Tests that need a fixture build one in the test source set
 *       ({@code com.gme.pay.gateway.partner.TestPartnerCredentials}).</li>
 *   <li>{@code source=stub} is <b>rejected at startup</b> with an explicit message rather than
 *       silently falling back to something. So is any other unrecognised value — a typo in a
 *       deployment file must not resolve to "accept everyone" or to a mystery bean.</li>
 *   <li>The default source is {@code config}: an operator-populated table whose secrets come from
 *       the environment. It is <b>empty by default</b>, so an unconfigured gateway answers 401 to
 *       every partner request.</li>
 *   <li>Every key is additionally checked against the real credential store (auth-identity's
 *       {@code api_keys}) for existence + liveness, and an unreachable store yields <b>503</b>
 *       (see {@link AuthIdentityVerifiedPartnerCredentialService}).</li>
 * </ul>
 *
 * <h2>What an operator must supply</h2>
 *
 * <ol>
 *   <li>{@code gateway.partner-credentials.partners[]} — one row per live partner: the {@code pk_…}
 *       api key auth-identity issued, the config-registry partner <b>code</b> as {@code partner-id},
 *       the one-time {@code sk_…} plaintext as {@code hmac-secret} (from a secret store, never a
 *       literal), and the partner's IP CIDR ranges / mTLS fingerprint.</li>
 *   <li>{@code GMEPAY_AUTH_IDENTITY_BASE_URL} and {@code GMEPAY_INTERNAL_AUTH_SECRET} — without
 *       both, the lifecycle check cannot run and every partner request is answered 503.</li>
 * </ol>
 */
@Configuration
public class PartnerCredentialConfig {

    private static final Logger log = LoggerFactory.getLogger(PartnerCredentialConfig.class);

    /** Credential sources a shipped build accepts. {@code stub} is deliberately absent. */
    static final List<String> ACCEPTED_SOURCES = List.of(ConfigPartnerCredentialProperties.SOURCE_CONFIG);

    @Bean
    public PartnerCredentialService partnerCredentialService(
            ConfigPartnerCredentialProperties props,
            WebClient.Builder webClientBuilder,
            @Value("${gmepay.auth-identity.base-url:http://auth-identity:8080}") String authIdentityBaseUrl,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret) {

        PartnerCredentialService signingMaterialSource = buildSigningMaterialSource(props);

        if (!props.isVerifyWithAuthIdentity()) {
            log.warn("gateway.partner-credentials.verify-with-auth-identity=false — presented api "
                    + "keys are NOT checked against auth-identity's credential store, so a key "
                    + "REVOKED upstream keeps working at this edge until the gateway config is "
                    + "edited. Acceptable only for isolated local runs (T0-7).");
            return signingMaterialSource;
        }

        return new AuthIdentityVerifiedPartnerCredentialService(
                signingMaterialSource,
                new AuthIdentityCredentialStatusClient(
                        webClientBuilder, authIdentityBaseUrl, internalSecret));
    }

    /**
     * Resolves the configured source, refusing to start on the removed stub or on anything
     * unrecognised. Throwing here fails context refresh — the gateway does not come up with an
     * unknown credential source, because "unknown" historically meant "the published stub".
     */
    private static PartnerCredentialService buildSigningMaterialSource(
            ConfigPartnerCredentialProperties props) {
        String source = props.getSource() == null
                ? "" : props.getSource().trim().toLowerCase(Locale.ROOT);

        if (ConfigPartnerCredentialProperties.SOURCE_STUB.equals(source)) {
            throw new IllegalStateException(failure(
                    "gateway.partner-credentials.source=stub is no longer supported. The stub "
                    + "authenticated partner API calls against api keys and HMAC secrets PUBLISHED "
                    + "in this repository, and it has been deleted from the shipped build. Use "
                    + "source=config and supply gateway.partner-credentials.partners[] with "
                    + "hmac-secret values from the environment"));
        }
        if (!ACCEPTED_SOURCES.contains(source)) {
            throw new IllegalStateException(failure(
                    "gateway.partner-credentials.source='" + props.getSource() + "' is not a "
                    + "recognised credential source (accepted: " + ACCEPTED_SOURCES + "). Refusing "
                    + "to guess: a mistyped source must not silently select a different credential "
                    + "store"));
        }
        return new ConfigPartnerCredentialService(props);
    }

    private static String failure(String reason) {
        return "api-gateway refuses to start: " + reason + ". (T0-7: the partner edge must "
                + "authenticate against the real credential store and fail closed; it must never "
                + "accept keys that are checked into source.)";
    }
}
