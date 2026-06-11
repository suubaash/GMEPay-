package com.gme.pay.registry.changerequest;

import com.gme.pay.changerequest.ChangeRequest;
import com.gme.pay.changerequest.ChangeRequestEvent;
import com.gme.pay.changerequest.ChangeRequestState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import com.gme.pay.changerequest.ChangeRequestStateMachineConfig;

/**
 * Gatekeeper for every regulated mutation in config-registry, per ADR-008.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link #propose} → {@link #approve} → {@link #apply}; or {@link #reject}
 * from any non-terminal state. Each method drives the Spring State Machine
 * (lib-change-request {@link ChangeRequestStateMachineConfig}) for the requested
 * transition, persists the new state, and — only on APPLY — invokes the
 * matching {@link ChangeRequestApplier} to mutate the underlying aggregate.
 *
 * <h2>4-eyes invariant — defence in depth</h2>
 *
 * <p>Two complementary layers protect the maker-checker rule:
 * <ol>
 *   <li>Procedural: the FSM has no DRAFT→APPLIED edge. A maker cannot skip
 *       checker review even by issuing the APPLY event directly.</li>
 *   <li>Structural: the {@code change_request} table's CHECK constraint forbids
 *       {@code proposed_by = approved_by}, except for the
 *       {@code (system, system)} carve-out. Even if a service-layer bug let
 *       the maker also approve, the DB rejects the write with a
 *       {@code ConstraintViolationException}.</li>
 * </ol>
 *
 * <h2>Transactional boundary</h2>
 *
 * <p>{@code @Transactional} on every mutating method so the state transition,
 * the entity UPDATE, and (for APPLY) the aggregate mutation share one
 * transaction with one audit_log write. Exceptions roll all of them back.
 *
 * <h2>Applier routing</h2>
 *
 * <p>Appliers are picked up via constructor injection of every
 * {@link ChangeRequestApplier} bean in the context and indexed by
 * {@link ChangeRequestApplier#aggregateType()}. Slice 1 wires the partner
 * applier only; later slices register more without touching this service.
 */
@Service
public class ChangeRequestService {

    private final ChangeRequestRepository repository;
    private final StateMachineFactory<ChangeRequestState, ChangeRequestEvent> stateMachineFactory;
    private final Map<String, ChangeRequestApplier> appliersByType;

    @PersistenceContext
    private EntityManager entityManager;

    public ChangeRequestService(
            ChangeRequestRepository repository,
            StateMachineFactory<ChangeRequestState, ChangeRequestEvent> stateMachineFactory,
            List<ChangeRequestApplier> appliers) {
        this.repository = repository;
        this.stateMachineFactory = stateMachineFactory;
        this.appliersByType = new HashMap<>();
        for (ChangeRequestApplier a : appliers) {
            ChangeRequestApplier prior = this.appliersByType.put(a.aggregateType(), a);
            if (prior != null) {
                throw new IllegalStateException(
                        "Duplicate ChangeRequestApplier for aggregateType=" + a.aggregateType());
            }
        }
    }

    /**
     * Persist a new DRAFT change_request and immediately submit it for review
     * (DRAFT → PROPOSED). This is the maker path; Slice 1 collapses DRAFT and
     * PROPOSED into a single operator action because the wizard's
     * server-side persistence (ADR-012) holds the DRAFT shape on the partner
     * row itself. Later slices may split this into a separate "save draft" call.
     */
    @Transactional
    public ChangeRequest propose(String aggregateType, String aggregateId,
                                 String proposedBy, String payloadJsonb,
                                 String[] appliesToFieldSet) {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(proposedBy, "proposedBy");

        ChangeRequestEntity entity = new ChangeRequestEntity();
        entity.setId(nextSurrogateId());
        entity.setAggregateType(aggregateType);
        entity.setAggregateId(aggregateId);
        entity.setState(ChangeRequestState.DRAFT);
        entity.setProposedBy(proposedBy);
        entity.setProposedAt(Instant.now());
        entity.setPayloadJsonb(payloadJsonb);
        entity.setAppliesToFieldSet(appliesToFieldSet);

        // Drive the FSM DRAFT → PROPOSED so the persisted state already reflects
        // "awaiting checker" — Slice 1 has no separate "save draft" call yet.
        ChangeRequestState next = driveTransition(entity.getState(), ChangeRequestEvent.SUBMIT);
        entity.setState(next);

        ChangeRequestEntity saved = repository.saveAndFlush(entity);
        return saved.toDomain();
    }

    /**
     * Checker approves a PROPOSED change_request (PROPOSED → APPROVED). The
     * 4-eyes invariant is enforced both by the DB CHECK constraint and by the
     * application layer: we set {@code approved_by} in the same UPDATE that
     * flips state, so the constraint fires on flush.
     *
     * @throws ResponseStatusException 404 if the row is missing; 409 if the row
     *         is not in PROPOSED state (the FSM refuses the transition)
     */
    @Transactional
    public ChangeRequest approve(Long id, String approvedBy) {
        Objects.requireNonNull(approvedBy, "approvedBy");
        ChangeRequestEntity entity = loadOr404(id);
        ChangeRequestState next = driveTransition(entity.getState(), ChangeRequestEvent.APPROVE);
        if (next == entity.getState()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "change_request " + id + " is in state " + entity.getState()
                            + ", cannot approve");
        }
        entity.setApprovedBy(approvedBy);
        entity.setApprovedAt(Instant.now());
        entity.setState(next);
        // Flush so the CHECK constraint on (proposed_by IS DISTINCT FROM approved_by)
        // fires inside this method rather than at end-of-transaction — the caller
        // sees the ConstraintViolationException on the approve() call itself.
        ChangeRequestEntity saved = repository.saveAndFlush(entity);
        return saved.toDomain();
    }

    /**
     * Reject a change_request with a mandatory reason. Available from any
     * non-terminal state ({@link ChangeRequestState#DRAFT DRAFT},
     * {@link ChangeRequestState#PROPOSED PROPOSED},
     * {@link ChangeRequestState#APPROVED APPROVED}).
     *
     * @throws ResponseStatusException 404 if the row is missing; 409 if already
     *         in a terminal state (APPLIED or REJECTED); 400 if reason is blank
     */
    @Transactional
    public ChangeRequest reject(Long id, String rejectedBy, String reason) {
        Objects.requireNonNull(rejectedBy, "rejectedBy");
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "rejection reason required");
        }
        ChangeRequestEntity entity = loadOr404(id);
        ChangeRequestState next = driveTransition(entity.getState(), ChangeRequestEvent.REJECT);
        if (next == entity.getState()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "change_request " + id + " is in terminal state " + entity.getState()
                            + ", cannot reject");
        }
        // Rejection is the second pair of eyes too — record who pressed reject
        // in approved_by so the audit trail shows the deciding operator. The
        // 4-eyes CHECK still applies: rejected_by must differ from proposed_by
        // (or both = 'system').
        entity.setApprovedBy(rejectedBy);
        entity.setApprovedAt(Instant.now());
        entity.setRejectedReason(reason);
        entity.setState(next);
        ChangeRequestEntity saved = repository.saveAndFlush(entity);
        return saved.toDomain();
    }

    /**
     * Apply an APPROVED change_request to its underlying aggregate
     * (APPROVED → APPLIED). The matching {@link ChangeRequestApplier} runs
     * inside this transaction; if it throws, the apply is rolled back and the
     * row stays in APPROVED.
     *
     * @throws ResponseStatusException 404 if the row is missing; 409 if the row
     *         is not in APPROVED state (FSM refuses); 500 if no applier is
     *         registered for the aggregate type
     */
    @Transactional
    public ChangeRequest apply(Long id) {
        ChangeRequestEntity entity = loadOr404(id);
        ChangeRequestState next = driveTransition(entity.getState(), ChangeRequestEvent.APPLY);
        if (next == entity.getState()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "change_request " + id + " is in state " + entity.getState()
                            + ", cannot apply (must be APPROVED)");
        }

        ChangeRequestApplier applier = appliersByType.get(entity.getAggregateType());
        if (applier == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "no ChangeRequestApplier registered for aggregateType="
                            + entity.getAggregateType());
        }
        applier.apply(entity.toDomain());

        entity.setState(next);
        ChangeRequestEntity saved = repository.saveAndFlush(entity);
        return saved.toDomain();
    }

    /** Read a change_request by id; returns the domain record. */
    @Transactional(readOnly = true)
    public ChangeRequest get(Long id) {
        return loadOr404(id).toDomain();
    }

    /** List change_requests for an aggregate row, newest-first. */
    @Transactional(readOnly = true)
    public List<ChangeRequest> listForAggregate(String aggregateType, String aggregateId) {
        return repository
                .findByAggregateTypeAndAggregateIdOrderByProposedAtDesc(aggregateType, aggregateId)
                .stream().map(ChangeRequestEntity::toDomain).toList();
    }

    /** Approval queue: PROPOSED rows, oldest-first. */
    @Transactional(readOnly = true)
    public List<ChangeRequest> listPendingApprovals() {
        return repository.findByStateOrderByProposedAtAsc(ChangeRequestState.PROPOSED)
                .stream().map(ChangeRequestEntity::toDomain).toList();
    }

    /**
     * Pull the next id from {@code change_request_id_seq} (V005). Mirrors the
     * pattern used by {@code PartnerStore} for {@code partners_id_seq}: explicit
     * NEXTVAL at the application layer works identically against PostgreSQL and
     * H2 in PostgreSQL mode, where Hibernate-managed sequences are flaky.
     */
    private Long nextSurrogateId() {
        Object value = entityManager
                .createNativeQuery("select nextval('change_request_id_seq')")
                .getSingleResult();
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException(
                "change_request_id_seq returned non-numeric value: " + value);
    }

    private ChangeRequestEntity loadOr404(Long id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "change_request " + id + " not found"));
    }

    /**
     * Drive the FSM from the given source state with the given event. Returns
     * the resulting state — equal to {@code source} when the FSM refuses the
     * transition (event has no edge from this source). Callers translate
     * "no transition fired" into a 409 with a meaningful message.
     */
    private ChangeRequestState driveTransition(ChangeRequestState source, ChangeRequestEvent event) {
        StateMachine<ChangeRequestState, ChangeRequestEvent> sm =
                stateMachineFactory.getStateMachine();
        // Reset the machine to the persisted source state before sending the
        // event — the factory always hands us a fresh instance initialised to
        // DRAFT, but the row may already be PROPOSED / APPROVED.
        sm.stopReactively().block();
        sm.getStateMachineAccessor().doWithAllRegions(access ->
                access.resetStateMachineReactively(
                        new org.springframework.statemachine.support.DefaultStateMachineContext<>(
                                source, null, null, null)).block());
        sm.startReactively().block();
        sm.sendEvent(Mono.just(MessageBuilder.withPayload(event).build())).blockLast();
        ChangeRequestState resulting = sm.getState().getId();
        sm.stopReactively().block();
        return resulting;
    }
}
