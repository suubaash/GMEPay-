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
 * <p>Bound from {@code gateway.rate-limit.*}. Disabled by default ({@code enabled=false})
 * so the filter is a no-op until explicitly switched on per environment — keeping the
 * existing test suite and local dev unthrottled unless a test opts in.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class RateLimitProperties {

    /** Master switch; when false the filter passes every request through untouched. */
    private boolean enabled = false;

    /** Fail-open when the backing store errors (mirrors replay-protection.fail-open). */
    private boolean failOpen = true;

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
