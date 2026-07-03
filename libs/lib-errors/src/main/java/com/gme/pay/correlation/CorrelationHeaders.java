package com.gme.pay.correlation;

/**
 * Header + MDC key names for end-to-end correlation-ID propagation. A single id follows one
 * logical request across every GMEPay+ service so its logs and responses can be joined by that id.
 *
 * <p>Distinct from the trace tap's {@code X-Gme-Trace-Caller} (which carries the <em>caller's
 * identity</em> for the trace-console graph, not a propagated request id): the tracer had no
 * request/correlation id, so this adds one rather than overloading that header.
 */
public final class CorrelationHeaders {

    private CorrelationHeaders() {}

    /** Canonical inbound/outbound header carrying the correlation id. */
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /** Also accepted on inbound (common gateway/ingress alias) so an upstream-supplied id is reused. */
    public static final String REQUEST_ID = "X-Request-Id";

    /** SLF4J MDC key the id is stored under; reference it in a log pattern as {@code %X{correlationId}}. */
    public static final String MDC_KEY = "correlationId";
}
