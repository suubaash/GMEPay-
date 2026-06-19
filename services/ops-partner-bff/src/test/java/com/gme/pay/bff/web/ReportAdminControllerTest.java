package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.bff.client.stub.StubReportingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link ReportAdminController} using the real
 * {@link StubReportingClient}, so the BOK run-list/download shape is exercised end-to-end.
 */
class ReportAdminControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ReportAdminController(new StubReportingClient())).build();
    }

    @Test
    @DisplayName("GET /v1/admin/reports returns the BOK runs with string recordCount + download url")
    void list_returnsRuns() throws Exception {
        mvc.perform(get("/v1/admin/reports").param("from", "2025-06-01").param("to", "2025-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("BOK_FX1014"))
                .andExpect(jsonPath("$[0].status").value("GENERATED"))
                .andExpect(jsonPath("$[0].recordCount").isString())
                .andExpect(jsonPath("$[0].downloadUrl").value(org.hamcrest.Matchers.containsString("/download")));
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
    @DisplayName("GET /v1/admin/reports/{id}/download streams CSV as an attachment")
    void download_returnsCsv() throws Exception {
        mvc.perform(get("/v1/admin/reports/BOK_FX1014~2025-06-01~2025-06-30/download"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("report_type")));
    }
}
