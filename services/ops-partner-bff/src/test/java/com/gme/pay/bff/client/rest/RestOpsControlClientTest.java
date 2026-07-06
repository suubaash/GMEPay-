package com.gme.pay.bff.client.rest;

import com.gme.pay.contracts.OperationalStatusView;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link RestOpsControlClient} maps config-registry's ops endpoints; the status read
 * degrades to all-clear on upstream error and pause forwards actor/reason.
 */
class RestOpsControlClientTest {

    private MockRestServiceServer server;

    private RestOpsControlClient newClient() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new RestOpsControlClient(builder.build());
    }

    @Test
    void operationalStatus_mapsView() {
        RestOpsControlClient client = newClient();
        String body = """
                {"systemPaused":true,"maintenanceMode":false,
                 "suspendedPartners":["P1"],"suspendedSchemes":[],"suspendedRoutes":[],
                 "reason":"scheme outage","since":"2026-07-01T00:00:00Z"}
                """;
        server.expect(requestTo("/v1/ops/operational-status"))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        OperationalStatusView view = client.operationalStatus();
        server.verify();
        assertThat(view.systemPaused()).isTrue();
        assertThat(view.suspendedPartners()).containsExactly("P1");
        assertThat(view.reason()).isEqualTo("scheme outage");
    }

    @Test
    void operationalStatus_degradesToAllClearOnError() {
        RestOpsControlClient client = newClient();
        server.expect(requestTo("/v1/ops/operational-status"))
                .andExpect(method(GET))
                .andRespond(withServerError());

        OperationalStatusView view = client.operationalStatus();
        server.verify();
        assertThat(view.systemPaused()).isFalse();
        assertThat(view.maintenanceMode()).isFalse();
    }

    @Test
    void pause_sendsActorAsHeader_andReasonInBody() {
        RestOpsControlClient client = newClient();
        String body = """
                {"systemPaused":true,"maintenanceMode":false,
                 "suspendedPartners":[],"suspendedSchemes":[],"suspendedRoutes":[],
                 "reason":"manual","since":"2026-07-01T00:00:00Z"}
                """;
        // OpsControlController reads the operator from the X-Actor HEADER; body carries reason only.
        server.expect(requestTo("/v1/ops/pause"))
                .andExpect(method(POST))
                .andExpect(header("X-Actor", "ops.admin@gmepay.com"))
                .andExpect(jsonPath("$.reason").value("manual"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        OperationalStatusView view = client.pause("ops.admin@gmepay.com", "manual");
        server.verify();
        assertThat(view.systemPaused()).isTrue();
    }

    @Test
    void maintenance_readsCurrentState_andSendsExplicitOnFlag() {
        RestOpsControlClient client = newClient();
        String statusBody = """
                {"systemPaused":false,"maintenanceMode":false,
                 "suspendedPartners":[],"suspendedSchemes":[],"suspendedRoutes":[],
                 "reason":null,"since":null}
                """;
        String afterBody = """
                {"systemPaused":false,"maintenanceMode":true,
                 "suspendedPartners":[],"suspendedSchemes":[],"suspendedRoutes":[],
                 "reason":"patching","since":"2026-07-04T00:00:00Z"}
                """;
        // Toggle semantics: current maintenanceMode=false → must POST {"on":true}.
        server.expect(requestTo("/v1/ops/operational-status"))
                .andExpect(method(GET))
                .andRespond(withSuccess(statusBody, MediaType.APPLICATION_JSON));
        server.expect(requestTo("/v1/ops/maintenance"))
                .andExpect(method(POST))
                .andExpect(header("X-Actor", "ops.admin@gmepay.com"))
                .andExpect(jsonPath("$.on").value(true))
                .andExpect(jsonPath("$.reason").value("patching"))
                .andRespond(withSuccess(afterBody, MediaType.APPLICATION_JSON));

        OperationalStatusView view = client.maintenance("ops.admin@gmepay.com", "patching");
        server.verify();
        assertThat(view.maintenanceMode()).isTrue();
    }

    @Test
    void suspend_mapsScopeRefToEntityTypeEntityId() {
        RestOpsControlClient client = newClient();
        String body = """
                {"systemPaused":false,"maintenanceMode":false,
                 "suspendedPartners":["GMEREMIT"],"suspendedSchemes":[],"suspendedRoutes":[],
                 "reason":"fraud check","since":"2026-07-04T00:00:00Z"}
                """;
        server.expect(requestTo("/v1/ops/suspend"))
                .andExpect(method(POST))
                .andExpect(header("X-Actor", "ops.admin@gmepay.com"))
                .andExpect(jsonPath("$.entityType").value("partner"))
                .andExpect(jsonPath("$.entityId").value("GMEREMIT"))
                .andExpect(jsonPath("$.reason").value("fraud check"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        OperationalStatusView view = client.suspend("partner", "GMEREMIT", "ops.admin@gmepay.com", "fraud check");
        server.verify();
        assertThat(view.suspendedPartners()).containsExactly("GMEREMIT");
    }
}
