package com.gme.pay.registry.client;

import java.security.SecureRandom;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Default {@link AuthIdentityClient}: mints credential material in-process so
 * local dev and unit slices need no running auth-identity (ADR-014 stub
 * discipline, same seam as {@code StubKybClient}). Production activates
 * {@link com.gme.pay.registry.client.rest.RestAuthIdentityClient} via
 * {@code gmepay.auth-identity.client=rest}, which is {@code @Primary} and
 * shadows this bean.
 *
 * <p>The stub honours the SEC-09 contract shape: the "plaintext" exists only
 * on the returned record; nothing is retained in this class. (Locally issued
 * stub keys are of course not verifiable by a real auth-identity.)
 */
@Component
public class StubAuthIdentityClient implements AuthIdentityClient {

    /** Unambiguous base32-ish alphabet (no 0/O, 1/l) — display-friendly tokens. */
    private static final char[] ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

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
