package com.gme.pay.auth.internalauth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.auth.approval.ApprovalController;
import com.gme.pay.auth.approval.ApprovalWorkflowService;
import com.gme.pay.auth.rbac.RbacAdminController;
import com.gme.pay.auth.rbac.RbacAdminService;
import com.gme.pay.internalauth.InternalAuthFilter;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.rbac.RbacHeaders;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #90: proves the service-to-service internal-auth gate actually fronts auth-identity's internal-only
 * surface. The REAL {@link InternalAuthFilter} runs ahead of the REAL {@link RbacAdminController} and
 * {@link ApprovalController} (services mocked). A direct caller with no / a forged {@code X-Gme-Internal}
 * token is refused 401 before the controller; a trusted caller presenting the secret reaches it. This is
 * the perimeter that closes the open {@code /v1/rbac/resolve}-style + unannotated {@code /approve} vectors.
 */
class InternalAuthGateTest {

    private static final String SECRET = "internal-svc-secret";

    private RbacAdminService rbacAdmin;
    private ApprovalWorkflowService approvals;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        rbacAdmin = mock(RbacAdminService.class);
        approvals = mock(ApprovalWorkflowService.class);
        mvc = standaloneSetup(new RbacAdminController(rbacAdmin), new ApprovalController(approvals))
                .addFilters(new InternalAuthFilter(SECRET, List.of("/v1/rbac/**", "/v1/approvals/**")))
                .build();
    }

    @Test
    @DisplayName("RBAC management without the internal token → 401, controller never invoked")
    void rbacManagementWithoutTokenRefused() throws Exception {
        mvc.perform(get("/v1/rbac/permissions"))
                .andExpect(status().isUnauthorized());
        verify(rbacAdmin, never()).listPermissions();
    }

    @Test
    @DisplayName("RBAC management with the internal token → reaches the controller")
    void rbacManagementWithTokenReachesController() throws Exception {
        when(rbacAdmin.listPermissions()).thenReturn(List.of());
        mvc.perform(get("/v1/rbac/permissions").header(InternalAuthHeaders.INTERNAL_TOKEN, SECRET))
                .andExpect(status().isOk());
        verify(rbacAdmin).listPermissions();
    }

    @Test
    @DisplayName("RBAC resolution (unannotated, the mint/enumerate vector) is gated too")
    void rbacResolveGated() throws Exception {
        mvc.perform(post("/v1/rbac/resolve").contentType("application/json").content("{\"username\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("approval decision without the internal token → 401, approve never invoked (forgery blocked)")
    void approvalDecisionWithoutTokenRefused() throws Exception {
        mvc.perform(post("/v1/approvals/1/approve")
                        .header(RbacHeaders.PRINCIPAL_ID, "5")
                        .header(RbacHeaders.PERMISSIONS, "approval.approve"))
                .andExpect(status().isUnauthorized());
        verify(approvals, never()).approve(any(), any(), any(), any());
    }

    @Test
    @DisplayName("approval decision with the internal token + stamped principal → reaches the controller")
    void approvalDecisionWithTokenReachesController() throws Exception {
        mvc.perform(post("/v1/approvals/1/approve")
                        .header(InternalAuthHeaders.INTERNAL_TOKEN, SECRET)
                        .header(RbacHeaders.PRINCIPAL_ID, "5")
                        .header(RbacHeaders.PERMISSIONS, "approval.approve"))
                .andExpect(status().isOk());
        verify(approvals).approve(eq(1L), eq("5"), any(), any());
    }
}
