package com.gme.pay.registry.regulatory;

import com.gme.pay.contracts.BokFxReportingCategory;
import com.gme.pay.contracts.BokRemitterType;
import com.gme.pay.contracts.LegalBasisCode;
import com.gme.pay.contracts.PartnerRegulatoryConfigCommand;
import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.TravelRuleProtocol;
import com.gme.pay.contracts.VatTreatment;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane C — owns the {@code partner_regulatory_config} child aggregate
 * (V029) behind the wizard's step-8 regulatory endpoints: the metadata GME
 * needs at activation to file BOK 외환거래보고 foreign-exchange reports, issue
 * Hometax e-tax-invoices, send KoFIU CTR/STR feeds, document PIPA
 * cross-border PII jurisdictions, and configure Travel Rule
 * (TRP/Sygna/IVMS101) on transfers ≥ KRW 1,000,000.
 *
 * <h2>Step-8 upsert semantics</h2>
 *
 * <p>The wizard's contract is "send the full regulatory panel on every save",
 * so a PATCH is a <b>full-state replace</b>: inside one transaction the
 * current config row (if any) is superseded ({@code superseded_at = now}) and
 * a fresh row inserted ({@code recorded_at = now}), both halves sharing the
 * same MICROS-truncated instant — the SCD-6 paired-write discipline of
 * {@code PrefundingConfigService.upsertStep5} (ADR-010). The fresh row also
 * stamps the provenance pair: {@code changed_by} = the actor,
 * {@code change_request_id} = NULL during ONBOARDING (the Slice 8 FSM threads
 * real change-request ids through post-activation edits).
 *
 * <h2>Activation gate (Lane A seam)</h2>
 *
 * <p>"A current {@code partner_regulatory_config} row exists" is a hard LIVE
 * pre-condition. Lane A's {@code ActivationGateService} evaluates it through
 * {@link PartnerRegulatoryConfigRepository#existsCurrentByPartnerId} — this
 * service only has to guarantee the row's existence implies a validated save
 * (every write path runs the full validation below).
 *
 * <h2>Money ({@code docs/MONEY_CONVENTION.md})</h2>
 *
 * <p>{@code ctrThresholdKrw} / {@code travelRuleThresholdKrw} are
 * {@link BigDecimal} major-KRW, at most 2 decimal places (NUMERIC(18,2)),
 * strictly positive, normalised to scale 2 before persisting so stored ==
 * in-memory on both engines and the audit snapshot bytes are deterministic.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_regulatory_config"},
 * keyed by the partner business code, BEFORE/AFTER = {@link RegulatoryJson}
 * canonical snapshots, published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code RuleService} / {@code PrefundingConfigService}.
 */
@Service
public class PartnerRegulatoryConfigService {

    /** Aggregate-type discriminator on audit rows for regulatory-config mutations. */
    public static final String AGGREGATE_TYPE = "partner_regulatory_config";

    /** Audit verb for the step-8 regulatory full-state replace. */
    public static final String EVENT_TYPE_SAVED = "PARTNER_REGULATORY_CONFIG_SAVED";

    /**
     * BOK external-trade-code shape. TODO(OI-03): confirm the official BOK
     * external-trade-code format against the OI-03 reference; until then this
     * placeholder (exactly 3 digits) is the enforced envelope, matching the
     * V029 column comment.
     */
    static final Pattern BOK_TXN_CODE = Pattern.compile("\\d{3}");

    /** The full ISO-3166 alpha-2 roster (JDK-maintained) for the PIPA allowlist. */
    static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    /** Defaults mirroring the V029 column DEFAULTs (applied to null command fields). */
    static final BigDecimal DEFAULT_CTR_THRESHOLD_KRW = new BigDecimal("10000000");
    static final BigDecimal DEFAULT_TRAVEL_RULE_THRESHOLD_KRW = new BigDecimal("1000000");

    /** NUMERIC(18,2): at most 2 decimal places, at most 16 integer digits. */
    static final int KRW_SCALE = 2;
    static final int KRW_MAX_INTEGER_DIGITS = 16;

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final PartnerRegulatoryConfigRepository configRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerRegulatoryConfigService(PartnerRegulatoryConfigRepository configRepository,
                                          PartnerRepository partnerRepository,
                                          ObjectProvider<AuditLogService> auditLogProvider) {
        this.configRepository = configRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Full-state replace of the regulatory config on a draft partner (wizard
     * step-8 "Next").
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner has left {@code ONBOARDING} (post-activation
     *         regulatory changes ride the change_request approval flow with
     *         the Slice 8 FSM); 400 on validation failure (bad BOK code shape,
     *         unknown roster value, non-ISO jurisdiction, non-positive or
     *         over-scale threshold, missing Travel-Rule endpoint).
     */
    @Transactional
    public PartnerRegulatoryConfigView upsertStep8(String partnerCode,
                                                   PartnerRegulatoryConfigCommand cmd,
                                                   String actor) {
        if (cmd == null) {
            throw badRequest("request body required");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-8 regulatory edits are only permitted while ONBOARDING"
                            + " (post-activation regulatory changes require the"
                            + " change_request approval flow)");
        }
        validate(cmd);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<PartnerRegulatoryConfigEntity> priorOpt =
                configRepository.findCurrentByPartnerId(partner.getId());

        PartnerRegulatoryConfigEntity fresh = new PartnerRegulatoryConfigEntity();
        fresh.setPartnerId(partner.getId());
        fresh.setBokTxnCode(trimToNull(cmd.bokTxnCode()));
        fresh.setBokFxReportingCategory(trimToNull(cmd.bokFxReportingCategory()));
        fresh.setBokRemitterType(trimToNull(cmd.bokRemitterType()));
        fresh.setHometaxIssuerCertId(trimToNull(cmd.hometaxIssuerCertId()));
        fresh.setVatTreatment(trimToNull(cmd.vatTreatment()));
        fresh.setKofiuEntityId(trimToNull(cmd.kofiuEntityId()));
        fresh.setCtrThresholdKrw(normalizeKrw(
                cmd.ctrThresholdKrw() == null ? DEFAULT_CTR_THRESHOLD_KRW
                        : cmd.ctrThresholdKrw()));
        fresh.setPipaJurisdictionAllowlist(toCsv(cmd.pipaJurisdictionAllowlist()));
        fresh.setLegalBasisCode(trimToNull(cmd.legalBasisCode()));
        fresh.setTravelRuleProtocol(trimToNull(cmd.travelRuleProtocol()));
        fresh.setTravelRuleEndpointUrl(trimToNull(cmd.travelRuleEndpointUrl()));
        fresh.setTravelRuleThresholdKrw(normalizeKrw(
                cmd.travelRuleThresholdKrw() == null ? DEFAULT_TRAVEL_RULE_THRESHOLD_KRW
                        : cmd.travelRuleThresholdKrw()));
        // Change provenance (V029): who wrote this row version; the
        // change_request id stays NULL for direct ONBOARDING writes (the
        // Slice 8 FSM threads real ids through post-activation edits).
        fresh.setChangedBy(actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor);
        fresh.setChangeRequestId(null);

        PartnerRegulatoryConfigEntity saved = pairedWrite(priorOpt.orElse(null), fresh, now);
        publishAudit(partnerCode, actor,
                priorOpt.map(RegulatoryJson::canonical).orElse(null),
                RegulatoryJson.canonical(saved));
        return saved.toView();
    }

    /**
     * The CURRENT regulatory config for the given partner code.
     *
     * @throws ResponseStatusException 404 when the partner code is unknown OR
     *         when the partner has no regulatory config yet (the wizard treats
     *         both as "nothing to rehydrate"; the message disambiguates).
     */
    @Transactional(readOnly = true)
    public PartnerRegulatoryConfigView currentConfig(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return configRepository.findCurrentByPartnerId(partner.getId())
                .map(PartnerRegulatoryConfigEntity::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no regulatory config for partner '" + partnerCode + "'"));
    }

    /**
     * TRUE when the partner (by business code) has a CURRENT regulatory config
     * row — the Slice 8 activation-gate pre-condition for LIVE, exposed for
     * Lane A's {@code ActivationGateService} (which may equally call
     * {@link PartnerRegulatoryConfigRepository#existsCurrentByPartnerId}
     * directly when it already holds the surrogate id).
     *
     * @throws ResponseStatusException 404 when the partner code is unknown.
     */
    @Transactional(readOnly = true)
    public boolean hasCurrentConfig(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return configRepository.existsCurrentByPartnerId(partner.getId());
    }

    // -------------------------- Helpers --------------------------------------

    private PartnerEntity requirePartner(String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    /**
     * SCD-6 paired write: supersede the prior current row (when present) and
     * insert the fresh one, both stamped with the same MICROS-truncated
     * instant. flush() forces the UPDATE out before the INSERT so the V029
     * current_partner_key UNIQUE emulation never sees two current rows
     * mid-transaction — same ordering discipline as
     * {@code PrefundingConfigService.pairedWrite}.
     */
    private PartnerRegulatoryConfigEntity pairedWrite(PartnerRegulatoryConfigEntity prior,
                                                      PartnerRegulatoryConfigEntity fresh,
                                                      Instant now) {
        fresh.setRecordedAt(now);
        if (prior != null) {
            // Business time stays continuous across transaction-time writes.
            fresh.setValidFrom(prior.getValidFrom());
            fresh.setValidTo(prior.getValidTo());
            prior.setSupersededAt(now);
            configRepository.saveAndFlush(prior);
        } else {
            fresh.setValidFrom(now);
        }
        return configRepository.saveAndFlush(fresh);
    }

    /** ADR-007 audit row, same-transaction (commits iff the config write commits). */
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

    /**
     * Field-format validation, whole payload before any row is touched (same
     * fail-fast discipline as {@code PrefundingConfigService.validate}).
     */
    private static void validate(PartnerRegulatoryConfigCommand cmd) {
        // BOK 외환거래보고
        if (notBlank(cmd.bokTxnCode())
                && !BOK_TXN_CODE.matcher(cmd.bokTxnCode().trim()).matches()) {
            throw badRequest("bokTxnCode must match the BOK external-trade-code shape "
                    + BOK_TXN_CODE.pattern() + " (3 digits; placeholder pending the OI-03"
                    + " reference), was: " + cmd.bokTxnCode());
        }
        validateRoster("bokFxReportingCategory", cmd.bokFxReportingCategory(),
                BokFxReportingCategory.values());
        validateRoster("bokRemitterType", cmd.bokRemitterType(), BokRemitterType.values());

        // Hometax
        if (notBlank(cmd.hometaxIssuerCertId()) && cmd.hometaxIssuerCertId().trim().length() > 64) {
            throw badRequest("hometaxIssuerCertId must be at most 64 characters"
                    + " (a lib-vault document id)");
        }
        validateRoster("vatTreatment", cmd.vatTreatment(), VatTreatment.values());

        // KoFIU
        if (notBlank(cmd.kofiuEntityId()) && cmd.kofiuEntityId().trim().length() > 40) {
            throw badRequest("kofiuEntityId must be at most 40 characters");
        }
        validateKrw("ctrThresholdKrw", cmd.ctrThresholdKrw());

        // PIPA
        validateJurisdictions(cmd.pipaJurisdictionAllowlist());
        validateRoster("legalBasisCode", cmd.legalBasisCode(), LegalBasisCode.values());

        // Travel Rule
        validateRoster("travelRuleProtocol", cmd.travelRuleProtocol(),
                TravelRuleProtocol.values());
        if (notBlank(cmd.travelRuleEndpointUrl())
                && cmd.travelRuleEndpointUrl().trim().length() > 500) {
            throw badRequest("travelRuleEndpointUrl must be at most 500 characters");
        }
        String protocol = notBlank(cmd.travelRuleProtocol())
                ? cmd.travelRuleProtocol().trim() : null;
        if (protocol != null && !TravelRuleProtocol.NONE.name().equals(protocol)
                && !notBlank(cmd.travelRuleEndpointUrl())) {
            throw badRequest("travelRuleEndpointUrl is required when travelRuleProtocol is "
                    + protocol + " (only NONE may omit the counterparty endpoint)");
        }
        validateKrw("travelRuleThresholdKrw", cmd.travelRuleThresholdKrw());
    }

    /** One roster-valued field against its lib-api-contracts enum; null/blank = not stated. */
    private static void validateRoster(String field, String value, Enum<?>[] roster) {
        if (!notBlank(value)) {
            return;
        }
        String trimmed = value.trim();
        for (Enum<?> e : roster) {
            if (e.name().equals(trimmed)) {
                return;
            }
        }
        throw badRequest(field + " must be one of "
                + Arrays.stream(roster).map(Enum::name).collect(Collectors.joining(", "))
                + ", was: " + value);
    }

    /**
     * Every element of the PIPA allowlist must be an UPPERCASE ISO-3166
     * alpha-2 code from the JDK's ISO roster; duplicates are a 400 so the
     * documented jurisdiction list stays unambiguous.
     */
    private static void validateJurisdictions(List<String> codes) {
        if (codes == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < codes.size(); i++) {
            String at = "pipaJurisdictionAllowlist[" + i + "]";
            String code = codes.get(i);
            if (!notBlank(code)) {
                throw badRequest(at + " must not be blank (ISO-3166 alpha-2, e.g. MN)");
            }
            String trimmed = code.trim();
            if (!ISO_COUNTRIES.contains(trimmed)) {
                throw badRequest(at + " must be an UPPERCASE ISO-3166 alpha-2 country code"
                        + " (e.g. MN), was: " + code);
            }
            if (!seen.add(trimmed)) {
                throw badRequest(at + ": duplicate jurisdiction " + trimmed);
            }
        }
    }

    /**
     * One KRW threshold against the NUMERIC(18,2) envelope: strictly positive
     * (a zero CTR threshold would report EVERY transaction — that is a
     * config error, not a policy), ≤ 2 decimal places, ≤ 16 integer digits.
     * {@code null} is fine — the V029 statutory defaults apply.
     */
    private static void validateKrw(String field, BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.signum() <= 0) {
            throw badRequest(field + " must be greater than 0, was: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > KRW_SCALE) {
            throw badRequest(field + " must have at most " + KRW_SCALE
                    + " decimal places (NUMERIC(18,2)), was: " + value.toPlainString());
        }
        if (value.precision() - value.scale() > KRW_MAX_INTEGER_DIGITS) {
            throw badRequest(field + " exceeds NUMERIC(18,2) (at most "
                    + KRW_MAX_INTEGER_DIGITS + " integer digits), was: "
                    + value.toPlainString());
        }
    }

    /**
     * Join the validated allowlist to the V029 CSV column form — trimmed,
     * original order preserved (validation already rejected duplicates), null
     * when the wire sent nothing (null and empty list both mean "none
     * documented").
     */
    private static String toCsv(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        return codes.stream().map(String::trim)
                .collect(Collectors.joining(PartnerRegulatoryConfigEntity.CSV_SEPARATOR));
    }

    /**
     * Normalise an accepted KRW threshold to scale 2 so the persisted
     * NUMERIC(18,2) equals the in-memory value on both engines and the
     * {@link RegulatoryJson} audit bytes are deterministic. Values arrive
     * pre-validated (≤ 2 dp), so the setScale never rounds.
     */
    private static BigDecimal normalizeKrw(BigDecimal value) {
        return value == null ? null : value.setScale(KRW_SCALE);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        return notBlank(s) ? s.trim() : null;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
