package com.gme.pay.registry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Controller slice test for {@link ChangeRequestController} per ADR-008.
 *
 * <p>Runs as a {@code @DataJpaTest} against H2 in PostgreSQL mode with the
 * trimmed migration chain used by {@link
 * com.gme.pay.registry.changerequest.ChangeRequestServiceTest} (partners +
 * change_request only). The controller is mounted on a standalone MockMvc so no
 * full Spring Boot web context is needed, keeping the test fast.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>{@link #list_filterByState_returnsPROPOSEDOnly} — {@code GET ?state=PROPOSED}
 *       returns only PROPOSED rows and nothing else.</li>
 *   <li>{@link #approveHappyPath_stateIsAPPLIED} — {@code POST .../approve} advances
 *       a PROPOSED row through APPROVED → APPLIED in a single request; the response
 *       carries {@code state=APPLIED} and the {@code approvedBy} principal.</li>
 *   <li>{@link #selfApprove_returns409} — a checker whose user-id matches the
 *       proposer is refused with HTTP 409 (DB CHECK
 *       {@code ck_change_request_four_eyes} surfaced cleanly).</li>
 *   <li>{@link #rejectWithReason_stateIsREJECTED} — {@code POST .../reject} with a
 *       non-blank reason terminates the row in REJECTED and the reason is persisted.</li>
 * </ol>
 *
 * <p>A {@link RecordingApplier} stub is registered so {@code apply()} does not
 * need the full partner aggregate stack.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:crctrl;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.flyway.locations=classpath:db/migration-changerequest"
})
@Import({
        ChangeRequestService.class,
        ChangeRequestStateMachineConfig.class,
        ChangeRequestControllerTest.TestAppliers.class
})
class ChangeRequestControllerTest {

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

    // ---- Test cases --------------------------------------------------------

    @Test
    @DisplayName("GET /v1/change-requests?state=PROPOSED returns only PROPOSED rows")
    void list_filterByState_returnsPROPOSEDOnly() throws Exception {
        // Create two change requests via the service — both land in PROPOSED.
        service.propose("partner", "ALPHA", "alice", "{\"f\":1}", new String[]{"f"});
        ChangeRequest r2 = service.propose("partner", "BETA",  "alice", "{\"f\":2}", new String[]{"f"});

        // Approve+apply r2 so its state moves to APPLIED (should not appear in list).
        service.approve(r2.id(), "bob");
        service.apply(r2.id());

        mvc.perform(get("/v1/change-requests")
                        .param("state", "PROPOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                // r2 is now APPLIED — must not appear
                .andExpect(jsonPath("$.content[?(@.aggregateId == 'BETA' && @.state == 'PROPOSED')]")
                        .isEmpty())
                // ALPHA is still PROPOSED
                .andExpect(jsonPath("$.content[?(@.aggregateId == 'ALPHA' && @.state == 'PROPOSED')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("POST .../approve on PROPOSED row results in state=APPLIED")
    void approveHappyPath_stateIsAPPLIED() throws Exception {
        ChangeRequest proposed = service.propose(
                "partner", "GMEREMIT", "alice",
                "{\"settlementRoundingMode\":\"FLOOR\"}", new String[]{"settlementRoundingMode"});

        mvc.perform(post("/v1/change-requests/{id}/approve", proposed.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPLIED"))
                .andExpect(jsonPath("$.approvedBy").value("bob"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty())
                .andExpect(jsonPath("$.id").value(proposed.id()));
    }

    @Test
    @DisplayName("POST .../approve by the same user who proposed returns 409")
    void selfApprove_returns409() throws Exception {
        ChangeRequest proposed = service.propose(
                "partner", "SELF_APPROVER", "alice",
                "{\"settlementRoundingMode\":\"FLOOR\"}", new String[]{"settlementRoundingMode"});

        // alice cannot approve her own change (ck_change_request_four_eyes CHECK)
        mvc.perform(post("/v1/change-requests/{id}/approve", proposed.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"alice\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST .../reject with reason sets state=REJECTED and persists the reason")
    void rejectWithReason_stateIsREJECTED() throws Exception {
        ChangeRequest proposed = service.propose(
                "partner", "REJECT_TARGET", "alice",
                "{\"settlementRoundingMode\":\"FLOOR\"}", new String[]{"settlementRoundingMode"});

        mvc.perform(post("/v1/change-requests/{id}/reject", proposed.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectedBy\":\"bob\",\"reason\":\"policy mismatch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.rejectedReason").value("policy mismatch"))
                .andExpect(jsonPath("$.approvedBy").value("bob"));
    }

    // ---- Stub applier (same pattern as ChangeRequestServiceTest) -----------

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
        RecordingApplier recordingApplier() { return new RecordingApplier(); }
    }
}
