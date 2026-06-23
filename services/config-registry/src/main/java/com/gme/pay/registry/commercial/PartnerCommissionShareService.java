package com.gme.pay.registry.commercial;

import static com.gme.pay.registry.commercial.CommercialValidation.badRequest;
import static com.gme.pay.registry.commercial.CommercialValidation.normalizeScale4;
import static com.gme.pay.registry.commercial.CommercialValidation.requirePartner;

import com.gme.pay.contracts.PartnerCommissionShareCommand;
import com.gme.pay.contracts.PartnerCommissionShareView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns the {@code partner_commission_share} aggregate (V031) behind the
 * wallet-partner setup commission endpoints — the configurable GME ↔ partner
 * split of GME's commission. There is <b>no fixed share</b>; every partner can
 * carry its own {@code partnerSharePct}.
 *
 * <h2>Bulk-replace semantics</h2>
 *
 * <p>A save is a <b>bulk replace</b> of the partner's whole share set, mirroring
 * {@link FeeScheduleService}: inside one transaction every current row is
 * superseded ({@code superseded_at = now}) and the new set is inserted
 * ({@code recorded_at = now}), both halves sharing one MICROS-truncated instant
 * (SCD-6, ADR-010). An empty list clears all rows; {@code null} is a 400.
 *
 * <h2>No onboarding gate</h2>
 *
 * <p>Unlike the step-6 fee schedule (draft-only), commission terms are
 * renegotiated over a partner's life, so a replace is allowed in any lifecycle
 * state — only that the partner exists is required.
 *
 * <h2>Key shape</h2>
 *
 * <p>At most one row per ({@code schemeId}, {@code direction}) pair per save —
 * {@code null} (= all) is part of the key. Duplicates are a 400.
 */
@Service
public class PartnerCommissionShareService {

    public static final String AGGREGATE_TYPE = "partner_commission_share";
    public static final String EVENT_TYPE_REPLACED = "PARTNER_COMMISSION_SHARES_REPLACED";

    static final Set<String> DIRECTIONS = Set.of("INBOUND", "OUTBOUND", "BOTH");

    private static final String DEFAULT_ACTOR = "system";

    private final PartnerCommissionShareRepository repository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerCommissionShareService(PartnerCommissionShareRepository repository,
                                         PartnerRepository partnerRepository,
                                         ObjectProvider<AuditLogService> auditLogProvider) {
        this.repository = repository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the commission-share set for a partner.
     *
     * @throws ResponseStatusException 404 when the partner code is unknown; 400
     *         on validation failure (bad direction roster / share out of [0,1] /
     *         over-scale value / duplicate (schemeId, direction) pair).
     */
    @Transactional
    public List<PartnerCommissionShareView> replaceCommissionShares(
            String partnerCode, List<PartnerCommissionShareCommand> shares, String actor) {
        if (shares == null) {
            throw badRequest(
                    "commissionShares is required (send an empty list to clear all rows)");
        }
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);

        for (int i = 0; i < shares.size(); i++) {
            validate(shares.get(i), i);
        }
        validateNoDuplicateKeys(shares);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<PartnerCommissionShareEntity> prior =
                repository.findCurrentByPartnerId(partner.getId());
        byte[] before = prior.isEmpty() ? null : canonical(prior);

        if (!prior.isEmpty()) {
            for (PartnerCommissionShareEntity p : prior) {
                p.setSupersededAt(now);
            }
            repository.saveAllAndFlush(prior);
        }

        List<PartnerCommissionShareEntity> fresh = new ArrayList<>(shares.size());
        for (PartnerCommissionShareCommand cmd : shares) {
            PartnerCommissionShareEntity e = new PartnerCommissionShareEntity();
            e.setPartnerId(partner.getId());
            e.setSchemeId(blankToNull(cmd.schemeId()));
            e.setDirection(blankToNull(cmd.direction()));
            e.setPartnerSharePct(normalizeScale4(cmd.partnerSharePct()));
            e.setRecordedAt(now);
            e.setValidFrom(now);
            fresh.add(e);
        }
        List<PartnerCommissionShareEntity> saved = repository.saveAllAndFlush(fresh);

        publishAudit(partnerCode, actor, before, saved.isEmpty() ? null : canonical(saved));
        return saved.stream().map(PartnerCommissionShareEntity::toView).toList();
    }

    /**
     * The CURRENT commission-share set for the partner (empty list when none).
     *
     * @throws ResponseStatusException 404 when the partner code is unknown.
     */
    @Transactional(readOnly = true)
    public List<PartnerCommissionShareView> currentCommissionShares(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);
        return repository.findCurrentByPartnerId(partner.getId()).stream()
                .map(PartnerCommissionShareEntity::toView)
                .toList();
    }

    // -------------------------- Helpers --------------------------------------

    private void publishAudit(String partnerCode, String actor, byte[] before, byte[] after) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(
                    AGGREGATE_TYPE,
                    partnerCode,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null,
                    EVENT_TYPE_REPLACED,
                    before,
                    after);
        }
    }

    private static void validate(PartnerCommissionShareCommand cmd, int index) {
        String at = "commissionShares[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.schemeId() != null && !cmd.schemeId().isBlank() && cmd.schemeId().length() > 40) {
            throw badRequest(at + ".schemeId must be at most 40 characters");
        }
        if (cmd.direction() != null && !cmd.direction().isBlank()
                && !DIRECTIONS.contains(cmd.direction())) {
            throw badRequest(at + ".direction must be one of " + DIRECTIONS
                    + " (or null for all directions), was: " + cmd.direction());
        }
        validateSharePct(at + ".partnerSharePct", cmd.partnerSharePct());
    }

    /** Partner share fraction: required, in [0,1], at most 4 decimal places. */
    private static void validateSharePct(String field, BigDecimal value) {
        if (value == null) {
            throw badRequest(field + " is required");
        }
        if (value.signum() < 0) {
            throw badRequest(field + " must be >= 0, was: " + value.toPlainString());
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            throw badRequest(field + " must be <= 1 (a fraction, e.g. 0.3000 = 30%), was: "
                    + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > 4) {
            throw badRequest(field + " must have at most 4 decimal places, was: "
                    + value.toPlainString());
        }
    }

    private static void validateNoDuplicateKeys(List<PartnerCommissionShareCommand> shares) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < shares.size(); i++) {
            PartnerCommissionShareCommand cmd = shares.get(i);
            String key = (blankToNull(cmd.schemeId()) == null ? "*" : cmd.schemeId())
                    + ":" + (blankToNull(cmd.direction()) == null ? "*" : cmd.direction());
            if (!seen.add(key)) {
                throw badRequest("commissionShares[" + i + "]: duplicate (schemeId, direction)"
                        + " pair " + key + " — at most one row per pair");
            }
        }
    }

    /** Deterministic snapshot for the ADR-007 audit chain (one line per row, id order). */
    private static byte[] canonical(List<PartnerCommissionShareEntity> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            PartnerCommissionShareEntity r = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"schemeId\":")
                    .append(r.getSchemeId() == null ? "null" : "\"" + r.getSchemeId() + "\"")
                    .append(",\"direction\":")
                    .append(r.getDirection() == null ? "null" : "\"" + r.getDirection() + "\"")
                    .append(",\"partnerSharePct\":\"")
                    .append(r.getPartnerSharePct() == null ? "0" : r.getPartnerSharePct().toPlainString())
                    .append("\"}");
        }
        return sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
