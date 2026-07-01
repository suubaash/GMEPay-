package com.gme.pay.payment.client.rest;

import com.gme.pay.contracts.OperationalStatusView;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link MockRestServiceServer} unit tests for {@link RestOperationalStatusClient}. */
class RestOperationalStatusClientTest {

    private static final String URL = "http://config-registry:8080/v1/ops/operational-status";

    private RestClient.Builder builder() {
        return RestClientSupport.withJavaTime(
                RestClient.builder().baseUrl("http://config-registry:8080"));
    }

    @Test
    @DisplayName("parses the operational-status read model (systemPaused + suspension lists)")
    void currentStatus_parsesReadModel() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(URL))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"systemPaused\":true,\"maintenanceMode\":false,"
                                + "\"suspendedPartners\":[\"GMEREMIT\"],"
                                + "\"suspendedSchemes\":[\"zeropay\"],\"suspendedRoutes\":[],"
                                + "\"reason\":\"incident 42\",\"since\":\"2026-07-01T00:00:00Z\"}",
                        MediaType.APPLICATION_JSON));
        // large TTL so a single fetch is used
        RestOperationalStatusClient client = new RestOperationalStatusClient(b.build(), 60_000, true);

        OperationalStatusView v = client.currentStatus();

        assertTrue(v.systemPaused());
        assertEquals("GMEREMIT", v.suspendedPartners().get(0));
        assertEquals("incident 42", v.reason());
        server.verify();
    }

    @Test
    @DisplayName("short cache: a second call within TTL does NOT re-hit config-registry")
    void currentStatus_cachesWithinTtl() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        // Exactly ONE server interaction is expected; a second call must be served from cache.
        server.expect(requestTo(URL))
                .andRespond(withSuccess(
                        "{\"systemPaused\":false,\"maintenanceMode\":false,"
                                + "\"suspendedPartners\":[],\"suspendedSchemes\":[],"
                                + "\"suspendedRoutes\":[],\"reason\":null,\"since\":null}",
                        MediaType.APPLICATION_JSON));
        RestOperationalStatusClient client = new RestOperationalStatusClient(b.build(), 60_000, true);

        client.currentStatus();
        client.currentStatus(); // served from cache — no second server expectation registered
        server.verify();
    }

    @Test
    @DisplayName("fail-OPEN (opt-in): unreachable config-registry with no cache → all-clear (allow)")
    void currentStatus_failOpen() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(URL)).andRespond(withServerError());
        RestOperationalStatusClient client = new RestOperationalStatusClient(b.build(), 60_000, true);

        OperationalStatusView v = client.currentStatus();

        assertFalse(v.systemPaused(), "fail-open must not pause");
        assertFalse(v.maintenanceMode());
        server.verify();
    }

    @Test
    @DisplayName("fail-CLOSED (default): unreachable + no cache → synthetic systemPaused (kill-switch safe)")
    void currentStatus_failClosed() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(URL)).andRespond(withServerError());
        RestOperationalStatusClient client = new RestOperationalStatusClient(b.build(), 60_000, false);

        OperationalStatusView v = client.currentStatus();

        assertTrue(v.systemPaused(), "fail-closed must pause when status cannot be confirmed");
        server.verify();
    }

    @Test
    @DisplayName("last-known-good cache is preferred over the fail-closed default on a later outage")
    void currentStatus_prefersLastKnownGoodOnOutage() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        // First call: all-clear success. Second call (after TTL) errors → must serve last-known-good.
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"systemPaused\":false,\"maintenanceMode\":false,\"suspendedPartners\":[],"
                        + "\"suspendedSchemes\":[],\"suspendedRoutes\":[],\"reason\":null,\"since\":null}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withServerError());
        // TTL 0 → the second call always re-fetches (and then falls back to last-known-good).
        RestOperationalStatusClient client = new RestOperationalStatusClient(b.build(), 0, false);

        assertFalse(client.currentStatus().systemPaused());          // seeds the cache
        OperationalStatusView second = client.currentStatus();       // outage → last-known-good
        assertFalse(second.systemPaused(), "a brief blip must not flip policy to fail-closed");
        server.verify();
    }

    @Test
    @DisplayName("hard client timeout: a hung config-registry is treated as unreachable within budget")
    void currentStatus_timesOutAndFailsClosed() throws Exception {
        // A local HTTP server that NEVER responds within the read timeout, simulating a hung registry.
        HttpServer hung = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hung.createContext("/v1/ops/operational-status", exchange -> {
            try {
                Thread.sleep(10_000); // far beyond the 300ms read timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        hung.start();
        try {
            String baseUrl = "http://127.0.0.1:" + hung.getAddress().getPort();
            // Production constructor path so the configured connect/read timeouts are applied.
            RestOperationalStatusClient client = new RestOperationalStatusClient(
                    RestClient.builder(), baseUrl, 60_000, /*failOpen*/ false,
                    /*connectMs*/ 300, /*readMs*/ 300);

            long start = System.nanoTime();
            OperationalStatusView v = client.currentStatus();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(v.systemPaused(),
                    "a timed-out (hung) registry with no cache must fail-closed for security");
            assertTrue(elapsed.toMillis() < 5_000,
                    "the read timeout must fire well within budget (was " + elapsed.toMillis() + "ms)");
        } finally {
            hung.stop(0);
        }
    }
}
