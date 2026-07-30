package com.gme.pay.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-partner rate-limit configuration (API-05 §3.5).
 *
 * <p>API-05 caps per partner: 100 req/s global, 20 req/s for {@code POST /v1/rates},
 * 50 req/s for {@code POST /v1/payments} and {@code POST /v1/payments/cpm/generate}.
 * The {@link RateLimitFilter} applies the most specific scope matching the request path
 * and falls back to the global cap.
 *
 * <p>Bound from {@code gateway.rate-limit.*}.
 *
 * <p><b>T0-7:</b> this used to ship {@code enabled=false} + {@code failOpen=true}, so the documented
 * per-partner cap was not applied anywhere and, had it been switched on, a store error would have
 * admitted unlimited traffic. Both defaults are now the safe ones: the cap is <b>on</b>, and a store
 * error <b>denies</b>. The caps themselves are per-partner and per-second, so an honest partner never
 * notices; the control exists to bound credential-stuffing and enumeration at the edge, which is
 * precisely the case where "fail open on error" hands the attacker the outcome they want.
 *
 * <p><b>The per-instance limitation T0-7 recorded is now closed:</b> {@link RedisRateLimitStore}
 * shares the window across replicas, selected by {@code gateway.shared-state.store} (see
 * {@link com.gme.pay.gateway.sharedstate.GatewaySharedStateConfig}). What that buys is a real
 * dependency, and {@link #onStoreError} is where its failure is decided rather than defaulted into.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class RateLimitProperties {

    /**
     * What to do when the rate-limit store cannot answer (Redis unreachable, timeout, error).
     *
     * <p>Making Redis the counter also makes "Redis is down" a question the edge has to answer.
     * The three answers are named rather than implied, because the two obvious ones are both bad
     * in a way the other hides:
     *
     * <ul>
     *   <li>{@link #DENY} (<b>default</b>) — 429 every throttled request. Consistent with T0-7's
     *       {@code fail-open: false}: an outage in the control that bounds enumeration must not
     *       hand an attacker unlimited attempts. The cost is honest and large — a Redis outage
     *       stops partner traffic. Chosen as the default because it is the posture T0-7 set
     *       deliberately, and a dependency swap is not a mandate to quietly relax it.</li>
     *   <li>{@link #LOCAL} — fall back to the per-JVM {@link InMemoryRateLimitStore}. The cap
     *       degrades to N x the configured value (exactly the pre-Redis posture) instead of
     *       disappearing. <b>This is the recommended setting for a multi-replica deployment that
     *       treats a Redis outage as worse than an N x cap</b>, and it is the option that did not
     *       exist while a boolean was the only knob: "not fail-open" used to mean "deny", with no
     *       way to say "degrade".</li>
     *   <li>{@link #ALLOW} — admit everything. The rate limit ceases to exist for the duration of
     *       the outage. Offered only because {@code fail-open: true} already meant this and
     *       removing it silently would be its own posture change.</li>
     * </ul>
     */
    public enum OnStoreError {
        /** 429 — the T0-7 default; an unavailable control is a closed control. */
        DENY,
        /** Degrade to the per-JVM window: N x the cap, but still a cap. */
        LOCAL,
        /** Admit everything — no rate limiting at all while the store is down. */
        ALLOW
    }

    /** Master switch; when false the filter passes every request through untouched. */
    private boolean enabled = true;

    /**
     * Legacy T0-7 knob, retained so its default and its tests keep their meaning. {@code true} is
     * equivalent to {@code on-store-error: ALLOW} and wins over it; {@code false} (the shipped
     * default) defers to {@link #onStoreError}.
     */
    private boolean failOpen = false;

    /** Posture when the store errors. Default DENY = T0-7's fail-closed behaviour, unchanged. */
    private OnStoreError onStoreError = OnStoreError.DENY;

    /** Global per-partner cap (requests per second). */
    private long globalPerSecond = 100;

    /** Cap for {@code POST /v1/rates} (requests per second). */
    private long ratesPerSecond = 20;

    /** Cap for {@code POST /v1/payments} and {@code /v1/payments/cpm/generate} (req/s). */
    private long paymentsPerSecond = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public OnStoreError getOnStoreError() {
        return onStoreError;
    }

    public void setOnStoreError(OnStoreError onStoreError) {
        this.onStoreError = onStoreError == null ? OnStoreError.DENY : onStoreError;
    }

    /**
     * The posture actually applied: the legacy {@code fail-open: true} still means ALLOW, so a
     * deployment that set it keeps behaving the way it was configured to.
     */
    public OnStoreError effectiveOnStoreError() {
        return failOpen ? OnStoreError.ALLOW : onStoreError;
    }

    public long getGlobalPerSecond() {
        return globalPerSecond;
    }

    public void setGlobalPerSecond(long globalPerSecond) {
        this.globalPerSecond = globalPerSecond;
    }

    public long getRatesPerSecond() {
        return ratesPerSecond;
    }

    public void setRatesPerSecond(long ratesPerSecond) {
        this.ratesPerSecond = ratesPerSecond;
    }

    public long getPaymentsPerSecond() {
        return paymentsPerSecond;
    }

    public void setPaymentsPerSecond(long paymentsPerSecond) {
        this.paymentsPerSecond = paymentsPerSecond;
    }
}
