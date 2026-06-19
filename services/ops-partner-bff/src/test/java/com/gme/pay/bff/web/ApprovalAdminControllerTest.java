package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.bff.client.stub.StubApprovalQueueClient;
import com.gme.pay.rbac.RbacHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link ApprovalAdminController} using the real
 * {@link StubApprovalQueueClient}, exercising the Admin-UI approval-queue contract end-to-end.
 */
class ApprovalAdminControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ApprovalAdminController(new StubApprovalQueueClient())).build();
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
    @DisplayName("POST approve returns the decided request with the approver recorded")
    void approve() throws Exception {
        mvc.perform(post("/v1/admin/approvals/1001/approve")
                        .header(RbacHeaders.PRINCIPAL_ID, "op.kim")
                        .header(RbacHeaders.PERMISSIONS, "refund.approve_l1")
                        .contentType("application/json").content("{\"reason\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisions[0].approverId").value("op.kim"));
    }

    @Test
    @DisplayName("POST reject returns the rejected request carrying the reason")
    void reject() throws Exception {
        mvc.perform(post("/v1/admin/approvals/1001/reject")
                        .header(RbacHeaders.PRINCIPAL_ID, "op.kim")
                        .header(RbacHeaders.PERMISSIONS, "refund.approve_l1")
                        .contentType("application/json").content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("duplicate"));
    }
}
