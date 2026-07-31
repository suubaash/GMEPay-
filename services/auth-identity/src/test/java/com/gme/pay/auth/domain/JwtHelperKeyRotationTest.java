package com.gme.pay.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.auth.domain.JwtHelper.Outcome;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T0-6 (rotation half) — {@code kid}-selected verification.
 *
 * <p>The behaviours pinned here are the whole reason the key set exists:
 *
 * <ul>
 *   <li>a token signed by the ACTIVE key verifies;</li>
 *   <li>a token signed by a still-ACCEPTED predecessor verifies — this is the overlap window that
 *       turns rotation from a cutover into an operation;</li>
 *   <li>an UNKNOWN {@code kid} is rejected <b>without trying the other keys</b>;</li>
 *   <li>a token with NO {@code kid} is rejected (pinned decision, see {@link JwtHelper});</li>
 *   <li>a retired key stops verifying the moment it leaves the set.</li>
 * </ul>
 */
class JwtHelperKeyRotationTest {

    private static final String KEY_V1 = "1a2b3c4d5e6f70819200aabbccddeeff";
    private static final String KEY_V2 = "ff00112233445566778899aabbccddee";
    private static final String KEY_V3 = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
    private static final Instant DEMOTED = Instant.parse("2026-07-28T09:00:00Z");
    private static final long TTL = 1800L;

    private static JwtHelper signingWith(String active, String... accepted) {
        List<JwtKeySet.Key> previous = java.util.Arrays.stream(accepted)
                .map(s -> JwtKeySet.retiredKey(s, DEMOTED))
                .toList();
        return new JwtHelper(JwtKeySet.of(active, previous), TTL);
    }

    private static String headerOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                          StandardCharsets.UTF_8);
    }

    // ── the header ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every minted token carries the active key's kid in its header")
    void mintedTokenIsStamped() {
        JwtHelper helper = signingWith(KEY_V2, KEY_V1);
        String token = helper.issue("svc:x", Map.of());

        assertThat(headerOf(token))
                .contains("\"alg\":\"HS256\"")
                .contains("\"kid\":\"" + JwtKeySet.kidFor(KEY_V2) + "\"");
        assertThat(helper.activeKid()).isEqualTo(JwtKeySet.kidFor(KEY_V2));
        // The kid names the ACTIVE key, never one of the accepted predecessors.
        assertThat(headerOf(token)).doesNotContain(JwtKeySet.kidFor(KEY_V1));
    }

    // ── the overlap window ────────────────────────────────────────────────────

    @Test
    @DisplayName("a token signed with the ACTIVE key verifies")
    void activeKeyVerifies() {
        JwtHelper helper = signingWith(KEY_V2, KEY_V1);
        String token = helper.issue("svc:x", Map.of("role", "ADMIN"));

        JwtHelper.VerificationResult result = helper.verifyDetailed(token);
        assertThat(result.outcome()).isEqualTo(Outcome.VALID);
        assertThat(result.claims().subject()).isEqualTo("svc:x");
        assertThat(result.kid()).isEqualTo(JwtKeySet.kidFor(KEY_V2));
    }

    @Test
    @DisplayName("a token signed with a still-ACCEPTED previous key verifies — the rotation window")
    void previousKeyStillVerifies() {
        // Minted before the rotation, by a process whose active key was V1…
        String tokenFromV1 = signingWith(KEY_V1).issue("svc:x", Map.of());
        // …and presented after it, to a process now signing with V2 but still accepting V1.
        JwtHelper afterRotation = signingWith(KEY_V2, KEY_V1);

        JwtHelper.VerificationResult result = afterRotation.verifyDetailed(tokenFromV1);
        assertThat(result.outcome()).isEqualTo(Outcome.VALID);
        assertThat(result.claims().subject()).isEqualTo("svc:x");
        assertThat(result.kid()).isEqualTo(JwtKeySet.kidFor(KEY_V1));
        assertThat(afterRotation.verify(tokenFromV1)).isNotNull();
    }

    @Test
    @DisplayName("two predecessors can be accepted at once (an overlapping second rotation)")
    void twoPreviousKeysBothVerify() {
        String fromV1 = signingWith(KEY_V1).issue("svc:a", Map.of());
        String fromV2 = signingWith(KEY_V2).issue("svc:b", Map.of());
        JwtHelper helper = signingWith(KEY_V3, KEY_V2, KEY_V1);

        assertThat(helper.verifyDetailed(fromV1).outcome()).isEqualTo(Outcome.VALID);
        assertThat(helper.verifyDetailed(fromV2).outcome()).isEqualTo(Outcome.VALID);
        assertThat(helper.verifyDetailed(helper.issue("svc:c", Map.of())).outcome())
                .isEqualTo(Outcome.VALID);
    }

    @Test
    @DisplayName("once the predecessor is RETIRED its tokens stop verifying — the window closed")
    void retiredKeyStopsVerifying() {
        String fromV1 = signingWith(KEY_V1).issue("svc:x", Map.of());

        JwtHelper.VerificationResult result = signingWith(KEY_V2).verifyDetailed(fromV1);
        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN_KID);
        assertThat(result.claims()).isNull();
    }

    // ── rejection paths ───────────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown kid is rejected as UNKNOWN_KID — and no other key is tried")
    void unknownKidIsRejectedWithoutFallback() {
        // Signed with V3, which this helper does not hold, but re-labelled to a kid it also does
        // not hold. If verification fell back to trying every configured key, a token whose
        // signature happens to match one of them would slip through; more importantly the kid
        // would be decorative, so a retired key could be honoured via a token naming a live one.
        JwtHelper helper = signingWith(KEY_V2, KEY_V1);
        String foreign = signingWith(KEY_V3).issue("svc:attacker", Map.of());

        JwtHelper.VerificationResult result = helper.verifyDetailed(foreign);
        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN_KID);
        assertThat(result.kid()).isEqualTo(JwtKeySet.kidFor(KEY_V3));
        assertThat(result.claims()).isNull();
        assertThat(helper.verify(foreign)).isNull();
    }

    @Test
    @DisplayName("a token whose kid names an accepted key but whose signature is another key's is rejected")
    void kidMustMatchTheSignature() {
        // Body + header from a V1-signed token (kid = V1, accepted), signature swapped for one made
        // with V3. Proves the selected key is the ONLY one used: no second attempt happens.
        JwtHelper helper = signingWith(KEY_V2, KEY_V1);
        String v1Token = signingWith(KEY_V1).issue("svc:x", Map.of());
        String v3Token = signingWith(KEY_V3).issue("svc:x", Map.of());

        String spliced = v1Token.substring(0, v1Token.lastIndexOf('.') + 1)
                + v3Token.substring(v3Token.lastIndexOf('.') + 1);

        assertThat(helper.verifyDetailed(spliced).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    @DisplayName("a token with NO kid is rejected — pinned decision, no fallback to the active key")
    void unkiddedTokenIsRejected() {
        // Exactly the shape this service minted before T0-6: {"alg":"HS256","typ":"JWT"}.
        JwtHelper helper = signingWith(KEY_V2, KEY_V1);
        String legacy = legacyUnkiddedToken(KEY_V2, "svc:x");

        // The signature is genuinely correct for the active key. It is still rejected: accepting it
        // would keep an un-versioned verification path permanently open, which is the thing T0-6
        // closes. The cost is bounded — pre-upgrade tokens live at most one max TTL.
        assertThat(helper.verifyDetailed(legacy).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(helper.verify(legacy)).isNull();
    }

    @Test
    @DisplayName("a header declaring a different alg is rejected at parse time")
    void wrongAlgIsRejected() {
        JwtHelper helper = signingWith(KEY_V2);
        String noneAlg = crafted("{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\""
                                 + JwtKeySet.kidFor(KEY_V2) + "\"}", "svc:x");

        assertThat(helper.verifyDetailed(noneAlg).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    @DisplayName("garbage in the header segment is rejected, not thrown")
    void unparseableHeaderIsRejected() {
        JwtHelper helper = signingWith(KEY_V2);
        assertThat(helper.verifyDetailed("!!!.eyJhIjoxfQ.sig").outcome()).isEqualTo(Outcome.INVALID);
        assertThat(helper.verifyDetailed("onlyonepart").outcome()).isEqualTo(Outcome.INVALID);
        assertThat(helper.verifyDetailed(null).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    @DisplayName("an expired token signed by an accepted predecessor reports EXPIRED, not UNKNOWN_KID")
    void expiredPredecessorTokenIsExpired() {
        // The distinction matters operationally: EXPIRED is routine, UNKNOWN_KID means a key was
        // retired too early. They must not be conflated.
        String expired = new JwtHelper(JwtKeySet.active(KEY_V1), -10).issue("svc:x", Map.of());
        JwtHelper helper = signingWith(KEY_V2, KEY_V1);

        JwtHelper.VerificationResult result = helper.verifyDetailed(expired);
        assertThat(result.outcome()).isEqualTo(Outcome.EXPIRED);
        assertThat(result.kid()).isEqualTo(JwtKeySet.kidFor(KEY_V1));
    }

    // ── helpers that mint tokens this codebase can no longer produce ──────────

    private static String legacyUnkiddedToken(String secret, String subject) {
        return crafted("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", subject, secret);
    }

    private static String crafted(String headerJson, String subject) {
        return crafted(headerJson, subject, KEY_V2);
    }

    private static String crafted(String headerJson, String subject, String secret) {
        long now = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"" + subject + "\",\"jti\":\"j\",\"iat\":" + now
                + ",\"exp\":" + (now + TTL) + "}";
        String h = b64(headerJson.getBytes(StandardCharsets.UTF_8));
        String p = b64(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = h + "." + p;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return signingInput + "."
                    + b64(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
