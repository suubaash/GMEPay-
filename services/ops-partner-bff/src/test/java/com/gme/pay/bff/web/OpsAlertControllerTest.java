package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.bff.alert.OpsAlertEventHandler;
import com.gme.pay.bff.alert.OpsAlertStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * MockMvc test for {@link OpsAlertController}: consumed alerts are returned newest-first by
 * {@code GET /v1/admin/ops/alerts}, with severity/type filtering (alert loop #5).
 */
class OpsAlertControllerTest {

    private MockMvc mvc;
    private OpsAlertStore store;

    @BeforeEach
    void setUp() {
        store = new OpsAlertStore(200);
        OpsAlertEventHandler handler = new OpsAlertEventHandler(store,
                com.gme.pay.bff.alert.paging.TestPaging.dispatcher(
                        new com.gme.pay.bff.alert.paging.TestPaging.RecordingPort(), store));
        handler.handle("P_B", "{\"eventType\":\"ops.alert\",\"alertType\":\"FLOAT_LOW\","
                + "\"severity\":\"WARN\",\"subjectRef\":\"P_B\",\"detail\":\"low\",\"occurredAt\":\"t1\"}");
        handler.handle("TXN-9", "{\"eventType\":\"ops.alert\",\"alertType\":\"STUCK_TXN\","
                + "\"severity\":\"CRITICAL\",\"subjectRef\":\"TXN-9\",\"detail\":\"stuck\",\"occurredAt\":\"t2\"}");
        mvc = standaloneSetup(new OpsAlertController(store))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @Test
    void listsNewestFirst() throws Exception {
        mvc.perform(get("/v1/admin/ops/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].alertType").value("STUCK_TXN"))
                .andExpect(jsonPath("$[1].alertType").value("FLOAT_LOW"));
    }

    @Test
    void filtersBySeverity() throws Exception {
        mvc.perform(get("/v1/admin/ops/alerts").param("severity", "CRITICAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subjectRef").value("TXN-9"));
    }

    @Test
    void filtersByType() throws Exception {
        mvc.perform(get("/v1/admin/ops/alerts").param("type", "FLOAT_LOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subjectRef").value("P_B"));
    }
}
