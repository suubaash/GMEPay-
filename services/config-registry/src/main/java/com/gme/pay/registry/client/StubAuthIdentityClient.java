package com.gme.pay.registry.client;

import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OPT-IN {@link AuthIdentityClient}: mints credential material in-process so a
 * unit slice or a single-service local run needs no running auth-identity
 * (ADR-014 stub discipline, same seam as {@code StubKybClient}).
 *
 * <h2>⚠ This bean issues DEAD credentials</h2>
 *
 * <p>The strings it returns are locally-generated randomness: no
 * {@code api_keys} row exists anywhere, so nothing can verify them — an
 * {@code X-API-Key} minted here resolves to {@code found=false} at
 * auth-identity's {@code POST /internal/auth/keys/resolve} and every signed
 * partner request is rejected. {@link #revokeKey} is likewise a no-op, so a
 * "revoked" credential stays usable if it ever were real.
 *
 * <h2>Why it is no longer the default (gap T1-1)</h2>
 *
 * <p>This class used to be an unconditional {@code @Component} while
 * {@link com.gme.pay.registry.client.rest.RestAuthIdentityClient} required an
 * explicit {@code gmepay.auth-identity.client=rest}. That selector was set for
 * ops-partner-bff but never for config-registry, so EVERY deployed environment
 * silently handed operators fabricated go-live credentials through the
 * activation modal. The defaults are now inverted: the REST client wins when
 * the selector is absent, and this stub must be asked for by name
 * ({@code gmepay.auth-identity.client=stub}). Misconfiguration now fails loudly
 * at activation time (502 from the REST client) instead of quietly succeeding
 * with worthless material.
 *
 * <p>The stub honours the SEC-09 contract shape: the "plaintext" exists only on
 * the returned record; nothing is retained in this class.
 */
@Component
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "stub")
public class StubAuthIdentityClient implements AuthIdentityClient {

    private static final Logger log = LoggerFactory.getLogger(StubAuthIdentityClient.class);

    /** Unambiguous base32-ish alphabet (no 0/O, 1/l) — display-friendly tokens. */
    private static final char[] ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Loud startup banner: selecting the stub is a deliberate act, and anyone
     * reading the log of an environment that handed out unusable credentials
     * must be able to find the reason in one grep (gap T1-1).
     */
    public StubAuthIdentityClient() {
        log.warn("gmepay.auth-identity.client=stub — partner credentials issued by this instance"
                + " are LOCAL RANDOMNESS and cannot be verified by auth-identity. Never use this"
                + " selector in an environment whose activation output reaches a real partner.");
    }

    @Override
    public IssuedKey issueKey(IssueKeyCommand command) {
        String keyId = command.keyPrefix() + random(24);
        String secret = command.secretPrefix() + random(40);
        return new IssuedKey(keyId, secret, command.expiresAt());
    }

    @Override
    public void revokeKey(String keyId) {
        // Stateless stub — nothing to revoke locally.
    }

    private static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
