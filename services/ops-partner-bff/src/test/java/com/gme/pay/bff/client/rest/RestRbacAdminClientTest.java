package com.gme.pay.bff.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.bff.web.dto.PermissionDef;
import com.gme.pay.bff.web.dto.RoleSummary;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies {@link RestRbacAdminClient} maps auth-identity {@code /v1/rbac/*} onto the Admin-UI
 * RBAC shapes, and that {@code putRolePermissions} diffs the requested grant set against the role's
 * current grants — issuing exactly one POST (add) and one DELETE (remove) for the changed codes.
 */
class RestRbacAdminClientTest {

    private static final String ROLES_JSON = """
            [{"id":1,"code":"HUB_ADMIN","description":"Admin","userCount":2,
              "permissions":["rbac.manage","txn.view"]},
             {"id":2,"code":"HUB_OPERATOR","description":"Operator","userCount":5,
              "permissions":["partner.view","report.generate","txn.view"]}]
            """;

    private static final String PERMS_JSON = """
            [{"id":3,"code":"partner.view","resource":"partner","action":"view","description":"View partner"},
             {"id":4,"code":"report.generate","resource":"report","action":"generate","description":"Gen report"},
             {"id":5,"code":"txn.view","resource":"txn","action":"view","description":"View txn"},
             {"id":6,"code":"inspector.view","resource":"inspector","action":"view","description":"Inspector"}]
            """;

    @Test
    void listRoles_mapsRoleViews() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(endsWith("/v1/rbac/roles"))).andExpect(method(GET))
                .andRespond(withSuccess(ROLES_JSON, MediaType.APPLICATION_JSON));

        List<RoleSummary> roles = new RestRbacAdminClient(b.build()).listRoles();
        server.verify();

        assertThat(roles).hasSize(2);
        assertThat(roles.get(0).role()).isEqualTo("HUB_ADMIN");
        assertThat(roles.get(0).userCount()).isEqualTo(2L);
        assertThat(roles.get(0).permissions()).contains("rbac.manage", "txn.view");
    }

    @Test
    void listPermissions_mapsPermissionViews() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(endsWith("/v1/rbac/permissions"))).andExpect(method(GET))
                .andRespond(withSuccess(PERMS_JSON, MediaType.APPLICATION_JSON));

        List<PermissionDef> perms = new RestRbacAdminClient(b.build()).listPermissions();
        server.verify();

        assertThat(perms).hasSize(4);
        assertThat(perms.get(0).permission()).isEqualTo("partner.view");
        assertThat(perms.get(0).resource()).isEqualTo("partner");
    }

    @Test
    void putRolePermissions_diffsAddsAndRemoves() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).ignoreExpectOrder(true).build();
        // current HUB_OPERATOR grants = [partner.view, report.generate, txn.view]
        server.expect(ExpectedCount.manyTimes(), requestTo(endsWith("/v1/rbac/roles")))
                .andExpect(method(GET)).andRespond(withSuccess(ROLES_JSON, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(endsWith("/v1/rbac/permissions")))
                .andExpect(method(GET)).andRespond(withSuccess(PERMS_JSON, MediaType.APPLICATION_JSON));
        // add inspector.view → POST .../roles/2/permissions
        server.expect(ExpectedCount.once(), requestTo(endsWith("/v1/rbac/roles/2/permissions")))
                .andExpect(method(POST)).andRespond(withSuccess());
        // remove report.generate (id 4) → DELETE .../roles/2/permissions/4
        server.expect(ExpectedCount.once(), requestTo(containsString("/v1/rbac/roles/2/permissions/4")))
                .andExpect(method(DELETE)).andRespond(withSuccess());

        // target = current - report.generate + inspector.view
        RoleSummary updated = new RestRbacAdminClient(b.build())
                .putRolePermissions("HUB_OPERATOR", List.of("partner.view", "txn.view", "inspector.view"));
        server.verify();

        assertThat(updated.role()).isEqualTo("HUB_OPERATOR");
    }

    @Test
    void createRole_postsAndMaps() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(endsWith("/v1/rbac/roles"))).andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"id":9,"code":"AUDIT_READ","description":null,"userCount":0,
                         "permissions":["txn.view"]}
                        """, MediaType.APPLICATION_JSON));

        RoleSummary created = new RestRbacAdminClient(b.build())
                .createRole("AUDIT_READ", List.of("txn.view"));
        server.verify();

        assertThat(created.role()).isEqualTo("AUDIT_READ");
        assertThat(created.permissions()).containsExactly("txn.view");
    }
}
