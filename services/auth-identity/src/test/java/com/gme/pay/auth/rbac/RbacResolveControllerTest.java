package com.gme.pay.auth.rbac;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** Standalone MockMvc test for {@link RbacResolveController} over a mocked resolution service. */
class RbacResolveControllerTest {

    private RbacResolutionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RbacResolutionService.class);
        mvc = standaloneSetup(new RbacResolveController(service)).build();
    }

    @Test
    void getByPrincipal_returnsResolvedAccess() throws Exception {
        when(service.resolve(eq(5L))).thenReturn(new ResolvedAccess(
                5L, "ops.kim", "700", List.of("HUB_ADMIN"),
                List.of("partner.view", "rbac.manage"), "TIME:timezone=Asia/Tokyo;startHour=9"));

        mvc.perform(get("/v1/rbac/principals/5/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalId").value(5))
                .andExpect(jsonPath("$.username").value("ops.kim"))
                .andExpect(jsonPath("$.tenantId").value("700"))
                .andExpect(jsonPath("$.permissions.length()").value(2))
                .andExpect(jsonPath("$.permissions[0]").value("partner.view"))
                .andExpect(jsonPath("$.constraints").value("TIME:timezone=Asia/Tokyo;startHour=9"));
    }

    @Test
    void postResolve_byPrincipalId_returnsResolvedAccess() throws Exception {
        when(service.resolve(eq(9L))).thenReturn(new ResolvedAccess(
                9L, "ops.lee", null, List.of("HUB_OPERATOR"), List.of("txn.view"), ""));

        mvc.perform(post("/v1/rbac/resolve").contentType("application/json").content("{\"principalId\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0]").value("txn.view"));
    }

    @Test
    void postResolve_withNeitherIdNorUsername_returns400() throws Exception {
        mvc.perform(post("/v1/rbac/resolve").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
