package com.gme.pay.registry.scheme;

import com.gme.pay.contracts.PartnerSchemeCommand;
import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.SchemeOperatingHoursView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
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
 * Slice 7 — owns the {@code partner_scheme} child aggregate (V022) behind the
 * wizard's step-7 scheme-enablement endpoints, plus the read path over the
 * {@code scheme_operating_hours} reference table (V024). See
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 7 — Scheme Enablement".
 *
 * <h2>Bulk-replace semantics</h2>
 *
 * <p>The wizard's contract is "send the full scheme set on every save", so a
 * PATCH is a <b>bulk replace</b>: inside one transaction every current
 * {@code partner_scheme} row for the partner is superseded
 * ({@code superseded_at = now}) and the new set is inserted
 * ({@code recorded_at = now}), both halves sharing the same MICROS-truncated
 * instant — the SCD-6 paired-write discipline of {@code RuleService}
 * (ADR-010). Sending an empty list clears all schemes; {@code null} is a 400.
 *
 * <h2>ZEROPAY cross-field invariant</h2>
 *
 * <p>An ENABLED {@code ZEROPAY} element must carry {@code zeropayMerchantId}
 * + {@code kftcInstitutionCode} — without them the row cannot route and an
 * enablement would be a lie. This is deliberately SERVICE-enforced, not a DB
 * CHECK: drafts may stay incomplete while the row is {@code enabled=false}.
 * Violations are a 400 ({@code VALIDATION_ERROR}).
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_scheme"}, keyed by
 * the partner business code, BEFORE/AFTER = {@link PartnerSchemeJson}
 * canonical snapshots, published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code RuleService} / {@code PrefundingConfigService}.
 */
@Service
public class PartnerSchemeService {

    /** Aggregate-type discriminator on audit rows for scheme mutations. */
    public static final String AGGREGATE_TYPE = "partner_scheme";

    /** Audit verb for the step-7 bulk replace. */
    public static final String EVENT_TYPE_REPLACED = "PARTNER_SCHEMES_REPLACED";

    /**
     * V022 CHECK roster for scheme_id (the Slice 7 scheme registry). Derived from the
     * {@link SchemeCatalogService} catalog — the single source of truth — so the
     * {@code GET /v1/schemes} picker can never offer a scheme this enablement endpoint
     * would reject with a 400. {@code SchemeCatalogServiceTest} pins this equal to the
     * V022 {@code ck_partner_scheme_scheme} DB CHECK roster.
     */
    static final Set<String> SCHEMES = SchemeCatalogService.schemeIds();

    /** V022 CHECK roster for direction (same as V017). */
    static final Set<String> DIRECTIONS = Set.of("INBOUND", "OUTBOUND", "BOTH");

    /** V022 CHECK roster for role. */
    static final Set<String> ROLES = Set.of("ACQUIRER", "ISSUER", "BOTH");

    /** V022 CHECK roster for partner_type_char: direct / indirect. */
    static final Set<String> TYPE_CHARS = Set.of("D", "I");

    /** V022 CHECK roster for both approval-method columns. */
    static final Set<String> APPROVAL_METHODS = Set.of("CONFIRMATION", "SILENT");

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final PartnerSchemeRepository schemeRepository;
    private final SchemeOperatingHoursRepository operatingHoursRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerSchemeService(PartnerSchemeRepository schemeRepository,
                                SchemeOperatingHoursRepository operatingHoursRepository,
                                PartnerRepository partnerRepository,
                                ObjectProvider<AuditLogService> auditLogProvider) {
        this.schemeRepository = schemeRepository;
        this.operatingHoursRepository = operatingHoursRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the scheme set on a draft partner (wizard step-7 "Next").
     *
     * @param partnerCode the human-facing business code routing the PATCH.
     * @param schemes     the FULL desired set; empty clears, {@code null} is a 400.
     * @param actor       the operator (X-Actor header); {@code "system"} when absent.
     * @return the freshly-inserted current set as canonical {@link PartnerSchemeView}s.
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner is no longer in {@code ONBOARDING}
     *         (post-activation scheme changes ride the change_request approval
     *         flow with the Slice 8 FSM); 400 on any validation failure —
     *         including the ZEROPAY cross-field invariant — reported with the
     *         offending {@code schemes[i]} index so the multi-row editor can
     *         highlight the row.
     */
    @Transactional
    public List<PartnerSchemeView> replaceDraftSchemes(String partnerCode,
                                                       List<PartnerSchemeCommand> schemes,
                                                       String actor) {
        if (schemes == null) {
            throw badRequest("schemes is required (send an empty list to clear all schemes)");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-7 scheme edits are only permitted while ONBOARDING"
                            + " (post-activation scheme changes require the change_request"
                            + " approval flow)");
        }
        // Validate the WHOLE payload before touching any row — a bad element
        // must not leave the set half-replaced (fail fast, side-effect free).
        for (int i = 0; i < schemes.size(); i++) {
            validate(schemes.get(i), i);
        }
        validateNoDuplicateSchemes(schemes);

        // One transaction-time instant shared by both halves of the paired
        // write (supersede + insert), truncated to MICROS — see PartnerStore.save.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        List<PartnerSchemeEntity> prior =
                schemeRepository.findAllCurrentByPartnerId(partner.getId());
        byte[] before = prior.isEmpty() ? null : PartnerSchemeJson.canonical(prior);

        // Supersede the prior current set first (flush forces the UPDATEs out
        // before the INSERTs so the V022 partial-unique emulation never sees
        // two current rows per key mid-transaction — same SCD-6 ordering as
        // RuleService).
        if (!prior.isEmpty()) {
            for (PartnerSchemeEntity p : prior) {
                p.setSupersededAt(now);
            }
            schemeRepository.saveAllAndFlush(prior);
        }

        // Insert the new current set. IDENTITY ids are assigned at flush; the
        // RETURNED managed entities carry them, which the audit AFTER snapshot
        // and the response views both need.
        List<PartnerSchemeEntity> fresh = new ArrayList<>(schemes.size());
        for (PartnerSchemeCommand cmd : schemes) {
            fresh.add(toEntity(partner.getId(), cmd, now));
        }
        List<PartnerSchemeEntity> saved = schemeRepository.saveAllAndFlush(fresh);

        publishAudit(partnerCode, actor, before, PartnerSchemeJson.canonical(saved));

        return saved.stream().map(PartnerSchemeEntity::toView).toList();
    }

    /**
     * The CURRENT scheme set for the given partner code (no historical rows).
     *
     * @throws ResponseStatusException 404 when no current partner row matches —
     *         "partner exists with zero schemes" returns an empty list, only an
     *         unknown code 404s.
     */
    @Transactional(readOnly = true)
    public List<PartnerSchemeView> currentSchemes(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return schemeRepository.findAllCurrentByPartnerId(partner.getId()).stream()
                .map(PartnerSchemeEntity::toView)
                .toList();
    }

    /**
     * Wave-3 location-resolution read (smart-router consumes this): every
     * CURRENT scheme enablement, each carrying its owning partner's operating
     * country plus the derived {@code supportsCpm}/{@code supportsMpm} +
     * {@code status} fields, optionally filtered to one ISO-3166 alpha-2
     * country.
     *
     * <p>A {@code null}/blank {@code countryCode} returns every current
     * enablement; a country code returns only the rows whose partner operates
     * there (rows whose partner has no operating country never match a country
     * filter). The list is ordered by partner id then scheme id.
     *
     * @param countryCode optional ISO-3166 alpha-2 filter; {@code null}/blank =
     *                    no filter.
     */
    @Transactional(readOnly = true)
    public List<PartnerSchemeView> resolveByLocation(String countryCode) {
        String filter = countryCode == null || countryCode.isBlank() ? null : countryCode;
        return schemeRepository.findCurrentForLocation(filter).stream()
                .map(row -> ((PartnerSchemeEntity) row[0]).toLocationView((String) row[1]))
                .toList();
    }

    /**
     * The weekly operating schedule for one scheme (V024 reference data),
     * Monday(0) .. Sunday(6).
     *
     * @throws ResponseStatusException 404 when {@code schemeId} is not in the
     *         V022 roster. A rostered scheme whose schedule has not been
     *         seeded yet (QRIS / KHQR) returns an empty list.
     */
    @Transactional(readOnly = true)
    public List<SchemeOperatingHoursView> operatingHours(String schemeId) {
        if (schemeId == null || !SCHEMES.contains(schemeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no scheme '" + schemeId + "' (expected one of " + SCHEMES + ")");
        }
        return operatingHoursRepository.findBySchemeIdOrderByWeekday(schemeId).stream()
                .map(SchemeOperatingHoursEntity::toView)
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
    private static PartnerSchemeEntity toEntity(Long partnerId, PartnerSchemeCommand cmd,
                                                Instant now) {
        PartnerSchemeEntity e = new PartnerSchemeEntity();
        e.setPartnerId(partnerId);
        e.setSchemeId(cmd.schemeId());
        e.setDirection(cmd.direction());
        e.setRole(cmd.role());
        e.setZeropayMerchantId(trimToNull(cmd.zeropayMerchantId()));
        e.setZeropaySubMerchantId(trimToNull(cmd.zeropaySubMerchantId()));
        e.setKftcInstitutionCode(trimToNull(cmd.kftcInstitutionCode()));
        e.setPartnerTypeChar(trimToNull(cmd.partnerTypeChar()));
        e.setVaultSecretId(trimToNull(cmd.vaultSecretId()));
        e.setApprovalMethodCpm(cmd.approvalMethodCpm());
        e.setApprovalMethodMpm(cmd.approvalMethodMpm());
        // null defaults to the V022 column DEFAULT TRUE.
        e.setEnabled(cmd.enabled() == null || cmd.enabled());
        e.setRecordedAt(now);
        // Business time starts at capture — the wizard does not back-date schemes.
        e.setValidFrom(now);
        return e;
    }

    /**
     * Field-format validation for one scheme element. Index-qualified messages
     * ({@code schemes[2].role ...}) so the multi-row editor can map the 400 to
     * the offending row. The cross-element duplicate check runs separately
     * once every element passes.
     */
    private static void validate(PartnerSchemeCommand cmd, int index) {
        String at = "schemes[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.schemeId() == null || !SCHEMES.contains(cmd.schemeId())) {
            throw badRequest(at + ".schemeId must be one of " + SCHEMES
                    + ", was: " + cmd.schemeId());
        }
        if (cmd.direction() == null || !DIRECTIONS.contains(cmd.direction())) {
            throw badRequest(at + ".direction must be one of " + DIRECTIONS
                    + ", was: " + cmd.direction());
        }
        if (cmd.role() == null || !ROLES.contains(cmd.role())) {
            throw badRequest(at + ".role must be one of " + ROLES
                    + ", was: " + cmd.role());
        }
        validateLength(at + ".zeropayMerchantId", cmd.zeropayMerchantId(), 40);
        validateLength(at + ".zeropaySubMerchantId", cmd.zeropaySubMerchantId(), 40);
        validateLength(at + ".kftcInstitutionCode", cmd.kftcInstitutionCode(), 20);
        validateLength(at + ".vaultSecretId", cmd.vaultSecretId(), 64);
        if (cmd.partnerTypeChar() != null && !TYPE_CHARS.contains(cmd.partnerTypeChar())) {
            throw badRequest(at + ".partnerTypeChar must be one of " + TYPE_CHARS
                    + " (direct / indirect), was: " + cmd.partnerTypeChar());
        }
        if (cmd.approvalMethodCpm() != null
                && !APPROVAL_METHODS.contains(cmd.approvalMethodCpm())) {
            throw badRequest(at + ".approvalMethodCpm must be one of " + APPROVAL_METHODS
                    + ", was: " + cmd.approvalMethodCpm());
        }
        if (cmd.approvalMethodMpm() != null
                && !APPROVAL_METHODS.contains(cmd.approvalMethodMpm())) {
            throw badRequest(at + ".approvalMethodMpm must be one of " + APPROVAL_METHODS
                    + ", was: " + cmd.approvalMethodMpm());
        }
        validateZeropayWiring(cmd, at);
    }

    /**
     * The ZEROPAY cross-field invariant: an ENABLED ZEROPAY row must carry
     * {@code zeropayMerchantId} + {@code kftcInstitutionCode} — deliberately
     * service-layer, NOT a DB CHECK, so a draft can hold an incomplete but
     * DISABLED ZEROPAY row while the wizard is in flight.
     */
    private static void validateZeropayWiring(PartnerSchemeCommand cmd, String at) {
        boolean enabled = cmd.enabled() == null || cmd.enabled();
        if (!enabled || !"ZEROPAY".equals(cmd.schemeId())) {
            return;
        }
        if (isBlank(cmd.zeropayMerchantId()) || isBlank(cmd.kftcInstitutionCode())) {
            throw badRequest("VALIDATION_ERROR: " + at + ": an enabled ZEROPAY scheme requires"
                    + " zeropayMerchantId and kftcInstitutionCode (disable the row to save"
                    + " an incomplete draft)");
        }
    }

    /** Optional free-text field against its V022 column width. */
    private static void validateLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw badRequest(field + " must be at most " + max + " characters");
        }
    }

    /**
     * At most one row per {@code schemeId} across the payload (the payload IS
     * the full current state under replace semantics, so the payload-level
     * check is the V022 partial-unique invariant).
     */
    private static void validateNoDuplicateSchemes(List<PartnerSchemeCommand> schemes) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < schemes.size(); i++) {
            if (!seen.add(schemes.get(i).schemeId())) {
                throw badRequest("schemes[" + i + "]: duplicate scheme "
                        + schemes.get(i).schemeId()
                        + " (at most one enablement per scheme)");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
