package com.gme.pay.registry.rule;

import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.RuleCommand;
import com.gme.pay.contracts.RuleView;
import com.gme.pay.domain.Direction;
import com.gme.pay.domain.Rule;
import com.gme.pay.errors.ApiException;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 6 — owns the {@code partner_rule} child aggregate (V017) behind the
 * wizard's step-6 rule-editor endpoints (see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6 — Commercial Terms").
 *
 * <h2>Bulk-replace semantics</h2>
 *
 * <p>The wizard's contract is "send the full rule set on every save", so a
 * PATCH is a <b>bulk replace</b>: inside one transaction every current
 * {@code partner_rule} row for the partner is superseded
 * ({@code superseded_at = now}) and the new set is inserted
 * ({@code recorded_at = now}), both halves sharing the same MICROS-truncated
 * instant — the SCD-6 paired-write discipline of
 * {@code PartnerBankAccountService} (ADR-010). Sending an empty list clears
 * all rules; {@code null} is a 400.
 *
 * <h2>Margin invariant (RATE-04 §11)</h2>
 *
 * <p>Every element is checked through the existing lib-domain
 * {@link Rule#validate()} before any row is touched: cross-border rules need
 * {@code mA + mB >= 2%}; same-currency rules must carry zero combined margin
 * (the USD pool is short-circuited). Whether a rule is cross-border is decided
 * by the partner's V016 currency split — {@code settle_a_ccy} vs
 * {@code collection_ccy}, falling back to the legacy
 * {@code settlement_currency} for either side that is still null (the ADR-013
 * Expand-phase contract).
 *
 * <h2>Margins / money ({@code docs/MONEY_CONVENTION.md})</h2>
 *
 * <p>{@code mA} / {@code mB} are decimal FRACTIONS ({@code 0.0150} = 1.50%),
 * NUMERIC(7,4); {@code serviceChargeUsd} is major-USD-units money,
 * NUMERIC(19,4). All three are normalised to scale 4 before persisting so
 * stored == in-memory on both engines and the audit snapshot bytes are
 * deterministic.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_rule"}, keyed by
 * the partner business code, BEFORE/AFTER = {@link RuleJson} canonical
 * snapshots, published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code PartnerStore} / {@code PrefundingConfigService}.
 */
@Service
public class RuleService {

    /** Aggregate-type discriminator on audit rows for rule mutations. */
    public static final String AGGREGATE_TYPE = "partner_rule";

    /** Audit verb for the step-6 bulk replace. */
    public static final String EVENT_TYPE_REPLACED = "PARTNER_RULES_REPLACED";

    /** V017 CHECK roster for direction. */
    static final Set<String> DIRECTIONS = Set.of("INBOUND", "OUTBOUND", "BOTH");

    /** NUMERIC(7,4): at most 4 decimal places, at most 3 integer digits. */
    static final int MARGIN_SCALE = 4;
    static final int MARGIN_MAX_INTEGER_DIGITS = 3;

    /** NUMERIC(19,4): at most 4 decimal places, at most 15 integer digits. */
    static final int MONEY_SCALE = 4;
    static final int MONEY_MAX_INTEGER_DIGITS = 15;

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final RuleRepository ruleRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public RuleService(RuleRepository ruleRepository,
                       PartnerRepository partnerRepository,
                       ObjectProvider<AuditLogService> auditLogProvider) {
        this.ruleRepository = ruleRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the rule set on a draft partner (wizard step-6 "Next").
     *
     * @param partnerCode the human-facing business code routing the PATCH.
     * @param rules       the FULL desired set; empty clears, {@code null} is a 400.
     * @param actor       the operator (X-Actor header); {@code "system"} when absent.
     * @return the freshly-inserted current set as canonical {@link RuleView}s.
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner is no longer in {@code ONBOARDING}
     *         (post-activation rule changes ride the change_request approval
     *         flow with the Slice 8 FSM); 400 on any validation failure —
     *         including the lib-domain margin invariant, reported with the
     *         offending {@code rules[i]} index so the multi-row editor can
     *         highlight the row.
     */
    @Transactional
    public List<RuleView> replaceDraftRules(String partnerCode,
                                            List<RuleCommand> rules,
                                            String actor) {
        if (rules == null) {
            throw badRequest("rules is required (send an empty list to clear all rules)");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-6 rule edits are only permitted while ONBOARDING"
                            + " (post-activation rule changes require the change_request"
                            + " approval flow)");
        }
        // Validate the WHOLE payload before touching any row — a bad element
        // must not leave the set half-replaced (fail fast, side-effect free).
        for (int i = 0; i < rules.size(); i++) {
            validate(rules.get(i), i);
        }
        validateNoDuplicateKeys(rules);
        validateMarginInvariant(partner, partnerCode, rules);

        // One transaction-time instant shared by both halves of the paired
        // write (supersede + insert), truncated to MICROS — see PartnerStore.save.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        List<RuleEntity> prior = ruleRepository.findCurrentByPartnerId(partner.getId());
        byte[] before = prior.isEmpty() ? null : RuleJson.canonical(prior);

        // Supersede the prior current set first (flush forces the UPDATEs out
        // before the INSERTs so the V017 partial-unique emulation never sees
        // two current rows per key mid-transaction — same SCD-6 ordering as
        // PartnerBankAccountService).
        if (!prior.isEmpty()) {
            for (RuleEntity p : prior) {
                p.setSupersededAt(now);
            }
            ruleRepository.saveAllAndFlush(prior);
        }

        // Insert the new current set. IDENTITY ids are assigned at flush; the
        // RETURNED managed entities carry them, which the audit AFTER snapshot
        // and the response views both need.
        List<RuleEntity> fresh = new ArrayList<>(rules.size());
        for (RuleCommand cmd : rules) {
            fresh.add(toEntity(partner.getId(), cmd, now));
        }
        List<RuleEntity> saved = ruleRepository.saveAllAndFlush(fresh);

        publishAudit(partnerCode, actor, before, RuleJson.canonical(saved));

        return saved.stream().map(RuleEntity::toView).toList();
    }

    /**
     * The CURRENT rule set for the given partner code (no historical rows).
     *
     * @throws ResponseStatusException 404 when no current partner row matches —
     *         "partner exists with zero rules" returns an empty list, only an
     *         unknown code 404s.
     */
    @Transactional(readOnly = true)
    public List<RuleView> currentRules(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return ruleRepository.findCurrentByPartnerId(partner.getId()).stream()
                .map(RuleEntity::toView)
                .toList();
    }

    // -------------------------- Helpers --------------------------------------

    private PartnerEntity requirePartner(String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    /** ADR-007 audit row, same-transaction (commits iff the business write commits). */
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

    /** Build a fresh current row from one validated command. */
    private static RuleEntity toEntity(Long partnerId, RuleCommand cmd, Instant now) {
        RuleEntity e = new RuleEntity();
        e.setPartnerId(partnerId);
        e.setSchemeId(cmd.schemeId());
        e.setDirection(cmd.direction());
        e.setMA(normalizeScale4(cmd.mA()));
        e.setMB(normalizeScale4(cmd.mB()));
        e.setServiceChargeUsd(normalizeScale4(
                cmd.serviceChargeUsd() == null ? BigDecimal.ZERO : cmd.serviceChargeUsd()));
        e.setRecordedAt(now);
        // Business time starts at capture — the wizard does not back-date rules.
        e.setValidFrom(now);
        return e;
    }

    /**
     * Field-format validation for one rule element. Index-qualified messages
     * ({@code rules[2].direction ...}) so the multi-row editor can map the 400
     * to the offending row. The cross-element checks (duplicate keys, margin
     * invariant) run separately once every element passes.
     */
    private static void validate(RuleCommand cmd, int index) {
        String at = "rules[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.schemeId() == null || cmd.schemeId().isBlank()) {
            throw badRequest(at + ".schemeId is required");
        }
        if (cmd.schemeId().length() > 40) {
            throw badRequest(at + ".schemeId must be at most 40 characters");
        }
        if (cmd.direction() == null || !DIRECTIONS.contains(cmd.direction())) {
            throw badRequest(at + ".direction must be one of " + DIRECTIONS
                    + ", was: " + cmd.direction());
        }
        validateMargin(at + ".mA", cmd.mA());
        validateMargin(at + ".mB", cmd.mB());
        validateServiceCharge(at + ".serviceChargeUsd", cmd.serviceChargeUsd());
    }

    /** One margin against the NUMERIC(7,4) envelope: required, >= 0, <= 4 dp. */
    private static void validateMargin(String field, BigDecimal value) {
        if (value == null) {
            throw badRequest(field + " is required (a decimal fraction, e.g. 0.0150 = 1.50%)");
        }
        if (value.signum() < 0) {
            throw badRequest(field + " must not be negative, was: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > MARGIN_SCALE) {
            throw badRequest(field + " must have at most " + MARGIN_SCALE
                    + " decimal places (NUMERIC(7,4)), was: " + value.toPlainString());
        }
        if (value.precision() - value.scale() > MARGIN_MAX_INTEGER_DIGITS) {
            throw badRequest(field + " exceeds NUMERIC(7,4) (at most "
                    + MARGIN_MAX_INTEGER_DIGITS + " integer digits), was: "
                    + value.toPlainString());
        }
    }

    /** Service charge against the MONEY_CONVENTION NUMERIC(19,4) envelope; null = default 0. */
    private static void validateServiceCharge(String field, BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            throw badRequest(field + " must not be negative, was: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw badRequest(field + " must have at most " + MONEY_SCALE
                    + " decimal places (NUMERIC(19,4)), was: " + value.toPlainString());
        }
        if (value.precision() - value.scale() > MONEY_MAX_INTEGER_DIGITS) {
            throw badRequest(field + " exceeds NUMERIC(19,4) (at most "
                    + MONEY_MAX_INTEGER_DIGITS + " integer digits), was: "
                    + value.toPlainString());
        }
    }

    /**
     * At most one rule per (schemeId, direction) across the payload (the
     * payload IS the full current state under replace semantics, so the
     * payload-level check is the V017 partial-unique invariant).
     */
    private static void validateNoDuplicateKeys(List<RuleCommand> rules) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            RuleCommand cmd = rules.get(i);
            if (!seen.add(cmd.schemeId() + ":" + cmd.direction())) {
                throw badRequest("rules[" + i + "]: duplicate rule for scheme "
                        + cmd.schemeId() + " direction " + cmd.direction()
                        + " (at most one rule per scheme and direction)");
            }
        }
    }

    /**
     * Run every element through the existing lib-domain {@link Rule#validate()}
     * margin invariant (RATE-04 §11). The settle/collection currencies come
     * from the partner's V016 split, falling back per-side to the legacy
     * {@code settlement_currency} (the Expand-phase mirror) so partners that
     * have not yet stated a split are treated as same-currency.
     */
    private static void validateMarginInvariant(PartnerEntity partner, String partnerCode,
                                                List<RuleCommand> rules) {
        String settleA = partner.getSettleACcy() != null
                ? partner.getSettleACcy() : partner.getSettlementCurrency();
        String collection = partner.getCollectionCcy() != null
                ? partner.getCollectionCcy() : partner.getSettlementCurrency();
        for (int i = 0; i < rules.size(); i++) {
            RuleCommand cmd = rules.get(i);
            try {
                new Rule(partnerCode, cmd.schemeId(), domainDirection(cmd.direction()),
                        settleA, collection, cmd.mA(), cmd.mB(),
                        cmd.serviceChargeUsd()).validate();
            } catch (ApiException invariant) {
                throw badRequest("rules[" + i + "]: " + invariant.getMessage());
            }
        }
    }

    /**
     * Adapt the V017 direction string to the lib-domain {@link Direction} for
     * the invariant check. {@code BOTH} (one row covering INBOUND + OUTBOUND)
     * has no domain-enum counterpart and maps to {@code null} — safe because
     * {@link Rule#validate()} prices on the currency pair, not the direction.
     */
    private static Direction domainDirection(String direction) {
        return switch (direction) {
            case "INBOUND" -> Direction.INBOUND;
            case "OUTBOUND" -> Direction.OUTBOUND;
            default -> null;
        };
    }

    /**
     * Normalise an accepted decimal to scale 4 so the persisted NUMERIC equals
     * the in-memory value on both engines and the {@link RuleJson} audit bytes
     * are deterministic. Values arrive pre-validated (≤ 4 dp), so the setScale
     * never rounds.
     */
    private static BigDecimal normalizeScale4(BigDecimal value) {
        return value == null ? null : value.setScale(4);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
