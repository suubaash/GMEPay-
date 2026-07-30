package com.gme.pay.gateway.replay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Replay-protection configuration ({@code gateway.replay-protection.*}).
 *
 * <p>T0-7 found this namespace carrying two <em>dead</em> keys ({@code fail-open}, {@code store})
 * that nothing read, and removed them rather than leave them implying controls that did not exist.
 * They come back here only because they are now real: {@link #onStoreError} is read by
 * {@link com.gme.pay.gateway.filter.ReplayProtectionFilter}, and the store selection moved to the
 * one place that owns it ({@code gateway.shared-state.store}).
 */
@ConfigurationProperties(prefix = "gateway.replay-protection")
public class ReplayProtectionProperties {

    /**
     * What to do when the nonce store cannot answer.
     *
     * <p><b>There is deliberately no {@code ALLOW}.</b> A replay check that fails open is not a
     * replay check — it is a check an attacker can disable by making one Redis unreachable, which
     * is a strictly easier attack than forging a signature. So the only two answers offered are
     * "reject" and "degrade", and an unrecognised value fails binding and the service refuses to
     * start rather than resolving to something permissive.
     *
     * <ul>
     *   <li>{@link #REJECT} (<b>default</b>) — 503 {@code REPLAY_STORE_UNAVAILABLE}. The request is
     *       not forwarded. This makes Redis a hard dependency of the partner edge, which is stated
     *       plainly rather than discovered in an incident.</li>
     *   <li>{@link #LOCAL} — fall back to the per-JVM {@link InMemoryNonceStore}. A captured
     *       request becomes replayable once per replica inside the clock-skew window (exactly the
     *       pre-Redis posture) instead of the edge going down. A real trade, offered explicitly.</li>
     * </ul>
     */
    public enum OnStoreError {
        /** 503 — no forwarding without a replay decision. */
        REJECT,
        /** Degrade to the per-JVM nonce set: one replay per replica, but still a check. */
        LOCAL
    }

    /** Nonce retention; matches the HMAC clock-skew window (5 min). */
    private long nonceTtlSeconds = 300L;

    /**
     * Maximum accepted {@code X-Nonce} length in characters; longer is 400 {@code INVALID_NONCE}.
     *
     * <p>A shared store turns an unbounded partner-supplied header into unbounded writes into
     * infrastructure shared with every other tenant of this Redis. The in-memory store had the same
     * exposure but the blast radius was one pod's heap. 256 is ~7x a UUID.
     */
    private int maxNonceLength = 256;

    /** Posture when the nonce store errors. */
    private OnStoreError onStoreError = OnStoreError.REJECT;

    public long getNonceTtlSeconds() {
        return nonceTtlSeconds;
    }

    public void setNonceTtlSeconds(long nonceTtlSeconds) {
        this.nonceTtlSeconds = nonceTtlSeconds;
    }

    public int getMaxNonceLength() {
        return maxNonceLength;
    }

    public void setMaxNonceLength(int maxNonceLength) {
        this.maxNonceLength = maxNonceLength;
    }

    public OnStoreError getOnStoreError() {
        return onStoreError;
    }

    public void setOnStoreError(OnStoreError onStoreError) {
        this.onStoreError = onStoreError == null ? OnStoreError.REJECT : onStoreError;
    }
}
