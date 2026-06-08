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

    @Bean
    public JwtHelper jwtHelper(
            @Value("${gme.auth.jwt.signing-secret:changeme-at-least-32-chars-long!!}") String secret,
            @Value("${gme.auth.jwt.access-token-ttl-seconds:1800}") long ttlSeconds) {
        return new JwtHelper(secret, ttlSeconds);
    }
}
