package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.bff.client.stub.StubRbacAdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link RbacAdminController} using the real {@link StubRbacAdminClient},
 * so the Admin-UI RBAC contract ({@code /v1/admin/rbac/*}) is exercised end-to-end against the
 * seeded role/permission shape.
 */
class RbacAdminControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new RbacAdminController(new StubRbacAdminClient())).build();
    }

    @Test
    @DisplayName("GET /v1/admin/rbac/roles returns roles with codes, userCount and grants")
    void roles() throws Exception {
        mvc.perform(get("/v1/admin/rbac/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].role").value("HUB_ADMIN"))
                .andExpect(jsonPath("$[0].userCount").isNumber())
                .andExpect(jsonPath("$[0].permissions").isArray());
    }

    @Test
    @DisplayName("GET /v1/admin/rbac/permissions returns the catalogue")
    void permissions() throws Exception {
        mvc.perform(get("/v1/admin/rbac/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[?(@.permission=='rbac.manage')].resource").value("rbac"));
    }

    @Test
    @DisplayName("PUT role permissions echoes the requested grant set")
    void putRolePermissions() throws Exception {
        mvc.perform(put("/v1/admin/rbac/roles/HUB_OPERATOR/permissions")
                        .contentType("application/json")
                        .content("{\"grants\":[\"partner.view\",\"txn.view\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("HUB_OPERATOR"))
                .andExpect(jsonPath("$.permissions.length()").value(2));
    }

    @Test
    @DisplayName("POST create role returns the new role")
    void createRole() throws Exception {
        mvc.perform(post("/v1/admin/rbac/roles")
                        .contentType("application/json")
                        .content("{\"name\":\"AUDIT_READ\",\"basePermissions\":[\"txn.view\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AUDIT_READ"))
                .andExpect(jsonPath("$.permissions[0]").value("txn.view"));
    }
}
