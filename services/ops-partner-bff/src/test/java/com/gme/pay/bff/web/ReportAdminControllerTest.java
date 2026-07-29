package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.bff.client.stub.StubReportingClient;
import com.gme.pay.bff.config.AdminSurfaceRbacInterceptor;
import com.gme.pay.bff.security.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link ReportAdminController} using the real
 * {@link StubReportingClient}, so the BOK run-list/download shape is exercised end-to-end.
 *
 * <p>Runs behind the real {@link AdminSurfaceRbacInterceptor} with RBAC <b>enforcing</b>
 * ({@code OpsRbacGuard(true)}), and authenticates through {@link TestTokens} — the same path
 * production takes (verified token → claims → guard). {@code ops:operate} covers both the coarse
 * admin-read gate and the admin-write gate the generate POST goes through; {@code report.generate}
 * is the fine-grained permission on that handler.
 */
class ReportAdminControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ReportAdminController(new StubReportingClient()))
                .addMappedInterceptors(new String[]{"/v1/admin/**"},
                        new AdminSurfaceRbacInterceptor(new OpsRbacGuard(true)))
                .build();
        TestTokens.hubOperator("ops:operate", "report.generate");
    }

    @AfterEach
    void clearAuthentication() {
        TestTokens.clear();
    }

    @Test
    @DisplayName("GET /v1/admin/reports returns the BOK runs with string recordCount + download url")
    void list_returnsRuns() throws Exception {
        mvc.perform(get("/v1/admin/reports").param("from", "2025-06-01").param("to", "2025-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("BOK_FX1014"))
                .andExpect(jsonPath("$[0].recordCount").isString())
                .andExpect(jsonPath("$[0].downloadUrl").value(org.hamcrest.Matchers.containsString("/download")));
    }

    @Test
    @DisplayName("T5-2: the offline stub reports NOT_FILED_CHANNEL_UNAVAILABLE with a reason, never a filing")
    void list_offlineRunsDoNotClaimAFiling() throws Exception {
        mvc.perform(get("/v1/admin/reports").param("from", "2025-06-01").param("to", "2025-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("NOT_FILED_CHANNEL_UNAVAILABLE"))
                .andExpect(jsonPath("$[1].status").value("NOT_FILED_CHANNEL_UNAVAILABLE"))
                .andExpect(jsonPath("$[0].filingChannelUnavailableReason")
                        .value(org.hamcrest.Matchers.containsString("nothing has been generated or filed")))
                .andExpect(jsonPath("$[0].filingChannels.length()").value(3))
                .andExpect(jsonPath("$[0].filingChannels[0].channelLive").value(false))
                .andExpect(jsonPath("$[0].filingChannels[1].channelLive").value(false))
                .andExpect(jsonPath("$[0].filingChannels[2].channelLive").value(false))
                .andExpect(jsonPath("$[0].filingChannels[2].reachableStatus")
                        .value("NOT_FILED_CHANNEL_UNAVAILABLE"));
    }

    @Test
    @DisplayName("GET /v1/admin/reports?type=BOK_FX1015 narrows to one run")
    void list_filteredByType() throws Exception {
        mvc.perform(get("/v1/admin/reports").param("type", "BOK_FX1015"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("BOK_FX1015"));
    }

    @Test
    @DisplayName("POST /v1/admin/reports/{type}/generate recomputes and returns the run")
    void generate_returnsRun() throws Exception {
        mvc.perform(post("/v1/admin/reports/BOK_FX1014/generate")
                        .contentType("application/json").content("{\"period\":\"2025-06\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("BOK_FX1014"));
    }

    @Test
    @DisplayName("GET /v1/admin/reports/{id}/download streams CSV as an attachment, with no fabricated filing status")
    void download_returnsCsv() throws Exception {
        mvc.perform(get("/v1/admin/reports/BOK_FX1014~2025-06-01~2025-06-30/download"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("report_type")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SUBMITTED"))));
    }

    @Test
    @DisplayName("a partner-scoped token cannot read the admin reports surface (403)")
    void list_deniedForPartnerToken() throws Exception {
        TestTokens.clear();
        TestTokens.partner("PARTNER_A", "portal.view");

        mvc.perform(get("/v1/admin/reports"))
                .andExpect(status().isForbidden());
    }
}
