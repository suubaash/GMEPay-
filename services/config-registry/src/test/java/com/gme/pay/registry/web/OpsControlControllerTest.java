package com.gme.pay.registry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.ops.OpsControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice test for {@link OpsControlController} — exercises the HTTP
 * surface (status read + pause + suspend) against the real service and DB.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OpsControlControllerTest.TestConfig.class, OpsControlService.class,
         AuditLogService.class, CacheConfig.class})
class OpsControlControllerTest {

    @Autowired
    private OpsControlService service;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        AuditPublisher auditPublisher(RecordingAuditPublisher r) {
            return r;
        }
    }

    private MockMvc mvc() {
        return standaloneSetup(new OpsControlController(service)).build();
    }

    @Test
    @DisplayName("GET operational-status returns all-clear on a fresh platform")
    void statusAllClear() throws Exception {
        mvc().perform(get("/v1/ops/operational-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemPaused").value(false))
                .andExpect(jsonPath("$.maintenanceMode").value(false))
                .andExpect(jsonPath("$.suspendedPartners.length()").value(0));
    }

    @Test
    @DisplayName("POST pause then GET status reflects systemPaused=true")
    void pauseReflectedInStatus() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(post("/v1/ops/pause")
                        .header("X-Actor", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"incident\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemPaused").value(true))
                .andExpect(jsonPath("$.reason").value("incident"));

        mvc.perform(get("/v1/ops/operational-status"))
                .andExpect(jsonPath("$.systemPaused").value(true));
    }

    @Test
    @DisplayName("POST suspend puts the entity in the right bucket")
    void suspendBucket() throws Exception {
        mvc().perform(post("/v1/ops/suspend")
                        .header("X-Actor", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entityType\":\"PARTNER\",\"entityId\":\"ACME\",\"reason\":\"fraud\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspendedPartners[0]").value("ACME"));
    }

    @Test
    @DisplayName("POST suspend with bad entityType is a 400")
    void suspendBadType() throws Exception {
        mvc().perform(post("/v1/ops/suspend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entityType\":\"WIDGET\",\"entityId\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }
}
