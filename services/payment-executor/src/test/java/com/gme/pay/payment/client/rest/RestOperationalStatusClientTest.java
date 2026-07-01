package com.gme.pay.payment.client.rest;

import com.gme.pay.contracts.OperationalStatusView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
    @DisplayName("fail-OPEN (default): unreachable config-registry with no cache → all-clear (allow)")
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
    @DisplayName("fail-CLOSED: unreachable config-registry with no cache → synthetic systemPaused")
    void currentStatus_failClosed() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(URL)).andRespond(withServerError());
        RestOperationalStatusClient client = new RestOperationalStatusClient(b.build(), 60_000, false);

        OperationalStatusView v = client.currentStatus();

        assertTrue(v.systemPaused(), "fail-closed must pause when status cannot be confirmed");
        server.verify();
    }
}
