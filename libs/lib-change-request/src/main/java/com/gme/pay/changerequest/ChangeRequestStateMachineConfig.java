package com.gme.pay.changerequest;

import java.util.EnumSet;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

/**
 * Spring State Machine wiring for {@link ChangeRequest} per ADR-008.
 *
 * <p>Allowed edges:
 * <pre>
 *   DRAFT    --SUBMIT-->  PROPOSED
 *   PROPOSED --APPROVE--> APPROVED
 *   APPROVED --APPLY-->   APPLIED      (terminal success)
 *   DRAFT    --REJECT-->  REJECTED     (terminal failure)
 *   PROPOSED --REJECT-->  REJECTED     (terminal failure)
 *   APPROVED --REJECT-->  REJECTED     (terminal failure)
 * </pre>
 *
 * <p>Crucially, there is <b>no</b> edge from {@code DRAFT} or {@code PROPOSED}
 * to {@code APPLIED}. A maker cannot skip checker approval, and a checker
 * cannot conjure an apply from a state that has not been approved. Spring State
 * Machine ignores events whose source state has no configured outgoing edge for
 * that event, so the caller observes the state machine staying put — the
 * service layer then refuses with a clear "illegal transition" exception (see
 * {@code ChangeRequestService} in each adopting module).
 *
 * <p>This factory is exposed via {@link EnableStateMachineFactory} so each
 * {@link ChangeRequest} gets its own short-lived state machine instance,
 * initialised to the current persisted state and re-driven for the requested
 * transition. We do not use a single long-lived state machine: change_request
 * rows are independent and may be processed concurrently.
 */
@Configuration
@EnableStateMachineFactory
public class ChangeRequestStateMachineConfig
        extends StateMachineConfigurerAdapter<ChangeRequestState, ChangeRequestEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<ChangeRequestState, ChangeRequestEvent> states)
            throws Exception {
        states
                .withStates()
                .initial(ChangeRequestState.DRAFT)
                .states(EnumSet.allOf(ChangeRequestState.class))
                .end(ChangeRequestState.APPLIED)
                .end(ChangeRequestState.REJECTED);
    }

    @Override
    public void configure(
            StateMachineTransitionConfigurer<ChangeRequestState, ChangeRequestEvent> transitions)
            throws Exception {
        transitions
                .withExternal()
                .source(ChangeRequestState.DRAFT)
                .target(ChangeRequestState.PROPOSED)
                .event(ChangeRequestEvent.SUBMIT)
                .and()
                .withExternal()
                .source(ChangeRequestState.PROPOSED)
                .target(ChangeRequestState.APPROVED)
                .event(ChangeRequestEvent.APPROVE)
                .and()
                .withExternal()
                .source(ChangeRequestState.APPROVED)
                .target(ChangeRequestState.APPLIED)
                .event(ChangeRequestEvent.APPLY)
                .and()
                // REJECT is available from every non-terminal state.
                .withExternal()
                .source(ChangeRequestState.DRAFT)
                .target(ChangeRequestState.REJECTED)
                .event(ChangeRequestEvent.REJECT)
                .and()
                .withExternal()
                .source(ChangeRequestState.PROPOSED)
                .target(ChangeRequestState.REJECTED)
                .event(ChangeRequestEvent.REJECT)
                .and()
                .withExternal()
                .source(ChangeRequestState.APPROVED)
                .target(ChangeRequestState.REJECTED)
                .event(ChangeRequestEvent.REJECT);
    }
}
