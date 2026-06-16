package com.gme.pay.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the trace helpers (no Spring context). */
class TraceUnitTest {

    @Test
    @DisplayName("TraceJson emits a flat object and escapes quotes / control chars")
    void jsonEscaping() {
        String json = new TraceJson()
                .str("caller", "ops-bff")
                .str("path", "a\"b\nc")
                .num("status", 200)
                .end();
        assertThat(json).startsWith("{").endsWith("}");
        assertThat(json).contains("\"caller\":\"ops-bff\"");
        assertThat(json).contains("\\\"b\\nc");   // escaped quote + newline
        assertThat(json).contains("\"status\":200");
    }

    @Test
    @DisplayName("null values are omitted from the JSON")
    void jsonOmitsNulls() {
        String json = new TraceJson().str("caller", "x").str("detail", null).end();
        assertThat(json).isEqualTo("{\"caller\":\"x\"}");
    }

    @Test
    @DisplayName("calleeFor maps known ports, keeps DNS hosts, falls back to host:port")
    void calleeNaming() {
        assertThat(TraceNames.calleeFor(URI.create("http://localhost:8084/v1/payments")))
                .isEqualTo("payment-executor");
        assertThat(TraceNames.calleeFor(URI.create("http://config-registry:8080/v1/partners")))
                .isEqualTo("config-registry");
        assertThat(TraceNames.calleeFor(URI.create("http://localhost:7777/x")))
                .isEqualTo("localhost:7777");
    }

    @Test
    @DisplayName("skip() filters health/docs/static + the tracer's own path")
    void skipPaths() {
        assertThat(TraceNames.skip("/actuator/health")).isTrue();
        assertThat(TraceNames.skip("/v3/api-docs")).isTrue();
        assertThat(TraceNames.skip("/ingest")).isTrue();
        assertThat(TraceNames.skip("/v1/payments")).isFalse();
    }

    @Test
    @DisplayName("reporter is fail-open: report/event never throw even when the console is down")
    void reporterFailOpen() {
        try (TraceReporter r = new TraceReporter("test-svc", "http://localhost:59999/ingest", 64)) {
            assertThat(r.self()).isEqualTo("test-svc");
            assertThatCode(() -> {
                r.report("a", "b", "GET", "/x", 200, 3, null, null);
                r.event("scheduler.tick", "swept=5");
            }).doesNotThrowAnyException();
        }
    }
}
