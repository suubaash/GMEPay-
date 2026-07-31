package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.bff.client.stub.StubApprovalQueueClient;
import com.gme.pay.bff.security.TestTokens;
import com.gme.pay.rbac.RbacHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link ApprovalAdminController} using the real
 * {@link StubApprovalQueueClient}, exercising the Admin-UI approval-queue contract end-to-end.
 *
 * <p>The approver identity + permissions forwarded to auth-identity now come from the verified
 * token (T0-3), so these tests authenticate instead of setting {@code X-Gme-*} headers.
 */
class ApprovalAdminControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ApprovalAdminController(new StubApprovalQueueClient(), new OpsRbacGuard(true)))
                .build();
        TestTokens.authenticate("op.kim", null, "refund.approve_l1");
    }

    @AfterEach
    void tearDown() {
        TestTokens.clear();
    }

    @Test
    @DisplayName("GET /v1/admin/approvals returns the pending queue with tier + step progress")
    void pending() throws Exception {
        mvc.perform(get("/v1/admin/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tierLabel").value("L1"))
                .andExpect(jsonPath("$[1].tierLabel").value("L2_CFO"))
                .andExpect(jsonPath("$[1].requiredSteps").value(2));
    }

    @Test
    @DisplayName("POST approve returns the decided request with the token subject as approver")
    void approve() throws Exception {
        mvc.perform(post("/v1/admin/approvals/1001/approve")
                        .contentType("application/json").content("{\"reason\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisions[0].approverId").value("op.kim"));
    }

    @Test
    @DisplayName("POST reject returns the rejected request carrying the reason")
    void reject() throws Exception {
        mvc.perform(post("/v1/admin/approvals/1001/reject")
                        .contentType("application/json").content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("duplicate"));
    }

    @Test
    @DisplayName("a spoofed X-Gme-Principal-Id / X-Gme-Permissions no longer decides the approver")
    void spoofedHeadersAreIgnored() throws Exception {
        mvc.perform(post("/v1/admin/approvals/1001/approve")
                        .header(RbacHeaders.PRINCIPAL_ID, "someone.else")
                        .header(RbacHeaders.PERMISSIONS, "approval.cfo_override")
                        .contentType("application/json").content("{\"reason\":\"ok\"}"))
                .andExpect(status().isOk())
                // recorded against the TOKEN subject, not the header
                .andExpect(jsonPath("$.decisions[0].approverId").value("op.kim"));
    }
}
