package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.SystemHealthClient.ServiceHealth;
import com.gme.pay.bff.client.SystemHealthClient.SystemHealth;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies {@link RestSystemHealthClient} fans out to every service's
 * {@code /actuator/health}, maps actuator status -> BFF status, resolves the
 * per-service base URL from {@code gmepay.<service>.base-url}, and degrades an
 * unreachable / erroring service to {@code DOWN} rather than failing the snapshot.
 */
class RestSystemHealthClientTest {

    /** Points every service at a distinct loopback URL so the mock can match per host. */
    private MockEnvironment envWithPerServiceUrls() {
        MockEnvironment env = new MockEnvironment();
        for (String svc : RestSystemHealthClient.SERVICES) {
            env.setProperty("gmepay." + svc + ".base-url", "http://" + svc + ".test");
        }
        return env;
    }

    @Test
    void check_mapsActuatorStatusPerService() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        MockEnvironment env = envWithPerServiceUrls();

        // Most services UP; flag two specials to exercise the status map.
        for (String svc : RestSystemHealthClient.SERVICES) {
            String status = switch (svc) {
                case "rate-fx" -> "DOWN";
                case "qr-service" -> "OUT_OF_SERVICE";
                default -> "UP";
            };
            server.expect(times(1), requestTo("http://" + svc + ".test/actuator/health"))
                    .andRespond(withSuccess("{\"status\":\"" + status + "\"}", MediaType.APPLICATION_JSON));
        }

        RestSystemHealthClient client = new RestSystemHealthClient(builder.build(), env);
        SystemHealth snapshot = client.check();
        server.verify();

        assertThat(snapshot.checkedAt()).isNotNull();
        assertThat(snapshot.services()).hasSize(RestSystemHealthClient.SERVICES.size());

        Map<String, String> byName = snapshot.services().stream()
                .collect(Collectors.toMap(ServiceHealth::name, ServiceHealth::status));
        assertThat(byName.get("rate-fx")).isEqualTo("DOWN");
        assertThat(byName.get("qr-service")).isEqualTo("DEGRADED");
        assertThat(byName.get("config-registry")).isEqualTo("UP");
    }

    @Test
    void check_unreachableOrErroringService_mapsToDown() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        MockEnvironment env = envWithPerServiceUrls();

        for (String svc : RestSystemHealthClient.SERVICES) {
            if (svc.equals("auth-identity")) {
                server.expect(times(1), requestTo("http://auth-identity.test/actuator/health"))
                        .andRespond(withServerError());
            } else {
                server.expect(times(1), requestTo("http://" + svc + ".test/actuator/health"))
                        .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));
            }
        }

        RestSystemHealthClient client = new RestSystemHealthClient(builder.build(), env);
        SystemHealth snapshot = client.check();
        server.verify();

        Map<String, String> byName = snapshot.services().stream()
                .collect(Collectors.toMap(ServiceHealth::name, ServiceHealth::status));
        assertThat(byName.get("auth-identity")).isEqualTo("DOWN");
    }

    @Test
    void mapStatus_coversAllBranches() {
        assertThat(RestSystemHealthClient.mapStatus("UP")).isEqualTo("UP");
        assertThat(RestSystemHealthClient.mapStatus("down")).isEqualTo("DOWN");
        assertThat(RestSystemHealthClient.mapStatus("OUT_OF_SERVICE")).isEqualTo("DEGRADED");
        assertThat(RestSystemHealthClient.mapStatus("WEIRD")).isEqualTo("UNKNOWN");
    }
}
