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
 * <p><b>Known limitation, deliberately not papered over:</b> the only {@link RateLimitStore}
 * implementation in the repo is {@link InMemoryRateLimitStore}, so the window is <em>per gateway
 * instance</em>. With N replicas the effective cap is N x the configured value. A shared Redis store
 * is still missing (the {@code gateway.rate-limit.store} key names one but nothing reads it) — see
 * the T0-7 note in {@code Documentation/GAP_REGISTER.md}.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class RateLimitProperties {

    /** Master switch; when false the filter passes every request through untouched. */
    private boolean enabled = true;

    /** Deny when the backing store errors. T0-7: was {@code true} (admit on error). */
    private boolean failOpen = false;

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
