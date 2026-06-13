package com.gme.pay.registry.commercial;

import static com.gme.pay.registry.commercial.CommercialValidation.badRequest;
import static com.gme.pay.registry.commercial.CommercialValidation.requireOnboarding;
import static com.gme.pay.registry.commercial.CommercialValidation.requirePartner;

import com.gme.pay.contracts.ContractCommand;
import com.gme.pay.contracts.ContractView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 6 — owns the {@code partner_contract} child aggregate (V021) behind
 * the wizard's step-6 commercial endpoints (see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6 — Commercial Terms").
 *
 * <p>The contract section of a step-6 PATCH is a <b>full-state replace</b> —
 * SCD-6 paired write per ADR-010, same discipline as {@link FxConfigService}.
 * One audit row per write ({@code aggregateType="partner_contract"},
 * ADR-007). Mind the two date axes (V021 header): {@code effectiveFrom} /
 * {@code effectiveTo} is the commercial TERM; {@code validFrom} /
 * {@code validTo} is the row version's business time.
 */
@Service
public class ContractService {

    /** Aggregate-type discriminator on audit rows for contract mutations. */
    public static final String AGGREGATE_TYPE = "partner_contract";

    /** Audit verb for the step-6 contract full-state replace. */
    public static final String EVENT_TYPE_SAVED = "PARTNER_CONTRACT_SAVED";

    /** V021 CHECK roster for refund_chargeback_policy. */
    static final Set<String> POLICIES = Set.of("PARTNER_BEARS", "MERCHANT_BEARS", "SHARED");

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final ContractRepository contractRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public ContractService(ContractRepository contractRepository,
                           PartnerRepository partnerRepository,
                           ObjectProvider<AuditLogService> auditLogProvider) {
        this.contractRepository = contractRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Full-state replace of the contract row on a draft partner (wizard
     * step-6 "Next", contract section).
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner has left {@code ONBOARDING}; 400 on
     *         validation failure (missing effectiveFrom, effectiveTo before
     *         effectiveFrom, unknown policy, negative notice period,
     *         over-length termination reason).
     */
    @Transactional
    public ContractView upsertContract(String partnerCode, ContractCommand cmd, String actor) {
        if (cmd == null) {
            throw badRequest("contract section required");
        }
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);
        requireOnboarding(partner, partnerCode, "step-6 contract");
        validate(cmd);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<ContractEntity> priorOpt =
                contractRepository.findCurrentByPartnerId(partner.getId());

        ContractEntity fresh = new ContractEntity();
        fresh.setPartnerId(partner.getId());
        fresh.setEffectiveFrom(cmd.effectiveFrom());
        fresh.setEffectiveTo(cmd.effectiveTo());
        fresh.setAutoRenewal(Boolean.TRUE.equals(cmd.autoRenewal()));
        fresh.setNoticePeriodDays(cmd.noticePeriodDays());
        fresh.setRefundChargebackPolicy(
                cmd.refundChargebackPolicy() == null || cmd.refundChargebackPolicy().isBlank()
                        ? null : cmd.refundChargebackPolicy());
        fresh.setTerminationReason(
                cmd.terminationReason() == null || cmd.terminationReason().isBlank()
                        ? null : cmd.terminationReason());
        // Slice 8 / V025: countersign instant feeding the activation gate.
        // MICROS truncation so the stored TIMESTAMP equals the in-memory value
        // on both engines (same discipline as recorded_at).
        fresh.setSignedAt(cmd.signedAt() == null
                ? null : cmd.signedAt().truncatedTo(ChronoUnit.MICROS));

        ContractEntity saved = pairedWrite(priorOpt.orElse(null), fresh, now);
        publishAudit(partnerCode, actor,
                priorOpt.map(CommercialJson::canonicalContract).orElse(null),
                CommercialJson.canonicalContract(saved));
        return saved.toView();
    }

    /**
     * The CURRENT contract for the given partner code.
     *
     * @throws ResponseStatusException 404 when the partner code is unknown OR
     *         when the partner has no contract row yet.
     */
    @Transactional(readOnly = true)
    public ContractView currentContract(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);
        return contractRepository.findCurrentByPartnerId(partner.getId())
                .map(ContractEntity::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no contract for partner '" + partnerCode + "'"));
    }

    // -------------------------- Helpers --------------------------------------

    /** SCD-6 paired write — same ordering discipline as {@link FxConfigService}. */
    private ContractEntity pairedWrite(ContractEntity prior, ContractEntity fresh, Instant now) {
        fresh.setRecordedAt(now);
        if (prior != null) {
            fresh.setValidFrom(prior.getValidFrom());
            fresh.setValidTo(prior.getValidTo());
            prior.setSupersededAt(now);
            contractRepository.saveAndFlush(prior);
        } else {
            fresh.setValidFrom(now);
        }
        return contractRepository.saveAndFlush(fresh);
    }

    /** ADR-007 audit row, same-transaction (commits iff the contract write commits). */
    private void publishAudit(String partnerCode, String actor, byte[] before, byte[] after) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(
                    AGGREGATE_TYPE,
                    partnerCode,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null,
                    EVENT_TYPE_SAVED,
                    before,
                    after);
        }
    }

    /** Field-format + cross-field validation, whole payload before any row is touched. */
    private static void validate(ContractCommand cmd) {
        if (cmd.effectiveFrom() == null) {
            throw badRequest("contract.effectiveFrom is required (ISO-8601 date)");
        }
        if (cmd.effectiveTo() != null && cmd.effectiveTo().isBefore(cmd.effectiveFrom())) {
            throw badRequest("contract.effectiveTo (" + cmd.effectiveTo()
                    + ") must not be before contract.effectiveFrom ("
                    + cmd.effectiveFrom() + ")");
        }
        if (cmd.noticePeriodDays() != null && cmd.noticePeriodDays() < 0) {
            throw badRequest("contract.noticePeriodDays must not be negative, was: "
                    + cmd.noticePeriodDays());
        }
        if (cmd.refundChargebackPolicy() != null && !cmd.refundChargebackPolicy().isBlank()
                && !POLICIES.contains(cmd.refundChargebackPolicy())) {
            throw badRequest("contract.refundChargebackPolicy must be one of " + POLICIES
                    + ", was: " + cmd.refundChargebackPolicy());
        }
        if (cmd.terminationReason() != null && cmd.terminationReason().length() > 200) {
            throw badRequest("contract.terminationReason must be at most 200 characters");
        }
    }
}
