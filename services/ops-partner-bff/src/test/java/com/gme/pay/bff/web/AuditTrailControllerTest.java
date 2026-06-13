package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.AuditTrailClient;
import com.gme.pay.bff.client.stub.StubAuditTrailClient;
import com.gme.pay.contracts.AuditEntryView;
import com.gme.pay.contracts.PageView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for {@link AuditTrailController} (agent 2C.1 — BFF layer).
 *
 * <p>Uses the real {@link StubAuditTrailClient} (which returns an empty page) to verify
 * the controller wiring, and a seeded fake client to verify non-empty pagination.
 * Does NOT test config-registry integration — that lives in config-registry's own slice test.
 */
class AuditTrailControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = buildMvc(new StubAuditTrailClient());
    }

    private MockMvc buildMvc(AuditTrailClient client) {
        AuditTrailController controller = new AuditTrailController(client);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);
        return standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("GET /v1/admin/audit-trail returns empty page from stub (no config-registry)")
    void list_stubReturnsEmptyPage() throws Exception {
        mvc.perform(get("/v1/admin/audit-trail")
                        .param("aggregateType", "partner")
                        .param("aggregateId", "GMEREMIT"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("GET /v1/admin/audit-trail passes through paginated entries with chainValid")
    void list_passesThrough_paginatedEntries() throws Exception {
        // Seed three entries in a fake client
        Instant now = Instant.parse("2026-06-11T10:00:00Z");
        List<AuditEntryView> entries = List.of(
                new AuditEntryView(now, "ops.admin@gme.com", "PARTNER_SAVED",
                        null, "{\"partnerCode\":\"ACE\"}", true),
                new AuditEntryView(now.minusSeconds(60), "ops.admin@gme.com", "PARTNER_SAVED",
                        null, "{\"partnerCode\":\"ACE\"}", true),
                new AuditEntryView(now.minusSeconds(120), "ops.admin@gme.com", "PARTNER_SAVED",
                        null, "{\"partnerCode\":\"ACE\"}", true));
        AuditTrailClient seeded = (aggregateType, aggregateId, page, size) ->
                new PageView<>(entries.subList(page * size, Math.min(page * size + size, entries.size())),
                        page, size, entries.size());

        MockMvc seededMvc = buildMvc(seeded);
        seededMvc.perform(get("/v1/admin/audit-trail")
                        .param("aggregateType", "partner")
                        .param("aggregateId", "ACE")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].chainValid").value(true))
                .andExpect(jsonPath("$.content[0].eventType").value("PARTNER_SAVED"))
                .andExpect(jsonPath("$.content[0].actorId").value("ops.admin@gme.com"))
                .andExpect(jsonPath("$.content[0].recordedAt").exists())
                // Raw hashes MUST NOT appear in the BFF response shape
                .andExpect(jsonPath("$.content[0].prevHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].rowHash").doesNotExist());
    }

    @Test
    @DisplayName("GET /v1/admin/audit-trail?page=1 returns the second page")
    void list_page1_returnsSecondPage() throws Exception {
        Instant now = Instant.parse("2026-06-11T10:00:00Z");
        List<AuditEntryView> entries = List.of(
                new AuditEntryView(now, "ops.admin@gme.com", "PARTNER_SAVED", null, "{}", true),
                new AuditEntryView(now.minusSeconds(60), "ops.admin@gme.com", "PARTNER_SAVED", null, "{}", true),
                new AuditEntryView(now.minusSeconds(120), "ops.admin@gme.com", "PARTNER_SAVED", null, "{}", true));
        AuditTrailClient seeded = (aggregateType, aggregateId, page, size) ->
                new PageView<>(entries.subList(Math.min(page * size, entries.size()),
                        Math.min(page * size + size, entries.size())),
                        page, size, entries.size());

        MockMvc seededMvc = buildMvc(seeded);
        seededMvc.perform(get("/v1/admin/audit-trail")
                        .param("aggregateType", "partner")
                        .param("aggregateId", "ACE")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    @DisplayName("GET /v1/admin/audit does NOT conflict with GET /v1/admin/audit-trail")
    void existingAuditEndpoint_isNotBroken() throws Exception {
        // The old AuditController at /v1/admin/audit must continue to exist.
        // We cannot test it from this test class (different controller), but we can
        // assert the audit-trail controller is NOT mapped to /v1/admin/audit.
        mvc.perform(get("/v1/admin/audit")
                        .param("aggregateType", "partner")
                        .param("aggregateId", "GMEREMIT"))
                .andExpect(status().isNotFound()); // standaloneSetup only wires AuditTrailController
    }
}
