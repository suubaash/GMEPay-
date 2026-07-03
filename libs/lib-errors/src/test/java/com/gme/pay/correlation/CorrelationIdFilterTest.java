package com.gme.pay.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for {@link CorrelationIdFilter} using Spring's servlet mocks. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("generates a UUID when no correlation header is present, and echoes it back")
    void generatesWhenAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/payments");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        String echoed = resp.getHeader(CorrelationHeaders.CORRELATION_ID);
        assertThat(echoed).isNotBlank();
        // Looks like a UUID (8-4-4-4-12).
        assertThat(echoed).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        assertThat(chain.getRequest()).isEqualTo(req);   // chain ran
    }

    @Test
    @DisplayName("reuses the incoming X-Correlation-Id header")
    void reusesCorrelationHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/payments");
        req.addHeader(CorrelationHeaders.CORRELATION_ID, "abc-123");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, new MockFilterChain());

        assertThat(resp.getHeader(CorrelationHeaders.CORRELATION_ID)).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("accepts the X-Request-Id alias when X-Correlation-Id is absent")
    void reusesRequestIdAlias() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/payments");
        req.addHeader(CorrelationHeaders.REQUEST_ID, "req-999");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, new MockFilterChain());

        assertThat(resp.getHeader(CorrelationHeaders.CORRELATION_ID)).isEqualTo("req-999");
    }

    @Test
    @DisplayName("a blank incoming header is treated as absent (a fresh id is generated)")
    void blankHeaderGeneratesFresh() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/payments");
        req.addHeader(CorrelationHeaders.CORRELATION_ID, "   ");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, new MockFilterChain());

        assertThat(resp.getHeader(CorrelationHeaders.CORRELATION_ID)).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    @DisplayName("the id is present in MDC DURING the chain and CLEARED after (no thread leakage)")
    void mdcSetDuringChainAndClearedAfter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/payments");
        req.addHeader(CorrelationHeaders.CORRELATION_ID, "trace-me");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        String[] seen = new String[1];

        FilterChain chain = (rq, rs) -> seen[0] = MDC.get(CorrelationHeaders.MDC_KEY);

        filter.doFilter(req, resp, chain);

        assertThat(seen[0]).isEqualTo("trace-me");                       // present during chain
        assertThat(MDC.get(CorrelationHeaders.MDC_KEY)).isNull();        // cleared afterwards
    }

    @Test
    @DisplayName("MDC is cleared even when the chain throws")
    void mdcClearedOnException() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/payments");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain boom = (rq, rs) -> { throw new java.io.IOException("boom"); };

        try {
            filter.doFilter(req, resp, boom);
        } catch (Exception expected) {
            // ignored
        }
        assertThat(MDC.get(CorrelationHeaders.MDC_KEY)).isNull();
    }
}
