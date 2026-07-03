package com.gme.pay.registry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.settings.PlatformSettingService;
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
 * MockMvc slice test for {@link PlatformSettingController} — exercises the HTTP surface
 * (list + get + 404 + PUT upsert + NUMBER validation) against the real service and DB.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PlatformSettingControllerTest.TestConfig.class, PlatformSettingService.class,
         AuditLogService.class, CacheConfig.class})
class PlatformSettingControllerTest {

    @Autowired
    private PlatformSettingService service;

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
        return standaloneSetup(new PlatformSettingController(service)).build();
    }

    @Test
    @DisplayName("GET /v1/admin/settings lists the seeded rows, key-sorted")
    void listSeeded() throws Exception {
        mvc().perform(get("/v1/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].key").value("fx.quote.ttl.seconds"))
                .andExpect(jsonPath("$[4].key").value("wallet.fee.krw"));
    }

    @Test
    @DisplayName("GET /v1/admin/settings/{key} returns one row")
    void getOne() throws Exception {
        mvc().perform(get("/v1/admin/settings/prefunding.alert.tier1.pct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("95"))
                .andExpect(jsonPath("$.valueType").value("NUMBER"));
    }

    @Test
    @DisplayName("GET unknown key is 404")
    void getUnknown() throws Exception {
        mvc().perform(get("/v1/admin/settings/nope.nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT upserts the value and returns the updated row")
    void putUpsert() throws Exception {
        mvc().perform(put("/v1/admin/settings/prefunding.alert.tier1.pct")
                        .header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"90\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("90"))
                .andExpect(jsonPath("$.updatedBy").value("alice"));
    }

    @Test
    @DisplayName("PUT a non-numeric NUMBER value is a 400")
    void putBadNumber() throws Exception {
        mvc().perform(put("/v1/admin/settings/wallet.fee.krw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }
}
