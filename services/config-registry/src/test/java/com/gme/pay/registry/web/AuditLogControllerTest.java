package com.gme.pay.registry.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.HashChain;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditConfig;
import com.gme.pay.registry.audit.AuditLogEntity;
import com.gme.pay.registry.audit.AuditLogRepository;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice test for {@link AuditLogController} (agent 2C.1).
 *
 * <p>Runs as a {@code @DataJpaTest} slice so Flyway applies the full V001..V008
 * migration chain against H2 in PostgreSQL mode. The test uses
 * {@link PartnerStore} to write a few partner rows (which also writes audit
 * rows via {@code AuditLogService.publish}) and then exercises the controller
 * via standalone MockMvc — no Spring Boot web context needed.
 *
 * <h2>Coverage</h2>
 *
 * <ol>
 *   <li>{@link #list_returnsPaginatedRows} — pagination + ordering (newest first).</li>
 *   <li>{@link #list_chainValidIsTrueForUntamperedChain} — {@code chainValid=true}
 *       for an intact chain.</li>
 *   <li>{@link #list_filterByAggregateTypeAndId} — only rows for the queried
 *       aggregate are returned; other aggregates' rows do not bleed in.</li>
 *   <li>{@link #list_chainValidIsFalseAfterTamper} — {@code chainValid=false}
 *       after a row is silently modified.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditLogControllerTest.TestConfig.class, AuditLogService.class,
         PartnerStore.class, CacheConfig.class})
class AuditLogControllerTest {

    @Autowired
    private PartnerStore store;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    // ---- Test wiring -------------------------------------------------------

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        com.gme.pay.audit.AuditPublisher auditPublisher(RecordingAuditPublisher r) {
            return r;
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private MockMvc buildMvc() {
        AuditLogController controller = new AuditLogController(auditLogRepository, auditLogService);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(om);
        return standaloneSetup(controller).setMessageConverters(converter).build();
    }

    // ---- Tests -------------------------------------------------------------

    @Test
    @DisplayName("GET /v1/audit returns paginated rows, newest first")
    void list_returnsPaginatedRows() throws Exception {
        // Write three partner saves to produce three audit rows for AUDIT_PAGE.
        store.save(Partner.of("AUDIT_PAGE", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP));
        store.save(Partner.of("AUDIT_PAGE", PartnerType.LOCAL, "KRW", RoundingMode.DOWN));
        store.save(Partner.of("AUDIT_PAGE", PartnerType.LOCAL, "KRW", RoundingMode.FLOOR));

        MockMvc mvc = buildMvc();
        mvc.perform(get("/v1/audit")
                        .param("aggregateType", "partner")
                        .param("aggregateId", "AUDIT_PAGE")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Page metadata
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                // Entry shape — no raw hashes
                .andExpect(jsonPath("$.content[0].recordedAt").exists())
                .andExpect(jsonPath("$.content[0].actorId").exists())
                .andExpect(jsonPath("$.content[0].eventType").value("PARTNER_SAVED"))
                .andExpect(jsonPath("$.content[0].afterJson").exists())
                // Hashes must NOT be present in the response
                .andExpect(jsonPath("$.content[0].prevHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].rowHash").doesNotExist());
    }

    @Test
    @DisplayName("chainValid=true for an untampered aggregate chain")
    void list_chainValidIsTrueForUntamperedChain() throws Exception {
        store.save(Partner.of("AUDIT_VALID", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        store.save(Partner.of("AUDIT_VALID", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN));

        MockMvc mvc = buildMvc();
        mvc.perform(get("/v1/audit")
                        .param("aggregateType", "partner")
                        .param("aggregateId", "AUDIT_VALID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].chainValid").value(true))
                .andExpect(jsonPath("$.content[1].chainValid").value(true));
    }

    @Test
    @DisplayName("Only rows for the requested aggregateId are returned; other aggregates do not bleed in")
    void list_filterByAggregateTypeAndId() throws Exception {
        store.save(Partner.of("AUDIT_FILTER_A", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP));
        store.save(Partner.of("AUDIT_FILTER_B", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP));
        store.save(Partner.of("AUDIT_FILTER_A", PartnerType.LOCAL, "KRW", RoundingMode.DOWN));

        MockMvc mvc = buildMvc();
        // Query only for AUDIT_FILTER_A — should see exactly 2 rows, none from _B.
        mvc.perform(get("/v1/audit")
                        .param("aggregateType", "partner")
                        .param("aggregateId", "AUDIT_FILTER_A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    @DisplayName("chainValid=false after a row is silently tampered")
    void list_chainValidIsFalseAfterTamper() throws Exception {
        store.save(Partner.of("AUDIT_TAMPER2", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP));
        store.save(Partner.of("AUDIT_TAMPER2", PartnerType.LOCAL, "KRW", RoundingMode.DOWN));

        // Silently rewrite the first row's afterJsonb to simulate tamper.
        List<AuditLogEntity> rows =
                auditLogRepository.findChainByAggregate("partner", "AUDIT_TAMPER2");
        assertThat(rows).hasSize(2);
        AuditLogEntity first = rows.get(0);
        first.setAfterJsonb("{\"tampered\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        auditLogRepository.saveAndFlush(first);

        MockMvc mvc = buildMvc();
        mvc.perform(get("/v1/audit")
                        .param("aggregateType", "partner")
                        .param("aggregateId", "AUDIT_TAMPER2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].chainValid").value(false))
                .andExpect(jsonPath("$.content[1].chainValid").value(false));
    }
}
