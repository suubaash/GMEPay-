package com.gme.pay.trace;

import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Records every outbound HTTP call made through an autoconfigured {@code RestClient} /
 * {@code RestTemplate}. Stamps an {@code X-Gme-Trace-Caller} header so the callee's inbound
 * filter can attribute the call to this service.
 */
public class TraceClientHttpInterceptor implements ClientHttpRequestInterceptor {

    static final String CALLER_HEADER = "X-Gme-Trace-Caller";

    private final TraceReporter reporter;

    public TraceClientHttpInterceptor(TraceReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        URI uri = request.getURI();
        // Don't trace our own POSTs to the console (defensive — those use JDK HttpClient anyway).
        if (TraceNames.skip(uri.getPath())) {
            return execution.execute(request, body);
        }
        try {
            request.getHeaders().add(CALLER_HEADER, reporter.self());
        } catch (RuntimeException ignored) {
            // some request impls have read-only headers at this point; tracing is best-effort
        }
        long t0 = System.nanoTime();
        int status = 0;
        try {
            ClientHttpResponse resp = execution.execute(request, body);
            status = resp.getStatusCode().value();
            return resp;
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            reporter.report(reporter.self(), TraceNames.calleeFor(uri),
                    request.getMethod().name(), uri.getPath(), status, ms, null, null);
        }
    }
}
