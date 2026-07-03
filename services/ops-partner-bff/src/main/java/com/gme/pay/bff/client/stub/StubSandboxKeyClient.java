package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.SandboxKeyClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase-1 in-memory implementation of {@link SandboxKeyClient}. It reproduces
 * auth-identity's SEC-09 §4 credential contract without a database or a live
 * auth-identity:
 *
 * <ul>
 *   <li>the one-time plaintext {@code apiKey} is returned exactly once from
 *       {@link #issue};</li>
 *   <li>the store keeps only a salted one-way hash of the secret — the
 *       plaintext is never held in a field after {@code issue} returns, and
 *       {@link #listForPartner} never exposes it;</li>
 *   <li>every key is {@code SANDBOX}-scoped (test-prefixed {@code pk_test_} /
 *       {@code sk_test_}, matching auth-identity's SANDBOX prefixes) so a
 *       sandbox key is visibly distinct from a production key.</li>
 * </ul>
 *
 * <p>This is the default (matchIfMissing) so the portal Get-Started flow works
 * standalone. When {@code gmepay.auth-identity.client=rest}, the
 * {@link com.gme.pay.bff.client.rest.RestSandboxKeyClient} (which forwards to
 * auth-identity {@code POST/GET /internal/auth/keys} with
 * {@code environment=SANDBOX}) wins as {@code @Primary} and this stub is not
 * created — matching the established Rest/Stub selector idiom
 * (see {@code RestRbacAdminClient} / {@code StubRbacAdminClient}).
 */
@Component
@ConditionalOnProperty(name = "gmepay.auth-identity.client", havingValue = "stub",
        matchIfMissing = true)
public class StubSandboxKeyClient implements SandboxKeyClient {

    /** SANDBOX scope marker — sandbox keys must not authorize production calls. */
    public static final String SCOPE_SANDBOX = "SANDBOX";

    /** Public key-id prefix (test = sandbox), mirrors auth-identity's pk_test_. */
    static final String KEY_PREFIX = "pk_test_";

    /** Secret prefix (test = sandbox), mirrors auth-identity's sk_test_. */
    static final String SECRET_PREFIX = "sk_test_";

    private static final int KEY_RANDOM_LENGTH = 24;
    private static final int SECRET_RANDOM_LENGTH = 40;

    /** Unambiguous alphabet (no 0/O, 1/l) — mirrors auth-identity's token alphabet. */
    private static final char[] ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /** Stored row — everything BUT the plaintext secret. */
    private record StoredKey(String keyId, String prefix, String secretHash,
                             String scope, Instant createdAt) {}

    /** partnerId -> issued sandbox keys (hash + metadata only; no plaintext). */
    private final Map<String, List<StoredKey>> store = new ConcurrentHashMap<>();

    @Override
    public IssuedSandboxKey issue(String partnerId, String name) {
        String safePartner = partnerId == null ? "anon" : partnerId;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        String keyId = KEY_PREFIX + randomToken(KEY_RANDOM_LENGTH);
        String secret = SECRET_PREFIX + randomToken(SECRET_RANDOM_LENGTH);
        // Non-secret display prefix: first 12 chars of the key id.
        String prefix = keyId.length() > 12 ? keyId.substring(0, 12) : keyId;

        // SEC-09 §4: persist only the salted one-way hash of the secret.
        StoredKey row = new StoredKey(keyId, prefix, sha256Hex(secret), SCOPE_SANDBOX, now);
        store.computeIfAbsent(safePartner, k -> new ArrayList<>()).add(row);

        // The plaintext leaves here exactly once and is never stored.
        return new IssuedSandboxKey(keyId, secret, prefix, SCOPE_SANDBOX, now);
    }

    @Override
    public List<SandboxKeyView> listForPartner(String partnerId) {
        String safePartner = partnerId == null ? "anon" : partnerId;
        List<StoredKey> rows = store.getOrDefault(safePartner, List.of());
        return rows.stream()
                .sorted(Comparator.comparing(StoredKey::createdAt).reversed())
                .map(r -> new SandboxKeyView(r.keyId(), r.prefix(), r.scope(), r.createdAt()))
                .toList();
    }

    private static String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String sha256Hex(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JCA algorithm — never absent on a real JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
