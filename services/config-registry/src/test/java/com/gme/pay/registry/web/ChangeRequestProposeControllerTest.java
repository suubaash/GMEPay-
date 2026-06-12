package com.gme.pay.registry.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.changerequest.ChangeRequest;
import com.gme.pay.changerequest.ChangeRequestState;
import com.gme.pay.changerequest.ChangeRequestStateMachineConfig;
import com.gme.pay.registry.changerequest.ChangeRequestApplier;
import com.gme.pay.registry.changerequest.ChangeRequestRepository;
import com.gme.pay.registry.changerequest.ChangeRequestService;
import java.util.ArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice 5 (5B.1) controller slice test for the NEW maker endpoint
 * {@code POST /v1/change-requests} on {@link ChangeRequestController} — the hook
 * prefunding's breach auto-suspend calls with {@code proposedBy='system'} (ADR-008).
 * Bootstrapping mirrors {@link ChangeRequestControllerTest}: {@code @DataJpaTest}
 * on H2 (PostgreSQL mode) with the trimmed migration chain, controller mounted on
 * a standalone MockMvc.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>POST with the system-suspension shape returns 201, state=PROPOSED,
 *       proposedBy=system and the payload preserved verbatim;</li>
 *   <li>the row is persisted (visible through the repository);</li>
 *   <li>missing required fields return 400.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:crpropose;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.flyway.locations=classpath:db/migration-changerequest"
})
@Import({
        ChangeRequestService.class,
        ChangeRequestStateMachineConfig.class,
        ChangeRequestProposeControllerTest.TestAppliers.class
})
class ChangeRequestProposeControllerTest {

    @Autowired
    private ChangeRequestService service;

    @Autowired
    private ChangeRequestRepository repository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ChangeRequestController controller =
                new ChangeRequestController(service, repository);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(om);
        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    private static final String SYSTEM_SUSPENSION_BODY = """
            {
              "aggregateType": "partner",
              "aggregateId": "BREACH_P1",
              "proposedBy": "system",
              "payloadJsonb": "{\\"status\\":\\"SUSPENDED\\"}",
              "appliesToFieldSet": ["status"]
            }
            """;

    @Test
    @DisplayName("POST /v1/change-requests creates the row in state=PROPOSED (201)")
    void create_returnsProposedView() throws Exception {
        mvc.perform(post("/v1/change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SYSTEM_SUSPENSION_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.state").value("PROPOSED"))
                .andExpect(jsonPath("$.aggregateType").value("partner"))
                .andExpect(jsonPath("$.aggregateId").value("BREACH_P1"))
                .andExpect(jsonPath("$.proposedBy").value("system"))
                // ChangeRequestView names the field payloadJson on the wire.
                .andExpect(jsonPath("$.payloadJson").value("{\"status\":\"SUSPENDED\"}"));

        assertEquals(1, repository.count(), "one change_request row persisted");
        assertEquals(ChangeRequestState.PROPOSED,
                repository.findAll().get(0).getState());
    }

    @Test
    @DisplayName("missing required fields return 400")
    void create_missingFields_400() throws Exception {
        mvc.perform(post("/v1/change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aggregateId\":\"X\",\"proposedBy\":\"system\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aggregateType\":\"partner\",\"proposedBy\":\"system\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/change-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aggregateType\":\"partner\",\"aggregateId\":\"X\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, repository.count(), "nothing persisted on validation failure");
    }

    // ---- Stub applier (same pattern as ChangeRequestControllerTest) --------

    static class RecordingApplier implements ChangeRequestApplier {
        final List<ChangeRequest> applied = new ArrayList<>();

        @Override
        public String aggregateType() { return "partner"; }

        @Override
        public void apply(ChangeRequest request) { applied.add(request); }
    }

    @TestConfiguration
    static class TestAppliers {
        @Bean
        RecordingApplier proposeTestRecordingApplier() { return new RecordingApplier(); }
    }
}
