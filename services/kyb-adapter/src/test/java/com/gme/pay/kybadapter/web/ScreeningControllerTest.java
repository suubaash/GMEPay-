package com.gme.pay.kybadapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.RecordingEventPublisher;
import com.gme.pay.kybadapter.event.KybScreeningEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import com.gme.pay.kybadapter.testsupport.TestInternalAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc test of {@code POST /v1/kyb/screen} (+ {@code GET /v1/kyb/health})
 * through the default wiring: {@code gmepay.kyb.provider} unset →
 * {@code StubKybAdapter} active. The {@link RecordingEventPublisher} replaces
 * the log fallback so the {@code gmepay.kyb.screening} fan-out is observable
 * (no Kafka broker on this box — {@code spring.kafka.bootstrap-servers} is
 * unset, so the lib-events-kafka auto-config backs off exactly as in local dev).
 */
@SpringBootTest(properties = TestInternalAuth.SECRET_PROPERTY)
@AutoConfigureMockMvc
class ScreeningControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RecordingEventPublisher events;

    /** @Primary so this wins over KybProviderConfig's LogEventPublisher fallback. */
    @TestConfiguration
    static class TestEvents {
        @Bean
        @Primary
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    @BeforeEach
    void resetEvents() {
        events.clear();
    }

    @Test
    @DisplayName("clean subject is NOT_SCREENED_NO_PROVIDER with non-authoritative provenance")
    void screen_cleanSubject_isNotScreened() throws Exception {
        String body = """
                {
                  "partnerCode": "P_CLEAN",
                  "legalNameLocal": "지엠이송금",
                  "legalNameRomanized": "GME Remit Co Ltd",
                  "countryOfIncorporation": "KR",
                  "taxId": "123-45-67890",
                  "uboList": [
                    {"name": "Hong Gil Dong", "ownershipPct": 60.0, "isPep": false, "country": "KR"}
                  ]
                }
                """;
        mvc.perform(TestInternalAuth.internal(post("/v1/kyb/screen").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isOk())
                // T1-4: the wire body cannot say CLEAR — no provider screened anything.
                .andExpect(jsonPath("$.status").value("NOT_SCREENED_NO_PROVIDER"))
                .andExpect(jsonPath("$.hits.length()").value(0))
                .andExpect(jsonPath("$.providerRef").value(org.hamcrest.Matchers.startsWith("stub-")))
                .andExpect(jsonPath("$.screenedAt").isNotEmpty())
                // …and the response carries WHO produced it and why it is not a check.
                .andExpect(jsonPath("$.provenance.providerId").value("stub"))
                .andExpect(jsonPath("$.provenance.authoritative").value(false))
                .andExpect(jsonPath("$.provenance.caveat")
                        .value(org.hamcrest.Matchers.containsString("NOT A SANCTIONS SCREENING")));
    }

    @Test
    @DisplayName("the KYB surface is internal-only: no X-Gme-Internal token -> 401, nothing published")
    void screen_withoutInternalToken_isRejected() throws Exception {
        mvc.perform(post("/v1/kyb/screen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerCode\":\"P_ANON\",\"legalNameRomanized\":\"Anon Corp\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(events.published()).isEmpty();
    }

    @Test
    @DisplayName("SANCTIONED name returns HIT and publishes one kyb.screening event")
    void screen_sanctionedSubject_publishesEvent() throws Exception {
        String body = """
                {
                  "partnerCode": "P_BAD",
                  "legalNameLocal": "나쁜회사",
                  "legalNameRomanized": "Sanctioned Holdings PLC",
                  "countryOfIncorporation": "KP",
                  "taxId": "999",
                  "uboList": []
                }
                """;
        mvc.perform(TestInternalAuth.internal(post("/v1/kyb/screen").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIT"))
                .andExpect(jsonPath("$.hits.length()").value(1))
                .andExpect(jsonPath("$.hits[0].listName").value("STUB_WATCHLIST"))
                .andExpect(jsonPath("$.hits[0].matchedName").value("Sanctioned Holdings PLC"));

        List<DomainEvent> published = events.published();
        assertThat(published).hasSize(1);
        assertThat(published.get(0)).isInstanceOfSatisfying(KybScreeningEvent.class, e -> {
            assertThat(e.eventType()).isEqualTo("kyb.screening");
            assertThat(e.aggregateId()).isEqualTo("P_BAD");
            assertThat(e.status()).isEqualTo("HIT");
            assertThat(e.providerRef()).startsWith("stub-");
            // T1-4: the event carries provenance, so no consumer can file a stub
            // run as a completed screening.
            assertThat(e.providerId()).isEqualTo("stub");
            assertThat(e.authoritative()).isFalse();
            assertThat(e.caveat()).contains("NOT A SANCTIONS SCREENING");
        });
    }

    @Test
    @DisplayName("REVIEW name returns NEEDS_REVIEW")
    void screen_reviewSubject() throws Exception {
        String body = """
                {
                  "partnerCode": "P_REVIEW",
                  "legalNameRomanized": "Review Trading LLC",
                  "uboList": []
                }
                """;
        mvc.perform(TestInternalAuth.internal(post("/v1/kyb/screen").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.hits[0].listName").value("STUB_FUZZY"));
    }

    @Test
    @DisplayName("missing partnerCode is rejected with 400 and publishes nothing")
    void screen_missingPartnerCode_400() throws Exception {
        mvc.perform(TestInternalAuth.internal(post("/v1/kyb/screen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalNameRomanized\":\"No Code Corp\"}")))
                .andExpect(status().isBadRequest());
        assertThat(events.published()).isEmpty();
    }

    @Test
    @DisplayName("GET /v1/kyb/health reports UP, the active provider, and that it is NOT authoritative")
    void health() throws Exception {
        // Anonymous on purpose (a container probe must reach it) and it must state
        // the honest posture of the environment: this one screens nothing.
        mvc.perform(get("/v1/kyb/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.provider").value("StubKybAdapter"))
                .andExpect(jsonPath("$.providerId").value("stub"))
                .andExpect(jsonPath("$.authoritative").value("false"))
                .andExpect(jsonPath("$.caveat")
                        .value(org.hamcrest.Matchers.containsString("NOT A SANCTIONS SCREENING")));
    }
}
