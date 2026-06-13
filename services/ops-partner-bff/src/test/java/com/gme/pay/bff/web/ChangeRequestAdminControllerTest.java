package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.contracts.ChangeRequestView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Slice test for {@link ChangeRequestAdminController} in ops-partner-bff.
 *
 * <p>Uses a hand-rolled stub of {@link ConfigRegistryClient} so the BFF controller
 * logic is exercised in isolation — no Spring context, no REST calls to
 * config-registry. The stub mimics a minimal in-memory change-request store so
 * the full approve / reject / list round-trip can be verified against the JSON
 * wire shape.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>List with state filter returns only matching rows.</li>
 *   <li>Approve happy path returns state=APPLIED.</li>
 *   <li>Self-approve 409 is propagated from the client stub.</li>
 *   <li>Reject with reason returns state=REJECTED.</li>
 * </ol>
 */
class ChangeRequestAdminControllerTest {

    private StubChangeRequestClient client;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        client = new StubChangeRequestClient();
        ChangeRequestAdminController controller = new ChangeRequestAdminController(client);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(om);
        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("GET /v1/admin/change-requests?state=PROPOSED returns only PROPOSED rows")
    void list_filterByState_returnsPROPOSEDOnly() throws Exception {
        client.seed(new ChangeRequestView(1L, "partner", "ALPHA", "PROPOSED",
                "alice", Instant.EPOCH, null, null, null, "{}", null));
        client.seed(new ChangeRequestView(2L, "partner", "BETA", "APPLIED",
                "alice", Instant.EPOCH, "bob", Instant.EPOCH, null, "{}", null));

        mvc.perform(get("/v1/admin/change-requests")
                        .param("state", "PROPOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.aggregateId == 'ALPHA' && @.state == 'PROPOSED')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.state == 'APPLIED')]").isEmpty())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("POST .../approve happy path returns state=APPLIED")
    void approve_happyPath_returnsApplied() throws Exception {
        client.seed(new ChangeRequestView(10L, "partner", "GMEREMIT", "PROPOSED",
                "alice", Instant.EPOCH, null, null, null, "{}", null));

        mvc.perform(post("/v1/admin/change-requests/{id}/approve", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPLIED"))
                .andExpect(jsonPath("$.approvedBy").value("bob"));
    }

    @Test
    @DisplayName("POST .../approve with same user as proposer propagates 409")
    void approve_selfApprove_returns409() throws Exception {
        client.seed(new ChangeRequestView(20L, "partner", "SELF", "PROPOSED",
                "alice", Instant.EPOCH, null, null, null, "{}", null));

        mvc.perform(post("/v1/admin/change-requests/{id}/approve", 20L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"alice\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST .../reject with reason returns state=REJECTED")
    void reject_withReason_returnsRejected() throws Exception {
        client.seed(new ChangeRequestView(30L, "partner", "REJECT_ME", "PROPOSED",
                "alice", Instant.EPOCH, null, null, null, "{}", null));

        mvc.perform(post("/v1/admin/change-requests/{id}/reject", 30L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectedBy\":\"bob\",\"reason\":\"policy mismatch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.rejectedReason").value("policy mismatch"))
                .andExpect(jsonPath("$.approvedBy").value("bob"));
    }

    // ---- Minimal stub of ConfigRegistryClient for change-request paths -----

    /**
     * Minimal stub that implements only the change-request methods needed for
     * this controller test. All other ConfigRegistryClient methods throw
     * UnsupportedOperationException (the defaults). We extend StubConfigRegistryClient
     * to get the full interface satisfied without reimplementing every method.
     */
    static class StubChangeRequestClient
            extends com.gme.pay.bff.client.stub.StubConfigRegistryClient {

        private final java.util.Map<Long, ChangeRequestView> store =
                new java.util.LinkedHashMap<>();

        void seed(ChangeRequestView view) {
            store.put(view.id(), view);
        }

        @Override
        public ChangeRequestPage listChangeRequests(String state, int page, int size) {
            List<ChangeRequestView> filtered = store.values().stream()
                    .filter(v -> state == null || state.isBlank() || state.equals(v.state()))
                    .toList();
            // crude pagination
            int from = Math.min(page * size, filtered.size());
            int to   = Math.min(from + size, filtered.size());
            List<ChangeRequestView> slice = filtered.subList(from, to);
            return new ChangeRequestPage(slice, page, size, filtered.size());
        }

        @Override
        public ChangeRequestView getChangeRequest(Long id) {
            return store.get(id);
        }

        @Override
        public ChangeRequestView approveChangeRequest(Long id, String approvedBy) {
            ChangeRequestView cr = store.get(id);
            if (cr == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "change-request " + id + " not found");
            }
            // Self-approval stub: same user → 409
            if (approvedBy != null && approvedBy.equals(cr.proposedBy())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Self-approval not permitted");
            }
            ChangeRequestView updated = new ChangeRequestView(
                    cr.id(), cr.aggregateType(), cr.aggregateId(),
                    "APPLIED",
                    cr.proposedBy(), cr.proposedAt(),
                    approvedBy, Instant.now(),
                    null, cr.payloadJson(), cr.appliesTo());
            store.put(id, updated);
            return updated;
        }

        @Override
        public ChangeRequestView rejectChangeRequest(Long id, String rejectedBy, String reason) {
            ChangeRequestView cr = store.get(id);
            if (cr == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "change-request " + id + " not found");
            }
            ChangeRequestView updated = new ChangeRequestView(
                    cr.id(), cr.aggregateType(), cr.aggregateId(),
                    "REJECTED",
                    cr.proposedBy(), cr.proposedAt(),
                    rejectedBy, Instant.now(),
                    reason, cr.payloadJson(), cr.appliesTo());
            store.put(id, updated);
            return updated;
        }
    }
}
