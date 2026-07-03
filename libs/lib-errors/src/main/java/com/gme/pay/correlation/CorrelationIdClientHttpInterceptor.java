package com.gme.pay.correlation;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Propagates the current correlation id onto outbound HTTP calls. When the {@code correlationId} is
 * present in {@link MDC} (set by {@link CorrelationIdFilter} on the inbound request), it stamps the
 * {@link CorrelationHeaders#CORRELATION_ID} header on the outgoing request so the downstream
 * service's own {@code CorrelationIdFilter} reuses the SAME id — a single id then spans the whole
 * call chain.
 *
 * <p>No-op when the MDC id is absent (e.g. a scheduler/async thread with no request context) or when
 * the request already carries the header. Best-effort: never fails the outbound call.
 *
 * <p>Apply it to a service-built {@code RestClient} with
 * {@code .requestInterceptor(correlationIdClientHttpInterceptor)}, or rely on the auto-configured
 * {@code RestClientCustomizer}/{@code RestTemplateCustomizer} which register it on the shared builders.
 */
public class CorrelationIdClientHttpInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String id = MDC.get(CorrelationHeaders.MDC_KEY);
        if (id != null && !id.isBlank()
                && !request.getHeaders().containsKey(CorrelationHeaders.CORRELATION_ID)) {
            try {
                request.getHeaders().add(CorrelationHeaders.CORRELATION_ID, id);
            } catch (RuntimeException ignored) {
                // some request impls expose read-only headers at this point; propagation is best-effort
            }
        }
        return execution.execute(request, body);
    }
}
