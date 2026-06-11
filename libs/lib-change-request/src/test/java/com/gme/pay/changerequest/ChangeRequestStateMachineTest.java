package com.gme.pay.changerequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import reactor.core.publisher.Mono;

/**
 * Pure FSM test for {@link ChangeRequestStateMachineConfig}: no DB, no Spring Boot
 * — only the bare {@code spring-context} that ships with lib-change-request.
 *
 * <p>Verifies the procedural half of the 4-eyes invariant: the maker cannot skip
 * checker review by going straight from {@link ChangeRequestState#DRAFT DRAFT}
 * to {@link ChangeRequestState#APPLIED APPLIED}. The corresponding DB-enforced
 * half (the CHECK constraint on {@code change_request}) is exercised in each
 * adopting service's own integration test, e.g. config-registry's
 * {@code ChangeRequestServiceTest}.
 */
class ChangeRequestStateMachineTest {

    private AnnotationConfigApplicationContext ctx;
    private StateMachineFactory<ChangeRequestState, ChangeRequestEvent> factory;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(ChangeRequestStateMachineConfig.class);
        @SuppressWarnings("unchecked")
        StateMachineFactory<ChangeRequestState, ChangeRequestEvent> f =
                ctx.getBean(StateMachineFactory.class);
        this.factory = f;
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    @Test
    void happyPathDraftToApplied() {
        StateMachine<ChangeRequestState, ChangeRequestEvent> sm = factory.getStateMachine();
        sm.startReactively().block();

        assertEquals(ChangeRequestState.DRAFT, sm.getState().getId());
        send(sm, ChangeRequestEvent.SUBMIT);
        assertEquals(ChangeRequestState.PROPOSED, sm.getState().getId());
        send(sm, ChangeRequestEvent.APPROVE);
        assertEquals(ChangeRequestState.APPROVED, sm.getState().getId());
        send(sm, ChangeRequestEvent.APPLY);
        assertEquals(ChangeRequestState.APPLIED, sm.getState().getId());
    }

    @Test
    void rejectFromDraft() {
        StateMachine<ChangeRequestState, ChangeRequestEvent> sm = factory.getStateMachine();
        sm.startReactively().block();
        send(sm, ChangeRequestEvent.REJECT);
        assertEquals(ChangeRequestState.REJECTED, sm.getState().getId());
    }

    @Test
    void rejectFromProposed() {
        StateMachine<ChangeRequestState, ChangeRequestEvent> sm = factory.getStateMachine();
        sm.startReactively().block();
        send(sm, ChangeRequestEvent.SUBMIT);
        send(sm, ChangeRequestEvent.REJECT);
        assertEquals(ChangeRequestState.REJECTED, sm.getState().getId());
    }

    @Test
    void rejectFromApproved() {
        StateMachine<ChangeRequestState, ChangeRequestEvent> sm = factory.getStateMachine();
        sm.startReactively().block();
        send(sm, ChangeRequestEvent.SUBMIT);
        send(sm, ChangeRequestEvent.APPROVE);
        send(sm, ChangeRequestEvent.REJECT);
        assertEquals(ChangeRequestState.REJECTED, sm.getState().getId());
    }

    /**
     * The procedural half of the 4-eyes invariant: APPLY is only an edge from
     * APPROVED. Firing APPLY in DRAFT must NOT advance the state — the maker
     * has to walk SUBMIT then APPROVE first.
     */
    @Test
    void cannotJumpDraftToAppliedDirectly() {
        StateMachine<ChangeRequestState, ChangeRequestEvent> sm = factory.getStateMachine();
        sm.startReactively().block();
        assertEquals(ChangeRequestState.DRAFT, sm.getState().getId());

        send(sm, ChangeRequestEvent.APPLY);

        assertEquals(ChangeRequestState.DRAFT, sm.getState().getId(),
                "DRAFT --APPLY--> APPLIED must not be a legal transition");
    }

    /** Same defence against skipping the checker from PROPOSED → APPLIED. */
    @Test
    void cannotJumpProposedToAppliedDirectly() {
        StateMachine<ChangeRequestState, ChangeRequestEvent> sm = factory.getStateMachine();
        sm.startReactively().block();
        send(sm, ChangeRequestEvent.SUBMIT);
        assertEquals(ChangeRequestState.PROPOSED, sm.getState().getId());

        send(sm, ChangeRequestEvent.APPLY);

        assertEquals(ChangeRequestState.PROPOSED, sm.getState().getId(),
                "PROPOSED --APPLY--> APPLIED must not be a legal transition");
    }

    private static void send(StateMachine<ChangeRequestState, ChangeRequestEvent> sm,
                             ChangeRequestEvent event) {
        sm.sendEvent(Mono.just(MessageBuilder.withPayload(event).build())).blockLast();
    }
}
