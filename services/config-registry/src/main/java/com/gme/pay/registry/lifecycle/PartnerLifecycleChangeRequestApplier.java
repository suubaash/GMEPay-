package com.gme.pay.registry.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.changerequest.ChangeRequest;
import com.gme.pay.contracts.PartnerLifecycleAction;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerStatusTransitionTable;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.changerequest.ChangeRequestApplier;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link ChangeRequestApplier} for the {@code "partner_lifecycle"} aggregate —
 * the apply-side of the Slice 8 4-eyes lifecycle transitions (ADR-008/ADR-011).
 *
 * <p>{@code payload_jsonb} carries {@code {"action": "...", "reason": "...",
 * "notes": "..."}} as written by {@link PartnerLifecycleService}. On APPLY this
 * component re-validates the FSM edge against the CURRENT partner status (it
 * may have moved between propose and approve), re-runs the activation gate for
 * ACTIVATE (defence in depth — the service already 422s before approving), and
 * stamps the V025 lifecycle columns.
 *
 * <h2>Write shape</h2>
 *
 * <p>The status + lifecycle stamps are updated on the CURRENT row rather than
 * through an SCD-6 paired write: {@code status} is the row's lifecycle GATE,
 * not its content (V008 header), and the regulator-defensible history of every
 * transition is the same-transaction {@code audit_log} row (ADR-007 hash
 * chain) carrying the before/after status snapshots — the identical discipline
 * the Identity-column writes use during the ADR-013 Expand phase.
 * {@code PartnerStore.save} carries the stamps forward onto any later SCD-6
 * generation, so the lifecycle position survives content writes.
 */
@Component
public class PartnerLifecycleChangeRequestApplier implements ChangeRequestApplier {

    /** Aggregate type discriminator stored on the change_request row. */
    public static final String AGGREGATE_TYPE = "partner_lifecycle";

    public static final String EVENT_ACTIVATED = "PARTNER_ACTIVATED";
    public static final String EVENT_SUSPENDED = "PARTNER_SUSPENDED";
    public static final String EVENT_REACTIVATED = "PARTNER_REACTIVATED";
    public static final String EVENT_TERMINATED = "PARTNER_TERMINATED";

    private final PartnerRepository partnerRepository;
    private final ActivationGateService activationGateService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerLifecycleChangeRequestApplier(
            PartnerRepository partnerRepository,
            ActivationGateService activationGateService,
            ObjectMapper objectMapper,
            ObjectProvider<AuditLogService> auditLogProvider) {
        this.partnerRepository = partnerRepository;
        this.activationGateService = activationGateService;
        this.objectMapper = objectMapper;
        this.auditLogProvider = auditLogProvider;
    }

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    @Override
    public void apply(ChangeRequest request) {
        if (request.payloadJsonb() == null || request.payloadJsonb().isBlank()) {
            throw new IllegalArgumentException(
                    "partner_lifecycle change_request " + request.id() + " has no payload");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(request.payloadJsonb());
        } catch (Exception e) {
            throw new IllegalArgumentException("partner_lifecycle change_request "
                    + request.id() + " has malformed payload", e);
        }
        PartnerLifecycleAction action =
                PartnerLifecycleAction.valueOf(node.get("action").asText());
        String reason = node.hasNonNull("reason") ? node.get("reason").asText() : null;
        String notes = node.hasNonNull("notes") ? node.get("notes").asText() : null;

        String partnerCode = request.aggregateId();
        PartnerEntity partner = partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new IllegalStateException(
                        "no current partner row for '" + partnerCode + "'"));

        PartnerStatus from = partner.getStatus();
        PartnerStatus to = PartnerLifecycleService.targetStatus(action);
        // The partner may have moved between propose and approve — re-validate
        // the edge against TODAY's status so a stale approval cannot apply.
        if (!PartnerStatusTransitionTable.isAllowed(from, to)
                || !PartnerLifecycleService.sourceStatuses(action).contains(from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + from
                            + ", cannot apply lifecycle action " + action);
        }

        byte[] before = canonicalLifecycle(partner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // The deciding operator: the checker when present, else the proposer
        // (the (system, system) ADR-008 carve-out).
        String actor = request.approvedBy() != null ? request.approvedBy()
                : request.proposedBy();

        String eventType;
        switch (action) {
            case ACTIVATE -> {
                // Defence in depth: the service refuses to approve when the gate
                // fails, but the gate may regress between approve and apply (or a
                // caller may drive the change_request endpoints directly).
                ActivationGateService.ActivationGateResult gate =
                        activationGateService.check(partner);
                if (!gate.passes()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "activation gate unmet: " + gate.unmet().stream()
                                    .map(ActivationGateService.UnmetCondition::code)
                                    .collect(Collectors.joining(", ")));
                }
                partner.setStatus(PartnerStatus.LIVE);
                if (partner.getGoLiveAt() == null) {
                    // First Go-live only: the stamp is the immutability lock
                    // marker and must never move on re-activations.
                    partner.setGoLiveAt(now);
                    partner.setActivatedBy(actor);
                }
                eventType = EVENT_ACTIVATED;
            }
            case SUSPEND -> {
                partner.setStatus(PartnerStatus.SUSPENDED);
                partner.setSuspensionReason(reason);
                partner.setSuspensionNotes(notes);
                partner.setSuspendedAt(now);
                eventType = EVENT_SUSPENDED;
            }
            case REACTIVATE -> {
                partner.setStatus(PartnerStatus.LIVE);
                partner.setSuspensionReason(null);
                partner.setSuspensionNotes(null);
                partner.setSuspendedAt(null);
                eventType = EVENT_REACTIVATED;
            }
            case TERMINATE -> {
                partner.setStatus(PartnerStatus.TERMINATED);
                partner.setTerminatedAt(now);
                partner.setTerminationReason(reason);
                eventType = EVENT_TERMINATED;
            }
            default -> throw new IllegalStateException("unhandled action " + action);
        }

        PartnerEntity saved = partnerRepository.saveAndFlush(partner);
        publishAudit(partnerCode, actor, eventType, before, canonicalLifecycle(saved));
    }

    /** ADR-007 audit row, same-transaction (commits iff the transition commits). */
    private void publishAudit(String partnerCode, String actor, String eventType,
                              byte[] before, byte[] after) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(AGGREGATE_TYPE, partnerCode, actor, null,
                    eventType, before, after);
        }
    }

    /**
     * Deterministic UTF-8 JSON snapshot of the lifecycle-relevant columns for
     * the audit hash chain. Hand-built in fixed key order (the ADR-007
     * canonicalisation discipline — what the auditor sees IS what was hashed).
     */
    static byte[] canonicalLifecycle(PartnerEntity p) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"partnerCode\":").append(jsonString(p.getPartnerCode()));
        sb.append(",\"status\":").append(jsonString(
                p.getStatus() == null ? null : p.getStatus().name()));
        sb.append(",\"goLiveAt\":").append(jsonString(
                p.getGoLiveAt() == null ? null : p.getGoLiveAt().toString()));
        sb.append(",\"activatedBy\":").append(jsonString(p.getActivatedBy()));
        sb.append(",\"suspensionReason\":").append(jsonString(p.getSuspensionReason()));
        sb.append(",\"suspensionNotes\":").append(jsonString(p.getSuspensionNotes()));
        sb.append(",\"suspendedAt\":").append(jsonString(
                p.getSuspendedAt() == null ? null : p.getSuspendedAt().toString()));
        sb.append(",\"terminatedAt\":").append(jsonString(
                p.getTerminatedAt() == null ? null : p.getTerminatedAt().toString()));
        sb.append(",\"terminationReason\":").append(jsonString(p.getTerminationReason()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
