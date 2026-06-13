package com.gme.pay.registry.credential;

import com.gme.pay.changerequest.ChangeRequestState;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.changerequest.ChangeRequestEntity;
import com.gme.pay.registry.changerequest.ChangeRequestRepository;
import com.gme.pay.registry.changerequest.ChangeRequestService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice 8 Lane B — the 11-month credential-rotation sweep.
 *
 * <h2>What it does</h2>
 *
 * <p>Every Monday 02:00 KST it finds ACTIVE {@code partner_credential} rows
 * (V028) issued more than {@value #ROTATION_AFTER_MONTHS} months ago and, per
 * (partner, environment) group, PROPOSES a rotation through the ADR-008
 * change_request flow ({@code proposed_by = "system"}). It deliberately does
 * NOT rotate anything itself — rotation invalidates a partner's keys, so a
 * human checker must approve (4-eyes; Lane A wires the approver flow that
 * calls {@link PartnerCredentialService#rotateCredentials} on APPLY).
 *
 * <h2>No double-propose</h2>
 *
 * <p>A (partner, environment) group is skipped while an open (PROPOSED or
 * APPROVED) {@code partner_credential} change_request already exists for the
 * partner — re-running the sweep is idempotent until the checker acts.
 *
 * <h2>Why 11 of 12 months</h2>
 *
 * <p>Issued material expires after 12 months
 * ({@link PartnerCredentialService#VALIDITY_MONTHS}); proposing at 11 gives
 * operations a month to approve + the partner to swap keys before anything
 * hard-expires.
 */
@Component
public class PartnerCredentialRotationScheduler {

    /** Audit verb for one proposed rotation. */
    public static final String EVENT_TYPE_PROPOSED = "CREDENTIAL_ROTATION_PROPOSED";

    /** ACTIVE credentials older than this many months get a rotation proposal. */
    public static final int ROTATION_AFTER_MONTHS = 11;

    /** The change_request aggregate the proposals land on. */
    public static final String AGGREGATE_TYPE = PartnerCredentialService.AGGREGATE_TYPE;

    private static final Logger log =
            LoggerFactory.getLogger(PartnerCredentialRotationScheduler.class);

    private static final String SYSTEM_ACTOR = "system";

    private final PartnerCredentialRepository credentialRepository;
    private final PartnerRepository partnerRepository;
    private final ChangeRequestService changeRequestService;
    private final ChangeRequestRepository changeRequestRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerCredentialRotationScheduler(
            PartnerCredentialRepository credentialRepository,
            PartnerRepository partnerRepository,
            ChangeRequestService changeRequestService,
            ChangeRequestRepository changeRequestRepository,
            ObjectProvider<AuditLogService> auditLogProvider) {
        this.credentialRepository = credentialRepository;
        this.partnerRepository = partnerRepository;
        this.changeRequestService = changeRequestService;
        this.changeRequestRepository = changeRequestRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /** Weekly sweep — Monday 02:00 KST (cron in Asia/Seoul, the ops timezone). */
    @Scheduled(cron = "0 0 2 * * MON", zone = "Asia/Seoul")
    @Transactional
    public void proposeOverdueRotations() {
        int proposed = sweep(Instant.now());
        if (proposed > 0) {
            log.info("credential rotation sweep proposed {} change_request(s)", proposed);
        }
    }

    /**
     * The sweep body, testable with an injected clock instant.
     *
     * @return how many change_requests were proposed.
     */
    int sweep(Instant now) {
        Instant threshold = now.atOffset(ZoneOffset.UTC)
                .minusMonths(ROTATION_AFTER_MONTHS).toInstant()
                .truncatedTo(ChronoUnit.MICROS);
        List<PartnerCredentialEntity> overdue =
                credentialRepository.findByStatusAndIssuedAtBefore("ACTIVE", threshold);
        if (overdue.isEmpty()) {
            return 0;
        }

        // Group by (partnerId, environment): one proposal rotates the whole set.
        Map<String, List<PartnerCredentialEntity>> groups = new LinkedHashMap<>();
        for (PartnerCredentialEntity row : overdue) {
            groups.computeIfAbsent(row.getPartnerId() + ":" + row.getEnvironment(),
                    k -> new java.util.ArrayList<>()).add(row);
        }

        int proposed = 0;
        for (List<PartnerCredentialEntity> group : groups.values()) {
            PartnerCredentialEntity first = group.get(0);
            Optional<PartnerEntity> partner = partnerRepository.findById(first.getPartnerId())
                    .filter(p -> p.getSupersededAt() == null);
            if (partner.isEmpty()) {
                // Superseded row id or a partner deleted mid-sweep: resolve the
                // current row by walking the credential's partner_id through
                // any current version; skip when none (terminated partner).
                partner = currentPartnerById(first.getPartnerId());
                if (partner.isEmpty()) {
                    continue;
                }
            }
            String partnerCode = partner.get().getPartnerCode();
            if (hasOpenProposal(partnerCode)) {
                continue; // idempotent: checker has not acted on the prior sweep yet
            }
            String environment = first.getEnvironment();
            String payload = proposalPayload(environment, group, now);
            changeRequestService.propose(AGGREGATE_TYPE, partnerCode, SYSTEM_ACTOR,
                    payload, new String[] {"credentials." + environment});
            publishAudit(partnerCode, payload);
            proposed++;
        }
        return proposed;
    }

    /** True while an open (PROPOSED / APPROVED) rotation proposal exists. */
    private boolean hasOpenProposal(String partnerCode) {
        List<ChangeRequestEntity> existing = changeRequestRepository
                .findByAggregateTypeAndAggregateIdOrderByProposedAtDesc(
                        AGGREGATE_TYPE, partnerCode);
        return existing.stream().anyMatch(cr ->
                cr.getState() == ChangeRequestState.PROPOSED
                        || cr.getState() == ChangeRequestState.APPROVED);
    }

    /**
     * Current partner row whose surrogate matches the ledger FK. The ledger
     * stores the surrogate of whichever partner version was current at
     * issuance; under SCD-6 the partner aggregate may have new row ids since.
     */
    private Optional<PartnerEntity> currentPartnerById(Long partnerId) {
        return partnerRepository.findById(partnerId)
                .map(PartnerEntity::getPartnerCode)
                .flatMap(partnerRepository::findCurrentByPartnerCode);
    }

    /** Change-request payload: what to rotate and why (display residue only). */
    private static String proposalPayload(String environment,
                                          List<PartnerCredentialEntity> group, Instant now) {
        StringBuilder ids = new StringBuilder();
        for (PartnerCredentialEntity row : group) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(row.getId());
        }
        return "{\"action\":\"ROTATE_CREDENTIALS\",\"environment\":\"" + environment
                + "\",\"credentialIds\":[" + ids + "],\"reason\":\"issued more than "
                + ROTATION_AFTER_MONTHS + " months ago\",\"sweptAt\":\"" + now + "\"}";
    }

    /** ADR-007 audit row for the proposal (same transaction as the propose). */
    private void publishAudit(String partnerCode, String payload) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(AGGREGATE_TYPE, partnerCode, SYSTEM_ACTOR, null,
                    EVENT_TYPE_PROPOSED, null,
                    payload.getBytes(StandardCharsets.UTF_8));
        }
    }
}
