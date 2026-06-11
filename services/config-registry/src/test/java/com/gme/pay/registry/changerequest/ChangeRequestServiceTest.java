package com.gme.pay.registry.changerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.changerequest.ChangeRequest;
import com.gme.pay.changerequest.ChangeRequestState;
import com.gme.pay.changerequest.ChangeRequestStateMachineConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice integration test for {@link ChangeRequestService} per ADR-008.
 *
 * <p>Runs against the in-memory H2 (PostgreSQL compatibility mode) with a trimmed
 * Flyway migration chain (see {@code src/test/resources/db/migration-changerequest/})
 * that ships only the {@code partners} + {@code change_request} schema this slice
 * actually needs. The production V001..V006 chain is exercised end-to-end against
 * a real PostgreSQL 16 in {@code PartnerPostgresMigrationIT} (docker-tagged,
 * CI-only).
 *
 * <h2>Stub applier</h2>
 *
 * <p>This test uses a {@link RecordingApplier} instead of the
 * {@link PartnerChangeRequestApplier} so the test focuses on the FSM /
 * change_request lifecycle without dragging in the full partner aggregate
 * + audit_log + bitemporal storage. The {@code PartnerChangeRequestApplier}
 * itself is unit-tested in its own slice; this test verifies that
 * {@link ChangeRequestService#apply} is the only path that runs the applier and
 * that it does so exactly once per APPLIED transition.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Happy path: {@code propose → approve → apply} invokes the applier
 *       exactly once.</li>
 *   <li>Reject path: {@code propose → reject(reason)} terminates with the
 *       reason persisted and the applier never invoked.</li>
 *   <li>Self-approval DB rejection: an INSERT with
 *       {@code proposed_by = approved_by} (and not the system carve-out) is
 *       refused by the {@code ck_change_request_four_eyes} CHECK constraint.</li>
 *   <li>System=system carve-out: the same INSERT with both columns equal to
 *       {@code 'system'} is accepted.</li>
 *   <li>Illegal-transition guard: the FSM refuses {@code DRAFT → APPLIED}
 *       directly — the maker must walk through PROPOSED and APPROVED first.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        // Use an isolated in-memory H2 instance for this test so the trimmed
        // Flyway location below does not collide with the production V001..V006
        // migration history (different checksums for the same V001 file path).
        "spring.datasource.url=jdbc:h2:mem:changereq;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.flyway.locations=classpath:db/migration-changerequest"
})
@Import({
        ChangeRequestService.class,
        ChangeRequestStateMachineConfig.class,
        ChangeRequestServiceTest.TestAppliers.class
})
class ChangeRequestServiceTest {

    @Autowired
    private ChangeRequestService service;

    @Autowired
    private RecordingApplier applier;

    @PersistenceContext
    private EntityManager em;

    @Test
    void happyPathProposeApproveApplyMutatesAggregate() {
        // Maker proposes a change with a payload + field set scope.
        ChangeRequest proposed = service.propose(
                "partner",
                "GMEREMIT",
                "alice",
                "{\"settlementRoundingMode\":\"FLOOR\"}",
                new String[]{"settlementRoundingMode"});
        assertThat(proposed.id()).isNotNull();
        assertThat(proposed.state())
                .as("propose() advances DRAFT → PROPOSED so the row is visible to the checker")
                .isEqualTo(ChangeRequestState.PROPOSED);

        // The applier MUST NOT fire yet — only APPLIED mutates the aggregate.
        assertThat(applier.applied).isEmpty();

        // Checker (different user) approves.
        ChangeRequest approved = service.approve(proposed.id(), "bob");
        assertThat(approved.state()).isEqualTo(ChangeRequestState.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("bob");
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(applier.applied)
                .as("approving must not yet invoke the applier")
                .isEmpty();

        // Apply — this is the one path that mutates the aggregate.
        ChangeRequest applied = service.apply(approved.id());
        assertThat(applied.state()).isEqualTo(ChangeRequestState.APPLIED);
        assertThat(applier.applied)
                .as("apply() must invoke the applier exactly once")
                .hasSize(1);
        assertThat(applier.applied.get(0).id()).isEqualTo(approved.id());
        assertThat(applier.applied.get(0).aggregateId()).isEqualTo("GMEREMIT");
        assertThat(applier.applied.get(0).payloadJsonb())
                .contains("settlementRoundingMode");
    }

    @Test
    void rejectPathTerminatesWithReasonAndNoMutation() {
        ChangeRequest proposed = service.propose(
                "partner",
                "GMEREMIT",
                "alice",
                "{\"settlementRoundingMode\":\"FLOOR\"}",
                new String[]{"settlementRoundingMode"});

        ChangeRequest rejected = service.reject(proposed.id(), "bob", "policy mismatch");

        assertThat(rejected.state()).isEqualTo(ChangeRequestState.REJECTED);
        assertThat(rejected.rejectedReason()).isEqualTo("policy mismatch");
        assertThat(rejected.approvedBy())
                .as("the rejecting operator is recorded in approved_by so the audit trail "
                        + "shows who closed the request")
                .isEqualTo("bob");

        // Applier never fired.
        assertThat(applier.applied).isEmpty();

        // Re-applying a REJECTED change is refused with 409.
        assertThatThrownBy(() -> service.apply(rejected.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
    }

    @Test
    void selfApprovalIsRejectedByCheckConstraint() {
        ChangeRequest proposed = service.propose(
                "partner",
                "GMEREMIT",
                "alice",
                "{\"settlementRoundingMode\":\"FLOOR\"}",
                new String[]{"settlementRoundingMode"});

        // Same operator who proposed tries to approve. The structural half of the
        // 4-eyes invariant (ck_change_request_four_eyes) refuses approved_by =
        // proposed_by outside the system carve-out. The service flushes inside
        // approve() so the violation surfaces immediately as a
        // DataIntegrityViolationException.
        assertThatThrownBy(() -> service.approve(proposed.id(), "alice"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void systemSystemCarveOutIsAllowed() {
        // Direct INSERT with proposed_by = approved_by = 'system' must succeed,
        // exercising the carve-out branch of ck_change_request_four_eyes. We
        // bypass the service because the service drives the FSM DRAFT → PROPOSED
        // on propose() and we want to verify the CHECK itself, not the
        // application path.
        Object id = em.createNativeQuery("select nextval('change_request_id_seq')")
                .getSingleResult();
        Long crId = ((Number) id).longValue();

        em.createNativeQuery(
                "insert into change_request("
                        + "id, aggregate_type, aggregate_id, state, "
                        + "proposed_by, proposed_at, "
                        + "approved_by, approved_at, "
                        + "payload_jsonb, applies_to_field_set) "
                        + "values (?1, 'partner', 'GMEREMIT', 'APPROVED', "
                        + "'system', current_timestamp, "
                        + "'system', current_timestamp, "
                        + "'{\"settlementRoundingMode\":\"FLOOR\"}', 'settlementRoundingMode')")
                .setParameter(1, crId)
                .executeUpdate();
        em.flush();

        ChangeRequest loaded = service.get(crId);
        assertThat(loaded.proposedBy()).isEqualTo("system");
        assertThat(loaded.approvedBy()).isEqualTo("system");
        assertThat(loaded.state()).isEqualTo(ChangeRequestState.APPROVED);
    }

    @Test
    void fsmRefusesDraftToAppliedDirectly() {
        // Drop a row straight into DRAFT — bypass the service's propose() which
        // auto-submits DRAFT → PROPOSED.
        Object id = em.createNativeQuery("select nextval('change_request_id_seq')")
                .getSingleResult();
        Long crId = ((Number) id).longValue();
        em.createNativeQuery(
                "insert into change_request("
                        + "id, aggregate_type, aggregate_id, state, "
                        + "proposed_by, proposed_at, "
                        + "payload_jsonb, applies_to_field_set) "
                        + "values (?1, 'partner', 'GMEREMIT', 'DRAFT', "
                        + "'alice', current_timestamp, "
                        + "'{\"settlementRoundingMode\":\"FLOOR\"}', 'settlementRoundingMode')")
                .setParameter(1, crId)
                .executeUpdate();
        em.flush();

        // The FSM has no DRAFT --APPLY--> APPLIED edge; the service must reject
        // the transition with 409 and leave the row + aggregate untouched.
        assertThatThrownBy(() -> service.apply(crId))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

        em.clear();
        ChangeRequest reread = service.get(crId);
        assertThat(reread.state())
                .as("DRAFT row must stay in DRAFT after a refused APPLY")
                .isEqualTo(ChangeRequestState.DRAFT);
        assertThat(applier.applied)
                .as("refused APPLY must not invoke the applier")
                .isEmpty();
    }

    /**
     * Stub {@link ChangeRequestApplier} that records every apply call. Lets the
     * test verify that {@link ChangeRequestService#apply} is the only path that
     * runs the applier, without coupling the test to the full partner
     * aggregate / audit_log / bitemporal stack.
     */
    static class RecordingApplier implements ChangeRequestApplier {
        final List<ChangeRequest> applied = new ArrayList<>();

        @Override
        public String aggregateType() {
            return "partner";
        }

        @Override
        public void apply(ChangeRequest request) {
            applied.add(request);
        }
    }

    @TestConfiguration
    static class TestAppliers {
        @Bean
        RecordingApplier recordingApplier() {
            return new RecordingApplier();
        }
    }
}
