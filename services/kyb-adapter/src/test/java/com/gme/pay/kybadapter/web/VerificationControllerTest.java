package com.gme.pay.kybadapter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.events.RecordingEventPublisher;
import com.gme.pay.kybadapter.persistence.KybScreeningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc tests of {@code POST /v1/kyb/verify} and {@code GET /v1/kyb/result/{ref}}
 * through the default H2-backed wiring (StubKybAdapter + stub business-registration
 * verifier). The RecordingEventPublisher replaces the log fallback.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VerificationControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KybScreeningRepository repository;

    @TestConfiguration
    static class TestEvents {
        @Bean
        @Primary
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("verify a clean full pack → PASS and a persisted run")
    void verifyPass() throws Exception {
        String body = """
                {
                  "subject": {
                    "partnerCode": "P_VERIFY",
                    "legalNameRomanized": "GME Remit Co Ltd",
                    "countryOfIncorporation": "KR",
                    "taxId": "123-45-67890",
                    "uboList": []
                  },
                  "suppliedDocuments": ["BUSINESS_REGISTRATION", "AOA", "UBO_DECLARATION"],
                  "force": false
                }
                """;
        MvcResult res = mvc.perform(post("/v1/kyb/verify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"))
                .andExpect(jsonPath("$.bizRegStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.providerRef").value(org.hamcrest.Matchers.startsWith("stub-")))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andReturn();

        String ref = com.jayway.jsonpath.JsonPath.read(
                res.getResponse().getContentAsString(), "$.providerRef");

        // The persisted run is retrievable by its providerRef.
        mvc.perform(get("/v1/kyb/result/" + ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"))
                .andExpect(jsonPath("$.partnerCode").value("P_VERIFY"))
                .andExpect(jsonPath("$.idempotentReplay").value(true));
    }

    @Test
    @DisplayName("verify with missing partnerCode is 400")
    void verifyMissingPartnerCode() throws Exception {
        String body = """
                {"subject": {"legalNameRomanized": "No Code"}, "suppliedDocuments": []}
                """;
        mvc.perform(post("/v1/kyb/verify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET result for an unknown ref is 404")
    void resultNotFound() throws Exception {
        mvc.perform(get("/v1/kyb/result/stub-does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
