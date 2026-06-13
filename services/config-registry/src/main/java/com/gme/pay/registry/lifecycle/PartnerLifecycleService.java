package com.gme.pay.registry.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gme.pay.changerequest.ChangeRequest;
import com.gme.pay.changerequest.ChangeRequestState;
import com.gme.pay.contracts.IssuedCredentialBundle;
import com.gme.pay.contracts.PartnerLifecycleAction;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerStatusTransitionTable;
import com.gme.pay.contracts.SuspensionReason;
import com.gme.pay.registry.changerequest.ChangeRequestEntity;
import com.gme.pay.registry.changerequest.ChangeRequestRepository;
import com.gme.pay.registry.changerequest.ChangeRequestService;
import com.gme.pay.registry.credential.PartnerCredentialService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import jakarta.persistence.PersistenceException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 orchestrator for the 4-eyes partner lifecycle transitions
 * (ADR-008 + ADR-011): {@code ACTIVATE / SUSPEND / REACTIVATE / TERMINATE}.
 *
 * <h2>Two-call protocol</h2>
 *
 * <p>The SAME endpoint is called twice with the same body:
 * <ol>
 *   <li><b>Maker</b> — no PROPOSED lifecycle change_request exists for this
 *       partner + action, so one is created ({@link Outcome.Pending}). The
 *       transition has NOT happened yet.</li>
 *   <li><b>Checker</b> — a PROPOSED row exists; the (different — V005 CHECK)
 *       operator approves and the {@link PartnerLifecycleChangeRequestApplier}
 *       applies the transition in the same transaction
 *       ({@link Outcome.Completed}). For ACTIVATE the activation gate runs
 *       BEFORE the approval — a failing gate returns
 *       {@link Outcome.GateFailed} (HTTP 422 + unmet[]) and leaves the
 *       change_request PROPOSED so it can be re-approved once fixed.</li>
 * </ol>
 *
 * <p>Self-approval is rejected by the V005 {@code ck_change_request_four_eyes}
 * CHECK (surfaced as 409), with the ADR-008 {@code (system, system)} carve-out
 * for bot flows (e.g. Slice 5 auto-suspend).
 */
@Service
public class PartnerLifecycleService {

    /** Aggregate-type discriminator on lifecycle change_request rows. */
    public static final String AGGREGATE_TYPE =
            PartnerLifecycleChangeRequestApplier.AGGREGATE_TYPE;

    /** Pre-Keycloak default actor (Slice 1B.4 carve-out, same as PartnerDraftService). */
    private static final String DEFAULT_ACTOR = "system";

    private final PartnerRepository partnerRepository;
    private final ChangeRequestRepository changeRequestRepository;
    private final ChangeRequestService changeRequestService;
    private final ActivationGateService activationGateService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<PartnerCredentialService> credentialServiceProvider;

    public PartnerLifecycleService(PartnerRepository partnerRepository,
                                   ChangeRequestRepository changeRequestRepository,
                                   ChangeRequestService changeRequestService,
                                   ActivationGateService activationGateService,
                                   ObjectMapper objectMapper,
                                   ObjectProvider<PartnerCredentialService>
                                           credentialServiceProvider) {
        this.partnerRepository = partnerRepository;
        this.changeRequestRepository = changeRequestRepository;
        this.changeRequestService = changeRequestService;
        this.activationGateService = activationGateService;
        this.objectMapper = objectMapper;
        this.credentialServiceProvider = credentialServiceProvider;
    }

    /**
     * Result of one lifecycle endpoint call. Sealed so the controller can
     * switch exhaustively onto the three HTTP shapes (202 / 200 / 422).
     */
    public sealed interface Outcome
            permits Outcome.Pending, Outcome.Completed, Outcome.GateFailed {

        /** Maker call: a change_request now awaits a second pair of eyes. */
        record Pending(ChangeRequest changeRequest) implements Outcome {
        }

        /**
         * Checker call: the transition has been applied.
         *
         * @param issuedCredentials the ONE-TIME plaintext bundle when the
         *        transition provisioned a credential tier (Slice 8 Lane B:
         *        first entry into SANDBOX or LIVE), else {@code null}. NEVER
         *        logged or persisted — it rides the HTTP response once
         *        (SEC-09 §4).
         */
        record Completed(PartnerEntity partner,
                         IssuedCredentialBundle issuedCredentials) implements Outcome {
        }

        /** Checker call on ACTIVATE: the activation gate refused (422 + unmet[]). */
        record GateFailed(ActivationGateService.ActivationGateResult gate) implements Outcome {
        }
    }

    /**
     * Drive one lifecycle action through the two-call 4-eyes protocol.
     *
     * @param partnerCode the human-facing business code on the URL.
     * @param action      which transition is requested.
     * @param reason      SUSPEND: a {@link SuspensionReason} name (required);
     *                    TERMINATE: free text ≤500 (required); else ignored.
     * @param notes       SUSPEND only: optional free text ≤500.
     * @param actor       X-Actor header; {@code "system"} when absent.
     * @throws ResponseStatusException 404 unknown partner; 400 bad/missing
     *         reason or notes; 422 the current status does not permit the
     *         action; 409 self-approval.
     */
    @Transactional
    public Outcome execute(String partnerCode, PartnerLifecycleAction action,
                           String reason, String notes, String actor) {
        String resolvedActor = actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor;
        PartnerEntity partner = requirePartner(partnerCode);

        validateSourceStatus(partner, action);
        validateReason(action, reason, notes);

        Optional<ChangeRequestEntity> pending = findPendingProposal(partnerCode, action);
        if (pending.isEmpty()) {
            // Maker call: park the request for a second pair of eyes.
            ChangeRequest proposed = changeRequestService.propose(
                    AGGREGATE_TYPE, partnerCode, resolvedActor,
                    payloadJson(action, reason, notes),
                    new String[]{"lifecycle:" + action.name()});
            return new Outcome.Pending(proposed);
        }

        // Checker call: gate first (ACTIVATE only) so a failing gate leaves the
        // change_request PROPOSED — fix the partner, approve again.
        if (action == PartnerLifecycleAction.ACTIVATE) {
            ActivationGateService.ActivationGateResult gate =
                    activationGateService.check(partner);
            if (!gate.passes()) {
                return new Outcome.GateFailed(gate);
            }
        }

        Long changeRequestId = pending.get().getId();
        try {
            changeRequestService.approve(changeRequestId, resolvedActor);
        } catch (DataIntegrityViolationException | PersistenceException e) {
            // The V005 ck_change_request_four_eyes CHECK fired — the proposer
            // tried to approve their own lifecycle request.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Self-approval not permitted: lifecycle " + action
                            + " for partner '" + partnerCode
                            + "' was proposed by the same operator");
        }
        changeRequestService.apply(changeRequestId);

        PartnerEntity transitioned = requirePartner(partnerCode);

        // Slice 8 Lane B wire-up: first entry into a credential-bearing tier
        // (SANDBOX / LIVE) mints the partner's API key + HMAC secret + webhook
        // secret INSIDE this transaction — a failed issuance rolls the whole
        // transition back. issueForTransition is idempotent (REACTIVATE finds
        // its PRODUCTION keys already ACTIVE and issues nothing). The bundle
        // is the one-time plaintext: it rides the response and is gone.
        IssuedCredentialBundle issued = null;
        PartnerCredentialService credentialService =
                credentialServiceProvider.getIfAvailable();
        if (credentialService != null) {
            issued = credentialService.issueForTransition(
                            partnerCode, transitioned.getStatus().name(), resolvedActor)
                    .orElse(null);
        }

        return new Outcome.Completed(transitioned, issued);
    }

    /**
     * Non-mutating gate evaluation for the Admin UI pre-activation checklist
     * ({@code GET …/lifecycle/preconditions}).
     *
     * @throws ResponseStatusException 404 when the partner code is unknown.
     */
    @Transactional(readOnly = true)
    public ActivationGateService.ActivationGateResult preconditions(String partnerCode) {
        return activationGateService.check(requirePartner(partnerCode));
    }

    // -------------------------- FSM mapping ----------------------------------

    /** The FSM target each action drives toward. */
    static PartnerStatus targetStatus(PartnerLifecycleAction action) {
        return switch (action) {
            case ACTIVATE, REACTIVATE -> PartnerStatus.LIVE;
            case SUSPEND -> PartnerStatus.SUSPENDED;
            case TERMINATE -> PartnerStatus.TERMINATED;
        };
    }

    /**
     * The statuses an action may legally start from. ACTIVATE and REACTIVATE
     * share the LIVE target but ride different edges (UAT → LIVE vs
     * SUSPENDED → LIVE), so the action — not just the edge — pins the source.
     */
    static Set<PartnerStatus> sourceStatuses(PartnerLifecycleAction action) {
        return switch (action) {
            case ACTIVATE -> EnumSet.of(PartnerStatus.UAT);
            case SUSPEND -> EnumSet.of(PartnerStatus.LIVE);
            case REACTIVATE -> EnumSet.of(PartnerStatus.SUSPENDED);
            case TERMINATE -> EnumSet.of(PartnerStatus.LIVE, PartnerStatus.SUSPENDED);
        };
    }

    // -------------------------- Helpers --------------------------------------

    private PartnerEntity requirePartner(String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    /** 422 when the current status does not permit the requested action. */
    private static void validateSourceStatus(PartnerEntity partner,
                                             PartnerLifecycleAction action) {
        PartnerStatus from = partner.getStatus();
        if (!sourceStatuses(action).contains(from)
                || !PartnerStatusTransitionTable.isAllowed(from, targetStatus(action))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "partner '" + partner.getPartnerCode() + "' is in status " + from
                            + "; lifecycle action " + action + " requires status "
                            + sourceStatuses(action));
        }
    }

    /** 400 on missing/over-length/unknown reason or over-length notes. */
    private static void validateReason(PartnerLifecycleAction action,
                                       String reason, String notes) {
        switch (action) {
            case SUSPEND -> {
                if (reason == null || reason.isBlank()) {
                    throw badRequest("reason is required for SUSPEND (one of "
                            + EnumSet.allOf(SuspensionReason.class) + ")");
                }
                try {
                    SuspensionReason.valueOf(reason);
                } catch (IllegalArgumentException e) {
                    throw badRequest("reason must be one of "
                            + EnumSet.allOf(SuspensionReason.class) + ", was: " + reason);
                }
                if (notes != null && notes.length() > 500) {
                    throw badRequest("notes must be at most 500 characters");
                }
            }
            case TERMINATE -> {
                if (reason == null || reason.isBlank()) {
                    throw badRequest("reason is required for TERMINATE");
                }
                if (reason.length() > 500) {
                    throw badRequest("reason must be at most 500 characters");
                }
            }
            case ACTIVATE, REACTIVATE -> {
                // no body fields to validate
            }
        }
    }

    /**
     * The PROPOSED lifecycle change_request for this partner + action, if one
     * exists. Matching includes the action (parsed from the payload) so an
     * in-flight SUSPEND proposal does not get consumed by a TERMINATE call.
     */
    private Optional<ChangeRequestEntity> findPendingProposal(String partnerCode,
                                                              PartnerLifecycleAction action) {
        List<ChangeRequestEntity> rows = changeRequestRepository
                .findByAggregateTypeAndAggregateIdOrderByProposedAtDesc(
                        AGGREGATE_TYPE, partnerCode);
        for (ChangeRequestEntity row : rows) {
            if (row.getState() == ChangeRequestState.PROPOSED
                    && action == parseAction(row.getPayloadJsonb())) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    private PartnerLifecycleAction parseAction(String payloadJsonb) {
        if (payloadJsonb == null || payloadJsonb.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJsonb);
            return node.hasNonNull("action")
                    ? PartnerLifecycleAction.valueOf(node.get("action").asText())
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String payloadJson(PartnerLifecycleAction action, String reason, String notes) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("action", action.name());
        if (reason != null && !reason.isBlank()) {
            node.put("reason", reason);
        }
        if (notes != null && !notes.isBlank()) {
            node.put("notes", notes);
        }
        return node.toString();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
