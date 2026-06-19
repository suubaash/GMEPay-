package com.gme.pay.auth.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.auth.approval.ApprovalDtos.ApprovalRequestView;
import com.gme.pay.auth.approval.ApprovalDtos.DecisionLookup;
import com.gme.pay.auth.approval.ApprovalDtos.RequestApprovalCommand;
import com.gme.pay.rbac.RbacHeaders;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link ApprovalController}: HTTP surface + the key security
 * property that approver/requester identity comes from the edge-stamped {@link RbacHeaders},
 * never the body.
 */
class ApprovalControllerTest {

    private ApprovalWorkflowService svc;
    private MockMvc mvc;

    private static ApprovalRequestView view(String status) {
        return new ApprovalRequestView(1L, "REFUND", "RF-1", new BigDecimal("2500.00"), "USD", "L1",
                status, List.of("refund.approve_l1"), 1, status.equals("PENDING") ? 0 : 1,
                "op.maria", Instant.parse("2026-06-17T00:00:00Z"), null, null, null, List.of());
    }

    @BeforeEach
    void setUp() {
        svc = mock(ApprovalWorkflowService.class);
        mvc = standaloneSetup(new ApprovalController(svc)).build();
    }

    @Test
    void create_withoutPrincipalHeader_isUnauthorized() throws Exception {
        mvc.perform(post("/v1/approvals").contentType("application/json")
                        .content("{\"requestType\":\"REFUND\",\"subjectRef\":\"RF-1\",\"amount\":2500,\"currency\":\"USD\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_usesStampedPrincipalAsRequester() throws Exception {
        when(svc.request(any())).thenReturn(view("PENDING"));
        mvc.perform(post("/v1/approvals")
                        .header(RbacHeaders.PRINCIPAL_ID, "op.maria")
                        .contentType("application/json")
                        .content("{\"requestType\":\"REFUND\",\"subjectRef\":\"RF-1\",\"amount\":2500,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        ArgumentCaptor<RequestApprovalCommand> cmd = ArgumentCaptor.forClass(RequestApprovalCommand.class);
        verify(svc).request(cmd.capture());
        assertThat(cmd.getValue().requestedBy()).isEqualTo("op.maria");
        assertThat(cmd.getValue().subjectRef()).isEqualTo("RF-1");
    }

    @Test
    void approve_passesStampedApproverAndPermissions() throws Exception {
        when(svc.approve(eq(1L), eq("op.kim"), any(), any())).thenReturn(view("APPROVED"));
        mvc.perform(post("/v1/approvals/1/approve")
                        .header(RbacHeaders.PRINCIPAL_ID, "op.kim")
                        .header(RbacHeaders.PERMISSIONS, "refund.approve_l1,txn.view")
                        .contentType("application/json").content("{\"reason\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> perms = ArgumentCaptor.forClass(Set.class);
        verify(svc).approve(eq(1L), eq("op.kim"), perms.capture(), eq("ok"));
        assertThat(perms.getValue()).contains("refund.approve_l1", "txn.view");
    }

    @Test
    void pendingQueue_returnsList() throws Exception {
        when(svc.listPending()).thenReturn(List.of(view("PENDING")));
        mvc.perform(get("/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tierLabel").value("L1"));
    }

    @Test
    void decisionBridge_returnsLookup() throws Exception {
        when(svc.decision(eq("REFUND"), eq("RF-1"), any())).thenReturn(new DecisionLookup(true, "APPROVED"));
        mvc.perform(get("/v1/approvals/decision").param("type", "REFUND").param("subjectRef", "RF-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }
}
