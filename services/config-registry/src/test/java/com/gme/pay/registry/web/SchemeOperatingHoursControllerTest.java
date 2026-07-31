package com.gme.pay.registry.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.SchemeAvailability;
import com.gme.pay.contracts.SchemeAvailabilityVerdict;
import com.gme.pay.contracts.SchemeOperatingHoursView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.scheme.PartnerSchemeService;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T3-6 — the SERVICE-facing read of the V024 schedule ({@code GET /v1/schemes/{id}/operating-hours}) and,
 * crucially, the verdict the seeded rows actually produce.
 *
 * <p>The second half of this class is the "is the fix real?" test: it evaluates the SHIPPED V024 seed
 * (loaded by Flyway into H2, not a hand-written fixture) through the same
 * {@link SchemeAvailability#evaluate} the payment path uses, so a future migration that narrows ZEROPAY's
 * window — or a schedule seeded in a way the evaluator cannot read — fails here rather than in production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemeOperatingHoursControllerTest.TestConfig.class, PartnerSchemeService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class SchemeOperatingHoursControllerTest {

    @Autowired
    private PartnerSchemeService schemeService;

    private MockMvc mvc;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        com.gme.pay.audit.AuditPublisher auditPublisher(RecordingAuditPublisher recording) {
            return recording;
        }
    }

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new SchemeOperatingHoursController(schemeService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    // ------------------------------------------------------- the read surface

    @Test
    @DisplayName("GET /v1/schemes/{id}/operating-hours — the flat, service-facing mount serves the 7 rows")
    void serviceFacingMount_servesTheWeeklySchedule() throws Exception {
        // Before T3-6 this projection existed ONLY under /v1/admin, i.e. behind the operator's OIDC
        // surface — which is why the payment path had no reachable consumer for it.
        mvc.perform(get("/v1/schemes/{schemeId}/operating-hours", "ZEROPAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$[0].weekday").value(0))
                .andExpect(jsonPath("$[0].openTimeLocal").value("00:00:00"))
                .andExpect(jsonPath("$[0].closeTimeLocal").value("23:59:59"))
                .andExpect(jsonPath("$[0].cutoffTimeLocal").value("16:30:00"))
                .andExpect(jsonPath("$[0].timezone").value("Asia/Seoul"));
    }

    @Test
    @DisplayName("unknown scheme 404s; rostered-but-unseeded returns [] (both mean UNVERIFIED to callers)")
    void unknownVsUnseeded() throws Exception {
        mvc.perform(get("/v1/schemes/{schemeId}/operating-hours", "ALIPAY"))
                .andExpect(status().isNotFound());

        for (String unseeded : List.of("QRIS", "KHQR", "NEPAL", "SENDMN")) {
            mvc.perform(get("/v1/schemes/{schemeId}/operating-hours", unseeded))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    // ------------------------------- the seed, through the real evaluator

    @Test
    @DisplayName("the SHIPPED ZEROPAY seed evaluates OPEN at every hour of the week")
    void zeropaySeed_is24x7_open_atEveryHour() {
        List<SchemeOperatingHoursView> rows = schemeService.operatingHours("ZEROPAY");

        // Walk a full week in 30-minute steps from a known Monday 00:00 KST.
        Instant cursor = Instant.parse("2026-07-26T15:00:00Z"); // 2026-07-27 00:00 KST (Monday)
        for (int step = 0; step < 7 * 48; step++) {
            SchemeAvailability a = SchemeAvailability.evaluate("ZEROPAY", rows, cursor);
            assertEquals(SchemeAvailabilityVerdict.OPEN, a.verdict(),
                    "ZEROPAY must never be CLOSED — it is a 24x7 rail: " + a.reason());
            cursor = cursor.plusSeconds(1800);
        }
    }

    @Test
    @DisplayName("ZEROPAY's 16:30 KST cutoff is reported as a cutoff, and never closes the rail")
    void zeropaySeed_cutoffIsNotAClose() {
        List<SchemeOperatingHoursView> rows = schemeService.operatingHours("ZEROPAY");

        SchemeAvailability beforeCutoff = SchemeAvailability.evaluate("ZEROPAY", rows,
                Instant.parse("2026-07-28T03:00:00Z"));   // 12:00 KST
        SchemeAvailability afterCutoff = SchemeAvailability.evaluate("ZEROPAY", rows,
                Instant.parse("2026-07-28T09:00:00Z"));   // 18:00 KST

        assertTrue(beforeCutoff.open());
        assertTrue(afterCutoff.open(), "past the settlement cutoff is STILL open");
        assertFalse(beforeCutoff.pastCutoff());
        assertTrue(afterCutoff.pastCutoff());
        assertEquals(LocalTime.of(16, 30), afterCutoff.cutoffTimeLocal());
        // The scheme cutoff coincides with the per-partner settlement default (V013, 16:30 Asia/Seoul);
        // T3-6 reads it, and deliberately changes nothing about settlement's own cutoff behaviour.
    }

    @Test
    @DisplayName("each seeded scheme is evaluated in ITS OWN timezone, not the server's")
    void everySeededScheme_isEvaluatedInItsOwnZone() {
        record Expected(String schemeId, String zone) { }
        List<Expected> expectations = List.of(
                new Expected("ZEROPAY", "Asia/Seoul"),
                new Expected("BAKONG", "Asia/Phnom_Penh"),
                new Expected("NAPAS_247", "Asia/Ho_Chi_Minh"),
                new Expected("PROMPT_PAY", "Asia/Bangkok"),
                new Expected("FAST_SG", "Asia/Singapore"));

        // 2026-07-27T16:30Z: already TUESDAY in Seoul (01:30) but still MONDAY in Bangkok (23:30) —
        // so the two schemes are decided on DIFFERENT weekday rows from the same instant.
        Instant at = Instant.parse("2026-07-27T16:30:00Z");
        for (Expected expected : expectations) {
            SchemeAvailability a = SchemeAvailability.evaluate(expected.schemeId(),
                    schemeService.operatingHours(expected.schemeId()), at);
            assertEquals(SchemeAvailabilityVerdict.OPEN, a.verdict(), expected.schemeId());
            assertEquals(expected.zone(), a.timezone(), expected.schemeId());
        }

        SchemeAvailability seoul = SchemeAvailability.evaluate("ZEROPAY",
                schemeService.operatingHours("ZEROPAY"), at);
        SchemeAvailability bangkok = SchemeAvailability.evaluate("PROMPT_PAY",
                schemeService.operatingHours("PROMPT_PAY"), at);
        assertEquals(1, seoul.weekday(), "Tuesday in Seoul");
        assertEquals(0, bangkok.weekday(), "still Monday in Bangkok");
    }

    @Test
    @DisplayName("an unseeded scheme is UNVERIFIED, never OPEN")
    void unseededScheme_isUnverified() {
        for (String unseeded : List.of("QRIS", "KHQR", "NEPAL", "SENDMN")) {
            SchemeAvailability a = SchemeAvailability.evaluate(unseeded,
                    schemeService.operatingHours(unseeded), Instant.parse("2026-07-28T03:00:00Z"));
            assertEquals(SchemeAvailabilityVerdict.UNVERIFIED, a.verdict(), unseeded);
            assertFalse(a.open(), unseeded + " must not be reported as open");
            assertFalse(a.closed(), unseeded + " must not be reported as closed either");
        }
    }
}
