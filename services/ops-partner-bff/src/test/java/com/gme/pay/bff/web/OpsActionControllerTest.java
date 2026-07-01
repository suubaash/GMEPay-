package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.OpsControlClient;
import com.gme.pay.bff.client.OperatorActionAuditClient.OperatorActionRecord;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.stub.StubOperatorActionAuditClient;
import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.rbac.RbacHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * MockMvc tests for {@link OpsActionController}: each operator action delegates to its
 * upstream AND records an operator-action audit entry (verified via the in-memory
 * {@link StubOperatorActionAuditClient}). Also covers RBAC guard + reason capture.
 */
class OpsActionControllerTest {

    private MockMvc mvc;
    private OpsControlClient opsControl;
    private SettlementClient settlements;
    private StubOperatorActionAuditClient audit;

    @BeforeEach
    void setUp() {
        opsControl = mock(OpsControlClient.class);
        settlements = mock(SettlementClient.class);
        audit = new StubOperatorActionAuditClient();

        when(opsControl.pause(any(), any())).thenReturn(new OperationalStatusView(
                true, false, List.of(), List.of(), List.of(), "boom", "2026-07-01T00:00:00Z"));
        when(opsControl.resume(any())).thenReturn(OperationalStatusView.allClear());
        when(opsControl.maintenance(any(), any())).thenReturn(new OperationalStatusView(
                false, true, List.of(), List.of(), List.of(), "maint", "2026-07-01T00:00:00Z"));
        when(opsControl.suspend(any(), any(), any(), any())).thenReturn(new OperationalStatusView(
                false, false, List.of("P1"), List.of(), List.of(), "risk", "2026-07-01T00:00:00Z"));
        when(opsControl.unsuspend(any(), any(), any())).thenReturn(OperationalStatusView.allClear());
        when(settlements.rerunRecon(any(), any(), any()))
                .thenReturn(new SettlementClient.ReconRerunResult("COMPLETED", 10, 1, "ok"));

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new OpsActionController(opsControl, settlements, audit))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void pause_delegatesAndAudits() throws Exception {
        mvc.perform(post("/v1/admin/ops/pause")
                        .header(RbacHeaders.PRINCIPAL_ID, "ops.admin@gmepay.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"scheme outage\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemPaused").value(true));

        verify(opsControl).pause("ops.admin@gmepay.com", "scheme outage");
        List<OperatorActionRecord> recs = audit.captured();
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).action()).isEqualTo("ops.pause");
        assertThat(recs.get(0).actor()).isEqualTo("ops.admin@gmepay.com");
        assertThat(recs.get(0).reason()).isEqualTo("scheme outage");
        assertThat(recs.get(0).target()).isEqualTo("system");
    }

    @Test
    void resume_delegatesAndAudits() throws Exception {
        mvc.perform(post("/v1/admin/ops/resume")
                        .header(RbacHeaders.PRINCIPAL_ID, "ops.admin@gmepay.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(opsControl).resume("ops.admin@gmepay.com");
        assertThat(audit.captured()).singleElement()
                .extracting(OperatorActionRecord::action).isEqualTo("ops.resume");
    }

    @Test
    void maintenance_delegatesAndAudits() throws Exception {
        mvc.perform(post("/v1/admin/ops/maintenance")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"db upgrade\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenanceMode").value(true));
        verify(opsControl).maintenance("unknown", "db upgrade");
        assertThat(audit.captured()).singleElement()
                .extracting(OperatorActionRecord::action).isEqualTo("ops.maintenance");
    }

    @Test
    void suspend_delegatesAndAudits() throws Exception {
        mvc.perform(post("/v1/admin/ops/suspend")
                        .header(RbacHeaders.PRINCIPAL_ID, "ops.admin@gmepay.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"partner\",\"ref\":\"P1\",\"reason\":\"risk\"}"))
                .andExpect(status().isOk());
        verify(opsControl).suspend("partner", "P1", "ops.admin@gmepay.com", "risk");
        assertThat(audit.captured()).singleElement()
                .satisfies(r -> {
                    assertThat(r.action()).isEqualTo("ops.suspend");
                    assertThat(r.target()).isEqualTo("partner:P1");
                });
    }

    @Test
    void suspend_missingRef_is400_andNoAudit() throws Exception {
        mvc.perform(post("/v1/admin/ops/suspend")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"partner\"}"))
                .andExpect(status().isBadRequest());
        assertThat(audit.captured()).isEmpty();
    }

    @Test
    void unsuspend_delegatesAndAudits() throws Exception {
        mvc.perform(post("/v1/admin/ops/unsuspend")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"scheme\",\"ref\":\"zeropay_kr\"}"))
                .andExpect(status().isOk());
        verify(opsControl).unsuspend(eq("scheme"), eq("zeropay_kr"), any());
        assertThat(audit.captured()).singleElement()
                .extracting(OperatorActionRecord::action).isEqualTo("ops.unsuspend");
    }

    @Test
    void reconRerun_delegatesAndAudits() throws Exception {
        mvc.perform(post("/v1/admin/settlements/recon/rerun")
                        .header(RbacHeaders.PRINCIPAL_ID, "ops.finance@gmepay.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-06-30\",\"reason\":\"late file\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.matched").value(10));
        verify(settlements).rerunRecon("2026-06-30", "ops.finance@gmepay.com", "late file");
        assertThat(audit.captured()).singleElement()
                .satisfies(r -> {
                    assertThat(r.action()).isEqualTo("settlement.recon.rerun");
                    assertThat(r.target()).isEqualTo("2026-06-30");
                });
    }

    @Test
    void rbacGuard_rejectsWhenPermissionsPresentButLacksOps() throws Exception {
        mvc.perform(post("/v1/admin/ops/pause")
                        .header(RbacHeaders.PERMISSIONS, "partner:read,partner:write")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(audit.captured()).isEmpty();
    }

    @Test
    void rbacGuard_allowsWhenOpsPermissionPresent() throws Exception {
        mvc.perform(post("/v1/admin/ops/pause")
                        .header(RbacHeaders.PERMISSIONS, "partner:read,ops:operate")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(audit.captured()).hasSize(1);
    }
}
