package com.gme.pay.bff.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.bff.alert.OpsAlertEventHandler;
import com.gme.pay.bff.alert.OpsAlertStore;
import com.gme.pay.bff.alert.OpsAlertView;
import com.gme.pay.bff.alert.paging.TestPaging;
import com.gme.pay.bff.client.stub.StubOperatorActionAuditClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * MockMvc test for {@link OpsAlertAckController}: ack marks the alert acknowledged and
 * audits; RBAC is fail-closed (403 without {@code ops:operate}).
 */
class OpsAlertAckControllerTest {

    private OpsAlertStore store;
    private StubOperatorActionAuditClient audit;

    private static final String CRIT =
            "{\"eventType\":\"ops.alert\",\"alertType\":\"STUCK_TXN\",\"severity\":\"CRITICAL\","
                    + "\"subjectRef\":\"TXN-9\",\"detail\":\"stuck\",\"occurredAt\":\"2026-07-02T00:00:00Z\"}";

    @BeforeEach
    void setUp() {
        store = new OpsAlertStore(200);
        audit = new StubOperatorActionAuditClient();
        new OpsAlertEventHandler(store, TestPaging.dispatcher(new TestPaging.RecordingPort(), store))
                .handle("TXN-9", CRIT);
    }

    private MockMvc mvc(boolean enforce) {
        return standaloneSetup(new OpsAlertAckController(store, audit, new OpsRbacGuard(enforce)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @Test
    void ack_marksAcknowledgedAndAudits() throws Exception {
        long seq = store.recent(null, null, 0).get(0).seq();
        mvc(false).perform(post("/v1/admin/ops/alerts/" + seq + "/ack")
                        .header("X-Gme-Principal-Id", "ops.admin@gmepay.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ops.admin@gmepay.com\",\"note\":\"investigating\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ack.operator").value("ops.admin@gmepay.com"))
                .andExpect(jsonPath("$.ack.note").value("investigating"));

        OpsAlertView stored = store.find(seq).orElseThrow();
        assertThat(stored.acked()).isTrue();
        assertThat(audit.captured()).singleElement()
                .satisfies(r -> assertThat(r.action()).isEqualTo("ops.alert.ack"));
    }

    @Test
    void ack_forbiddenWithoutOpsOperate() throws Exception {
        long seq = store.recent(null, null, 0).get(0).seq();
        // enforce=true and NO permissions header -> 403 (fail closed).
        mvc(true).perform(post("/v1/admin/ops/alerts/" + seq + "/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"x\"}"))
                .andExpect(status().isForbidden());
        assertThat(store.find(seq).orElseThrow().acked()).isFalse();
    }

    @Test
    void ack_forbiddenWhenPermissionPresentButLacksOpsOperate() throws Exception {
        long seq = store.recent(null, null, 0).get(0).seq();
        mvc(false).perform(post("/v1/admin/ops/alerts/" + seq + "/ack")
                        .header("X-Gme-Permissions", "partner:read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
