package com.gme.pay.registry.commercial;

import static com.gme.pay.registry.commercial.CommercialValidation.badRequest;
import static com.gme.pay.registry.commercial.CommercialValidation.normalizeScale4;
import static com.gme.pay.registry.commercial.CommercialValidation.requireOnboarding;
import static com.gme.pay.registry.commercial.CommercialValidation.requirePartner;
import static com.gme.pay.registry.commercial.CommercialValidation.validateBps;
import static com.gme.pay.registry.commercial.CommercialValidation.validateMoney;

import com.gme.pay.contracts.FeeScheduleCommand;
import com.gme.pay.contracts.FeeScheduleView;
import com.gme.pay.contracts.FeeTier;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 6 — owns the {@code partner_fee_schedule} child aggregate (V018)
 * behind the wizard's step-6 commercial endpoints (see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6 — Commercial Terms").
 *
 * <h2>Bulk-replace semantics</h2>
 *
 * <p>The wizard's contract is "send the full fee panel on every save", so the
 * fee section of a step-6 PATCH is a <b>bulk replace</b>: inside one
 * transaction every current fee row for the partner is superseded
 * ({@code superseded_at = now}) and the new set is inserted
 * ({@code recorded_at = now}), both halves sharing the same MICROS-truncated
 * instant — the SCD-6 paired-write discipline of
 * {@code PartnerBankAccountService} (ADR-010). An empty list clears all fee
 * rows; {@code null} is rejected with 400 (the composite facade translates a
 * null SECTION to "untouched" before calling here).
 *
 * <h2>Key shape</h2>
 *
 * <p>At most one row per ({@code schemeId}, {@code direction}) pair per save
 * — {@code null} means "applies to all" and is part of the key (one wildcard
 * row allowed). Duplicates are a 400 carrying the offending
 * {@code feeSchedules[i]} index so the multi-row editor can highlight the
 * row.
 *
 * <h2>Tier table</h2>
 *
 * <p>Tiers are validated (both fields present, money/bps envelopes, strictly
 * ascending {@code fromVolumeUsd}, at most {@value #MAX_TIERS} bands), scale-4
 * normalised and stored CANONICALLY via {@link FeeTierTableJson} so the audit
 * bytes stay deterministic.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per replace, {@code aggregateType="partner_fee_schedule"},
 * keyed by the partner business code, BEFORE/AFTER =
 * {@link CommercialJson#canonicalFeeSchedules} snapshots, published inside the
 * same transaction through the {@link ObjectProvider}-resolved
 * {@link AuditLogService} — the same wiring contract as
 * {@code PartnerBankAccountService}.
 */
@Service
public class FeeScheduleService {

    /** Aggregate-type discriminator on audit rows for fee-schedule mutations. */
    public static final String AGGREGATE_TYPE = "partner_fee_schedule";

    /** Audit verb for the step-6 fee bulk replace. */
    public static final String EVENT_TYPE_REPLACED = "PARTNER_FEE_SCHEDULES_REPLACED";

    /** V018 CHECK roster for direction (same roster as the V017 rule rows). */
    static final Set<String> DIRECTIONS = Set.of("INBOUND", "OUTBOUND", "BOTH");

    /** Sanity ceiling on tier bands per row (keeps tier_table_json bounded). */
    static final int MAX_TIERS = 20;

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final FeeScheduleRepository feeScheduleRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public FeeScheduleService(FeeScheduleRepository feeScheduleRepository,
                              PartnerRepository partnerRepository,
                              ObjectProvider<AuditLogService> auditLogProvider) {
        this.feeScheduleRepository = feeScheduleRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the fee-schedule set on a draft partner (wizard step-6
     * "Next", fee section).
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner has left {@code ONBOARDING}; 400 on
     *         validation failure (bad direction roster / negative fee /
     *         over-scale value / duplicate (schemeId, direction) pair /
     *         malformed tier table — message carries the offending
     *         {@code feeSchedules[i]} index).
     */
    @Transactional
    public List<FeeScheduleView> replaceDraftFeeSchedules(String partnerCode,
                                                          List<FeeScheduleCommand> fees,
                                                          String actor) {
        if (fees == null) {
            throw badRequest(
                    "feeSchedules is required (send an empty list to clear all fee rows)");
        }
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);
        requireOnboarding(partner, partnerCode, "step-6 fee-schedule");

        // Validate the WHOLE payload before touching any row — a bad element
        // must not leave the set half-replaced (fail fast, side-effect free).
        for (int i = 0; i < fees.size(); i++) {
            validate(fees.get(i), i);
        }
        validateNoDuplicateKeys(fees);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<FeeScheduleEntity> prior =
                feeScheduleRepository.findCurrentByPartnerId(partner.getId());
        byte[] before = prior.isEmpty() ? null : CommercialJson.canonicalFeeSchedules(prior);

        // Supersede the prior current set first (flush forces the UPDATEs out
        // before the INSERTs — same SCD-6 write ordering as PartnerBankAccountService).
        if (!prior.isEmpty()) {
            for (FeeScheduleEntity p : prior) {
                p.setSupersededAt(now);
            }
            feeScheduleRepository.saveAllAndFlush(prior);
        }

        List<FeeScheduleEntity> fresh = new ArrayList<>(fees.size());
        for (FeeScheduleCommand cmd : fees) {
            FeeScheduleEntity e = new FeeScheduleEntity();
            e.setPartnerId(partner.getId());
            e.setSchemeId(blankToNull(cmd.schemeId()));
            e.setDirection(blankToNull(cmd.direction()));
            e.setFixedFeeUsd(normalizeScale4(
                    cmd.fixedFeeUsd() == null ? BigDecimal.ZERO : cmd.fixedFeeUsd()));
            e.setBpsFee(normalizeScale4(
                    cmd.bpsFee() == null ? BigDecimal.ZERO : cmd.bpsFee()));
            e.setTierTableJson(FeeTierTableJson.canonical(normalizeTiers(cmd.tiers())));
            e.setRecordedAt(now);
            e.setValidFrom(now);
            fresh.add(e);
        }
        List<FeeScheduleEntity> saved = feeScheduleRepository.saveAllAndFlush(fresh);

        publishAudit(partnerCode, actor, before, CommercialJson.canonicalFeeSchedules(saved));
        return saved.stream().map(FeeScheduleEntity::toView).toList();
    }

    /**
     * The CURRENT fee-schedule set for the given partner code (empty list for
     * a partner with no fee rows yet — multi-row child aggregates rehydrate
     * to an empty editor, same contract as bank accounts).
     *
     * @throws ResponseStatusException 404 when the partner code is unknown.
     */
    @Transactional(readOnly = true)
    public List<FeeScheduleView> currentFeeSchedules(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);
        return feeScheduleRepository.findCurrentByPartnerId(partner.getId()).stream()
                .map(FeeScheduleEntity::toView)
                .toList();
    }

    /** bps → fraction divisor: bpsFee is in basis points (1 bps = 0.0001). */
    private static final BigDecimal BPS_DIVISOR = new BigDecimal("10000");

    /**
     * Resolve the effective GME→partner service fee (USD) for a
     * ({@code partnerCode}, {@code schemeId}, {@code direction}, {@code amountUsd}) —
     * SETTLEMENT_FLOW_SPEC §7.4 ("wire partner_fee_schedule into pricing"). This is
     * the read-time analogue of {@code MerchantFeeScheduleService.resolveRate}: the
     * quote-issuer (and an admin "effective fee" preview) call it to turn the stored
     * fee schedule into a single USD amount.
     *
     * <p>Match specificity (most specific wins): an exact {@code schemeId} beats the
     * {@code scheme = null} wildcard, and an exact {@code direction} beats
     * {@code BOTH}/{@code null}. A row is a candidate only if BOTH dimensions match
     * (exact or wildcard); the highest combined specificity wins.
     *
     * <p>Fee = {@code fixedFeeUsd + amountUsd × bps / 10000}, where {@code bps} is the
     * applicable tier's {@code bpsOverride} (the highest band whose
     * {@code fromVolumeUsd ≤ amountUsd}) or the row's flat {@code bpsFee} when no tier
     * applies. Lenient like the merchant-fee resolver: unknown partner / no matching
     * row ⇒ {@link Optional#empty()} (the caller defaults rather than failing).
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> resolveServiceFee(String partnerCode, String schemeId,
                                                  String direction, BigDecimal amountUsd) {
        if (partnerCode == null || partnerCode.isBlank()) {
            return Optional.empty();
        }
        Optional<PartnerEntity> partner = partnerRepository.findCurrentByPartnerCode(partnerCode);
        if (partner.isEmpty()) {
            return Optional.empty();
        }
        FeeScheduleEntity best = bestMatch(
                feeScheduleRepository.findCurrentByPartnerId(partner.get().getId()),
                blankToNull(schemeId), blankToNull(direction));
        if (best == null) {
            return Optional.empty();
        }
        BigDecimal volume = amountUsd == null ? BigDecimal.ZERO : amountUsd;
        BigDecimal bps = effectiveBps(best, volume);
        BigDecimal fixed = best.getFixedFeeUsd() == null ? BigDecimal.ZERO : best.getFixedFeeUsd();
        BigDecimal variable = bps.multiply(volume).divide(BPS_DIVISOR, 4, RoundingMode.HALF_UP);
        return Optional.of(fixed.add(variable));
    }

    /**
     * Pick the most specific fee row for the given scheme/direction, or {@code null}
     * when none match. Scheme/direction each score 2 (exact), 1 (wildcard: null, plus
     * {@code BOTH} for direction), or excludes the row (mismatch); the highest
     * combined score wins (scheme breaks ties).
     */
    private static FeeScheduleEntity bestMatch(List<FeeScheduleEntity> rows,
                                               String schemeId, String direction) {
        FeeScheduleEntity best = null;
        int bestScore = -1;
        int bestSchemeScore = -1;
        for (FeeScheduleEntity r : rows) {
            int s = schemeScore(r.getSchemeId(), schemeId);
            int d = directionScore(r.getDirection(), direction);
            if (s == 0 || d == 0) {
                continue; // a dimension mismatched — not a candidate
            }
            int total = s + d;
            if (total > bestScore || (total == bestScore && s > bestSchemeScore)) {
                best = r;
                bestScore = total;
                bestSchemeScore = s;
            }
        }
        return best;
    }

    /** 2 = exact scheme, 1 = wildcard (row scheme null), 0 = mismatch (exclude). */
    private static int schemeScore(String rowScheme, String requested) {
        if (rowScheme == null) {
            return 1;
        }
        return requested != null && rowScheme.equalsIgnoreCase(requested) ? 2 : 0;
    }

    /** 2 = exact direction, 1 = wildcard (null or BOTH), 0 = mismatch (exclude). */
    private static int directionScore(String rowDirection, String requested) {
        if (rowDirection == null || "BOTH".equalsIgnoreCase(rowDirection)) {
            return 1;
        }
        return requested != null && rowDirection.equalsIgnoreCase(requested) ? 2 : 0;
    }

    /**
     * The bps that applies at {@code volume}: the highest tier band whose
     * {@code fromVolumeUsd ≤ volume}, else the row's flat {@code bpsFee}. Tiers are
     * stored ascending by {@code fromVolumeUsd} (write-side validated).
     */
    private static BigDecimal effectiveBps(FeeScheduleEntity row, BigDecimal volume) {
        BigDecimal flat = row.getBpsFee() == null ? BigDecimal.ZERO : row.getBpsFee();
        List<FeeTier> tiers = FeeTierTableJson.parse(row.getTierTableJson());
        if (tiers == null || tiers.isEmpty()) {
            return flat;
        }
        BigDecimal applicable = null;
        for (FeeTier t : tiers) {
            if (t.fromVolumeUsd() != null && t.fromVolumeUsd().compareTo(volume) <= 0) {
                applicable = t.bpsOverride(); // ascending => last match is the highest band ≤ volume
            }
        }
        return applicable != null ? applicable : flat;
    }

    // -------------------------- Helpers --------------------------------------

    /** ADR-007 audit row, same-transaction (commits iff the fee write commits). */
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

    /** Field-format validation for one fee row; {@code at} names the element on failure. */
    private static void validate(FeeScheduleCommand cmd, int index) {
        String at = "feeSchedules[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.schemeId() != null && !cmd.schemeId().isBlank()
                && cmd.schemeId().length() > 40) {
            throw badRequest(at + ".schemeId must be at most 40 characters");
        }
        if (cmd.direction() != null && !cmd.direction().isBlank()
                && !DIRECTIONS.contains(cmd.direction())) {
            throw badRequest(at + ".direction must be one of " + DIRECTIONS
                    + " (or null for all directions), was: " + cmd.direction());
        }
        validateMoney(at + ".fixedFeeUsd", cmd.fixedFeeUsd(), false);
        validateBps(at + ".bpsFee", cmd.bpsFee());
        validateTiers(at, cmd.tiers());
    }

    /** Tier-table validation: both fields, envelopes, strict ascent, size cap. */
    private static void validateTiers(String at, List<FeeTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return;
        }
        if (tiers.size() > MAX_TIERS) {
            throw badRequest(at + ".tiers must have at most " + MAX_TIERS + " bands");
        }
        BigDecimal previousFrom = null;
        for (int t = 0; t < tiers.size(); t++) {
            FeeTier tier = tiers.get(t);
            String tierAt = at + ".tiers[" + t + "]";
            if (tier == null) {
                throw badRequest(tierAt + " must be an object");
            }
            if (tier.fromVolumeUsd() == null) {
                throw badRequest(tierAt + ".fromVolumeUsd is required");
            }
            if (tier.bpsOverride() == null) {
                throw badRequest(tierAt + ".bpsOverride is required");
            }
            validateMoney(tierAt + ".fromVolumeUsd", tier.fromVolumeUsd(), false);
            validateBps(tierAt + ".bpsOverride", tier.bpsOverride());
            if (previousFrom != null && tier.fromVolumeUsd().compareTo(previousFrom) <= 0) {
                throw badRequest(tierAt + ".fromVolumeUsd must be strictly greater than the"
                        + " previous band's (" + previousFrom.toPlainString() + "), was: "
                        + tier.fromVolumeUsd().toPlainString());
            }
            previousFrom = tier.fromVolumeUsd();
        }
    }

    /**
     * At most one row per (schemeId, direction) pair per save — null is part
     * of the key (one wildcard row allowed), blank normalises to null first.
     */
    private static void validateNoDuplicateKeys(List<FeeScheduleCommand> fees) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fees.size(); i++) {
            FeeScheduleCommand cmd = fees.get(i);
            String key = (blankToNull(cmd.schemeId()) == null ? "*" : cmd.schemeId())
                    + ":" + (blankToNull(cmd.direction()) == null ? "*" : cmd.direction());
            if (!seen.add(key)) {
                throw badRequest("feeSchedules[" + i + "]: duplicate (schemeId, direction)"
                        + " pair " + key + " — at most one fee row per pair");
            }
        }
    }

    /** Scale-4 normalise tier values so the stored canonical JSON is deterministic. */
    private static List<FeeTier> normalizeTiers(List<FeeTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        return tiers.stream()
                .map(t -> new FeeTier(
                        normalizeScale4(t.fromVolumeUsd()),
                        normalizeScale4(t.bpsOverride())))
                .toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
