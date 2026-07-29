package com.gme.pay.auth.config;

import com.gme.pay.auth.domain.InMemoryNonceStore;
import com.gme.pay.auth.domain.JwtHelper;
import com.gme.pay.auth.domain.NonceStore;
import com.gme.pay.auth.domain.PartnerCredentialPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Spring bean wiring for auth-identity.
 *
 * NonceStore: falls back to InMemoryNonceStore when no RedisNonceStore bean is present.
 *             Production deployment overrides this with a Redis-backed implementation.
 *
 * PartnerCredentialPort: falls back to a stub that always returns empty (unknown key).
 *                        Production deployment supplies a WebClient-based bean that
 *                        calls config-registry /internal/v1/credentials/resolve.
 *
 * JwtHelper: configured from application properties.
 */
@Configuration
public class AuthConfig {

    @Bean
    @ConditionalOnMissingBean(NonceStore.class)
    public NonceStore inMemoryNonceStore() {
        return new InMemoryNonceStore();
    }

    @Bean
    @ConditionalOnMissingBean(PartnerCredentialPort.class)
    public PartnerCredentialPort stubPartnerCredentialPort() {
        // Stub: returns empty for all keys — replaced by real adapter in production.
        return apiKey -> Optional.empty();
    }

    /**
     * T0-6: the signing secret has <b>no default</b>. It used to default to the literal
     * {@code changeme-at-least-32-chars-long!!} right here as well as in {@code application.yml},
     * so a deployment that never set {@code GME_AUTH_JWT_SIGNING_SECRET} (which was every
     * deployment — the variable appeared in no manifest) signed real capability tokens with a key
     * published in the repo. {@link JwtSigningKeyEnforcedConfig} refuses to start the service on a
     * blank, too-short, placeholder or previously-published value, so reaching this method at all
     * means a usable operator-supplied key is present.
     */
    @Bean
    public JwtHelper jwtHelper(
            @Value("${gme.auth.jwt.signing-secret:}") String secret,
            @Value("${gme.auth.jwt.access-token-ttl-seconds:1800}") long ttlSeconds) {
        return new JwtHelper(secret, ttlSeconds);
    }
}
