package com.gme.pay.http;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Builds the one {@link ClientHttpRequestFactory} shape the whole platform uses for outbound HTTP:
 * the JDK {@code HttpClient} with <b>both</b> a connect and a read timeout set.
 *
 * <h2>Why both, and why a helper rather than a line of config</h2>
 *
 * <p>A connect timeout alone bounds nothing that matters here. The failure that
 * {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #7 measured is a peer that <em>accepts</em> the
 * connection and then never answers — the TCP handshake completes in a millisecond and the caller
 * then blocks on {@code read()} until the OS gives up, holding a Tomcat worker for minutes. So the
 * read timeout is the load-bearing one; the connect timeout is only there so a black-holed address
 * fails in seconds instead of at the kernel's SYN-retry budget.
 *
 * <p>The two timeouts have to be set on two different objects — connect on the
 * {@code java.net.http.HttpClient}, read on the Spring factory that wraps it — which is exactly the
 * kind of detail that gets half-done when it is copy-pasted into twenty services. Hence one helper.
 *
 * <h2>Why the JDK client specifically</h2>
 *
 * <p>Not a preference: {@code SimpleClientHttpRequestFactory} (Boot's default, built on
 * {@code HttpURLConnection}) rejects {@code PATCH} with {@code ProtocolException: Invalid HTTP
 * method: PATCH}, which is a verb the money path really uses
 * ({@code PATCH /v1/transactions/{ref}/status}). Anything that installs timeouts must therefore also
 * keep the JDK client, or it silently breaks the status commit. Naming it explicitly — rather than
 * letting {@code ClientHttpRequestFactories} pick by classpath scan — means adding, say, Apache
 * HttpClient5 to one service's classpath cannot quietly change the transport underneath the money
 * path.
 */
public final class HttpClientTimeouts {

    private HttpClientTimeouts() {}

    /**
     * A PATCH-capable request factory with the given connect and read timeouts.
     *
     * @param connectTimeout time allowed to establish the TCP/TLS connection
     * @param readTimeout    time allowed between the request being sent and the response arriving —
     *                       the bound that turns "hangs forever" into a definite, catchable
     *                       {@code ResourceAccessException}
     */
    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /** Convenience overload for millisecond-valued properties. */
    public static ClientHttpRequestFactory requestFactory(long connectTimeoutMillis, long readTimeoutMillis) {
        return requestFactory(Duration.ofMillis(connectTimeoutMillis), Duration.ofMillis(readTimeoutMillis));
    }
}
