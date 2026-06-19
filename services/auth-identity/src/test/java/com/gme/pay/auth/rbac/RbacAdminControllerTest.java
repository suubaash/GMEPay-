package com.gme.pay.auth.rbac;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.auth.rbac.RbacAdminDtos.ConstraintView;
import com.gme.pay.auth.rbac.RbacAdminDtos.PermissionView;
import com.gme.pay.auth.rbac.RbacAdminDtos.UserRoleView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link RbacAdminController}'s HTTP surface over a mocked
 * {@link RbacAdminService} — routing, status codes, and (de)serialization. The {@code rbac.manage}
 * gate is enforced by the edge/interceptor, not exercised here (standalone has no interceptor).
 */
class RbacAdminControllerTest {

    private RbacAdminService admin;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        admin = mock(RbacAdminService.class);
        mvc = standaloneSetup(new RbacAdminController(admin)).build();
    }

    @Test
    void listPermissions_returnsCatalogue() throws Exception {
        when(admin.listPermissions()).thenReturn(List.of(
                new PermissionView(1L, "rbac.manage", "rbac", "manage", "Manage RBAC", null)));
        mvc.perform(get("/v1/rbac/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("rbac.manage"));
    }

    @Test
    void assignRole_returns201_andDelegates() throws Exception {
        when(admin.assignRole(eq(5L), any())).thenReturn(new UserRoleView(
                10L, 5L, 2L, "HUB_ADMIN", null, Instant.now(), null, "tester", Instant.now(), null, true));
        mvc.perform(post("/v1/rbac/principals/5/roles")
                        .contentType("application/json")
                        .content("{\"roleCode\":\"HUB_ADMIN\",\"grantedBy\":\"tester\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleCode").value("HUB_ADMIN"))
                .andExpect(jsonPath("$.active").value(true));
        verify(admin).assignRole(eq(5L), any());
    }

    @Test
    void createConstraint_returns201() throws Exception {
        when(admin.createConstraint(any())).thenReturn(new ConstraintView(
                7L, "ROLE", 2L, "TIME", "{\"startHour\":\"9\"}", null, true, Instant.now()));
        mvc.perform(post("/v1/rbac/constraints")
                        .contentType("application/json")
                        .content("{\"scopeType\":\"ROLE\",\"scopeId\":2,\"constraintType\":\"TIME\","
                                + "\"configJson\":\"{\\\"startHour\\\":\\\"9\\\"}\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.constraintType").value("TIME"));
    }

    @Test
    void revokeUserRole_returns204() throws Exception {
        mvc.perform(delete("/v1/rbac/principals/5/roles/10"))
                .andExpect(status().isNoContent());
        verify(admin).revokeUserRole(eq(5L), eq(10L));
    }

    @Test
    void deactivateConstraint_returns204() throws Exception {
        mvc.perform(delete("/v1/rbac/constraints/7"))
                .andExpect(status().isNoContent());
        verify(admin).deactivateConstraint(eq(7L));
    }
}
