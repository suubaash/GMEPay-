package com.gme.pay.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/** Unit tests for {@link CorrelationIdClientHttpInterceptor} — no Spring context. */
class CorrelationIdClientHttpInterceptorTest {

    private final CorrelationIdClientHttpInterceptor interceptor = new CorrelationIdClientHttpInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private MockClientHttpRequest request() {
        return new MockClientHttpRequest(HttpMethod.GET, java.net.URI.create("http://downstream:8080/v1/x"));
    }

    /** Records the header the interceptor stamped on the request, then returns 200. */
    private static ClientHttpRequestExecution captureExec(String[] seenHeader) {
        return (req, body) -> {
            seenHeader[0] = req.getHeaders().getFirst(CorrelationHeaders.CORRELATION_ID);
            return new MockClientHttpResponse(new byte[0], 200);
        };
    }

    @Test
    @DisplayName("copies the MDC correlation id onto the outbound request header")
    void copiesMdcIdOntoRequest() throws IOException {
        MDC.put(CorrelationHeaders.MDC_KEY, "chain-42");
        String[] seen = new String[1];
        MockClientHttpRequest req = request();

        try (ClientHttpResponse ignored = interceptor.intercept(req, new byte[0], captureExec(seen))) {
            assertThat(seen[0]).isEqualTo("chain-42");
        }
    }

    @Test
    @DisplayName("no-op when the MDC id is absent — no header is added")
    void noopWhenMdcEmpty() throws IOException {
        String[] seen = new String[1];
        MockClientHttpRequest req = request();

        try (ClientHttpResponse ignored = interceptor.intercept(req, new byte[0], captureExec(seen))) {
            assertThat(seen[0]).isNull();
            assertThat(req.getHeaders().containsKey(CorrelationHeaders.CORRELATION_ID)).isFalse();
        }
    }

    @Test
    @DisplayName("does not overwrite a correlation header already present on the request")
    void doesNotOverwriteExisting() throws IOException {
        MDC.put(CorrelationHeaders.MDC_KEY, "from-mdc");
        String[] seen = new String[1];
        MockClientHttpRequest req = request();
        req.getHeaders().add(CorrelationHeaders.CORRELATION_ID, "already-set");

        try (ClientHttpResponse ignored = interceptor.intercept(req, new byte[0], captureExec(seen))) {
            assertThat(seen[0]).isEqualTo("already-set");
        }
    }
}
