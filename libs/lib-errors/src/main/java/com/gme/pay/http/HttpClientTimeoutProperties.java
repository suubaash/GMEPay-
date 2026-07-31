package com.gme.pay.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The fleet-wide outbound HTTP timeout floor, and the reasoning behind each number.
 *
 * <h2>connect-timeout = 2s</h2>
 *
 * <p>Every hop in this platform is either in-cluster (single-digit-millisecond RTT) or a scheme over
 * the public internet. Two seconds is far beyond any healthy handshake in either case, so it never
 * fires on a working peer, and it replaces the kernel's SYN-retry budget (order of a minute) for a
 * black-holed address. There is no case where waiting longer to <em>connect</em> produces a better
 * outcome than failing over.
 *
 * <h2>read-timeout = 10s</h2>
 *
 * <p>This is deliberately the <b>loosest</b> value in the platform, because it is the default that
 * applies to hops nobody has thought about individually — reports, admin reads, config lookups,
 * batch enrichment. Its job is to convert "unbounded" into "bounded", not to be tight. A tight
 * global default would be the more dangerous change: it would start failing legitimately slow
 * non-money calls the day it shipped, and the pressure to raise it back would land on the money path
 * too.
 *
 * <p>The money path does not use this value. It sets its own, tighter, and the nesting is the point:
 *
 * <pre>
 *   scheme adapter -&gt; scheme          4s   (gmepay.scheme.outbound.read-timeout-millis)
 *   payment-executor -&gt; adapter       5s   (gmepay.scheme.read-timeout-millis)
 *   payment-executor -&gt; internal      5s   (gmepay.internal-http.read-timeout-millis)
 *   anything else                    10s   (this default)
 * </pre>
 *
 * <p>Inner budgets are strictly smaller than the budget of the caller waiting on them. That ordering
 * is what stops a hung scheme from manufacturing {@code UNCERTAIN}: the adapter gives up first and
 * reports a definite {@code SCHEME_UNAVAILABLE}, so payment-executor learns "the call failed
 * ambiguously" from a response rather than inferring it from its own socket timeout. Both are routed
 * to the same anti-double-charge path (ADR-016 §4) — the difference is that one carries a reason and
 * the other is a guess.
 *
 * <h2>What a timeout must never mean</h2>
 *
 * <p>Never an automatic retry of an irreversible submit. A read timeout on a scheme submit means the
 * scheme <em>may</em> have moved money; the platform's contract (ADR-016 §4) is to treat the outcome
 * as unknown, probe the scheme's idempotent status endpoint, and only then decide. Shortening a
 * timeout increases how often that path is taken, which is precisely why the values above are floors
 * on the ambiguity window rather than aggressive fail-fast numbers.
 */
@ConfigurationProperties(prefix = "gmepay.http.client")
public class HttpClientTimeoutProperties {

    /** Time allowed to establish a connection before the attempt is abandoned. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Time allowed between sending a request and the response arriving. */
    private Duration readTimeout = Duration.ofSeconds(10);

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
