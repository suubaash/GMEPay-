package com.gme.pay.registry.settlement;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.SettlementConfigView;
import com.gme.pay.contracts.SettlementPreview;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 4 — owns the {@code partner_settlement_config} child aggregate (V013)
 * behind the wizard's step-4 settlement endpoints, and the settlement-preview
 * projection over the {@code business_day_calendar} (V014).
 *
 * <h2>Step-4 upsert semantics</h2>
 *
 * <p>The wizard's contract is "send the full settlement panel on every save",
 * so a PATCH is a <b>full-state replace</b>: inside one transaction the
 * current config row (if any) is superseded ({@code superseded_at = now}) and
 * a fresh row is inserted ({@code recorded_at = now}), both halves sharing the
 * same MICROS-truncated instant — the SCD-6 paired-write discipline of
 * {@code PartnerStore.save} / {@code KybService.upsertStep3} (ADR-010).
 *
 * <p>During ONBOARDING these writes go direct (audited). The
 * 2-authorized-signatory approval flow for POST-ACTIVATION settlement/bank
 * changes lands with the Slice 8 FSM — until then, non-ONBOARDING partners get
 * a 409 here, same as every other step service.
 *
 * <h2>Preview (business-day projection)</h2>
 *
 * <p>{@link #preview} resolves the partner's config, merges the V014 holiday
 * calendars of KR (always — every settlement touches a Korean bank) and the
 * partner's bank country, and delegates the date arithmetic to the pure
 * {@link SettlementScheduleCalculator}. The bank country comes from the
 * optional {@code bankCountry} request parameter, falling back to the
 * partner's {@code country_of_incorporation}; once Slice 4's bank-account
 * aggregate (V012, sibling agent) exposes a primary PAYOUT account, its
 * {@code bank_country} becomes the natural default — a deliberately additive
 * follow-up.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_settlement_config"},
 * keyed by the partner business code, BEFORE/AFTER = {@link SettlementJson}
 * canonical snapshots, published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code PartnerStore} / {@code KybService}.
 */
@Service
public class SettlementConfigService {

    /** Aggregate-type discriminator on audit rows for settlement-config mutations. */
    public static final String AGGREGATE_TYPE = "partner_settlement_config";

    /** Audit verb for the step-4 settlement full-state replace. */
    public static final String EVENT_TYPE_SAVED = "PARTNER_SETTLEMENT_CONFIG_SAVED";

    /** V013 CHECK roster for settlement_method. */
    static final Set<String> SETTLEMENT_METHODS = Set.of(
            "SWIFT_MT103", "KR_FIRM_BANKING", "BAKONG",
            "NAPAS_247", "PROMPT_PAY", "FAST_SG", "OTHER");

    /** Defaults mirroring the V013 column DEFAULTs (applied to null command fields). */
    static final int DEFAULT_CYCLE_T_PLUS_N = 1;
    static final LocalTime DEFAULT_CUTOFF_TIME = LocalTime.of(16, 30);
    static final String DEFAULT_CUTOFF_TIMEZONE = "Asia/Seoul";

    /** GME's home calendar — every settlement touches a Korean bank. */
    static final String HOME_COUNTRY = "KR";

    /**
     * Days of calendar fetched ahead of the value date for one preview. T+5
     * across the longest seeded block plus weekends lands well inside it, and
     * {@code SettlementScheduleCalculator.MAX_DAYS_WALKED} (60) aborts before
     * the window could ever be outrun silently.
     */
    static final int CALENDAR_WINDOW_DAYS = 70;

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final SettlementConfigRepository configRepository;
    private final BusinessDayCalendarRepository calendarRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public SettlementConfigService(SettlementConfigRepository configRepository,
                                   BusinessDayCalendarRepository calendarRepository,
                                   PartnerRepository partnerRepository,
                                   ObjectProvider<AuditLogService> auditLogProvider) {
        this.configRepository = configRepository;
        this.calendarRepository = calendarRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Full-state replace of the settlement config on a draft partner (wizard
     * step-4 settlement panel "Next").
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner has left {@code ONBOARDING} (post-activation
     *         settlement changes wait for the Slice 8 signatory flow);
     *         400 on validation failure.
     */
    @Transactional
    public SettlementConfigView upsertStep4Settlement(String partnerCode,
                                                      PartnerCommand.UpdateStep4Settlement cmd,
                                                      String actor) {
        if (cmd == null) {
            throw badRequest("request body required");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-4 settlement edits are only permitted while ONBOARDING");
        }
        validate(cmd);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<SettlementConfigEntity> priorOpt =
                configRepository.findCurrentByPartnerId(partner.getId());

        SettlementConfigEntity fresh = new SettlementConfigEntity();
        fresh.setPartnerId(partner.getId());
        fresh.setCycleTPlusN(cmd.cycleTPlusN() == null ? DEFAULT_CYCLE_T_PLUS_N : cmd.cycleTPlusN());
        fresh.setCutoffTime(cmd.cutoffTime() == null ? DEFAULT_CUTOFF_TIME : cmd.cutoffTime());
        fresh.setCutoffTimezone(cmd.cutoffTimezone() == null || cmd.cutoffTimezone().isBlank()
                ? DEFAULT_CUTOFF_TIMEZONE : cmd.cutoffTimezone());
        fresh.setSettlementMethod(cmd.settlementMethod());

        SettlementConfigEntity saved = pairedWrite(priorOpt.orElse(null), fresh, now);
        publishAudit(partnerCode, actor,
                priorOpt.map(SettlementJson::canonical).orElse(null),
                SettlementJson.canonical(saved));
        return saved.toView();
    }

    /**
     * The CURRENT settlement config for the given partner code.
     *
     * @throws ResponseStatusException 404 when the partner code is unknown OR
     *         when the partner has no settlement config yet (the wizard treats
     *         both as "nothing to rehydrate"; the message disambiguates).
     */
    @Transactional(readOnly = true)
    public SettlementConfigView currentConfig(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return configRepository.findCurrentByPartnerId(partner.getId())
                .map(SettlementConfigEntity::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no settlement config for partner '" + partnerCode + "'"));
    }

    /**
     * Project one transaction instant onto a payout date using the partner's
     * CURRENT settlement config and the merged KR + bank-country holiday
     * calendars (V014).
     *
     * @param partnerCode the partner business code.
     * @param txnInstant  the transaction instant being previewed.
     * @param bankCountry optional ISO-3166 alpha-2 override of the partner's
     *                    bank country; {@code null} falls back to the
     *                    partner's {@code country_of_incorporation} (see class
     *                    docs for the planned bank-account default).
     * @throws ResponseStatusException 404 unknown partner / no settlement
     *         config; 400 when {@code bankCountry} is not alpha-2.
     */
    @Transactional(readOnly = true)
    public SettlementPreview preview(String partnerCode, Instant txnInstant, String bankCountry) {
        if (txnInstant == null) {
            throw badRequest("txnInstant is required (ISO-8601 instant, e.g. 2026-09-23T08:30:00Z)");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        SettlementConfigEntity config = configRepository.findCurrentByPartnerId(partner.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no settlement config for partner '" + partnerCode
                                + "' - save step-4 settlement first"));

        String resolvedBankCountry = resolveBankCountry(bankCountry, partner);
        Set<String> countries = new LinkedHashSet<>();
        countries.add(HOME_COUNTRY);
        if (resolvedBankCountry != null) {
            countries.add(resolvedBankCountry);
        }

        ZoneId zone = ZoneId.of(config.getCutoffTimezone());
        // The walk starts at the txn's local date; -1 day of slack covers the
        // zone-shift edge where the instant's UTC date differs from local.
        LocalDate windowStart = txnInstant.atZone(zone).toLocalDate().minusDays(1);
        Map<LocalDate, String> holidays = mergeHolidays(
                countries, windowStart, windowStart.plusDays(CALENDAR_WINDOW_DAYS));

        return SettlementScheduleCalculator.project(
                txnInstant,
                config.getCycleTPlusN(),
                config.getCutoffTime(),
                zone,
                holidays);
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
     * instant. flush() forces the UPDATE out before the INSERT — same ordering
     * discipline as {@code PartnerStore.save} / {@code KybService}.
     */
    private SettlementConfigEntity pairedWrite(SettlementConfigEntity prior,
                                               SettlementConfigEntity fresh,
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

    /** Normalise/validate the bank-country override, falling back to incorporation country. */
    private static String resolveBankCountry(String bankCountry, PartnerEntity partner) {
        if (bankCountry != null && !bankCountry.isBlank()) {
            String trimmed = bankCountry.trim();
            if (!trimmed.matches("[A-Z]{2}")) {
                throw badRequest("bankCountry must be ISO-3166 alpha-2 (e.g. KH), was: "
                        + bankCountry);
            }
            return trimmed;
        }
        String incorporation = partner.getCountryOfIncorporation();
        return incorporation == null || incorporation.isBlank() ? null : incorporation;
    }

    /**
     * Merge the V014 rows for the requested countries into the calculator's
     * {@code date -> label} shape; multiple closures on one date concatenate
     * ({@code "KR holiday: Hangul Day + KH holiday: Pchum Ben"}) so the
     * explanation trail names every reason.
     */
    private Map<LocalDate, String> mergeHolidays(Set<String> countries,
                                                 LocalDate from, LocalDate to) {
        Map<LocalDate, String> merged = new LinkedHashMap<>();
        for (BusinessDayCalendarEntity row : calendarRepository
                .findByCountryInAndHolidayDateBetweenOrderByHolidayDateAscCountryAsc(
                        countries, from, to)) {
            // CHAR(2) round-trips with trailing spaces on some engines; trim
            // defensively so labels stay clean.
            String label = row.getCountry().trim() + " holiday: " + row.getName();
            merged.merge(row.getHolidayDate(), label, (a, b) -> a + " + " + b);
        }
        return merged;
    }

    /**
     * Field-format validation, whole payload before any row is touched (same
     * fail-fast discipline as {@code PartnerContactService} / {@code KybService}).
     */
    private static void validate(PartnerCommand.UpdateStep4Settlement cmd) {
        if (cmd.settlementMethod() == null || cmd.settlementMethod().isBlank()) {
            throw badRequest("settlementMethod is required and must be one of "
                    + SETTLEMENT_METHODS);
        }
        if (!SETTLEMENT_METHODS.contains(cmd.settlementMethod())) {
            throw badRequest("settlementMethod must be one of " + SETTLEMENT_METHODS
                    + ", was: " + cmd.settlementMethod());
        }
        if (cmd.cycleTPlusN() != null
                && (cmd.cycleTPlusN() < 0 || cmd.cycleTPlusN() > 5)) {
            throw badRequest("cycleTPlusN must be between 0 and 5, was: " + cmd.cycleTPlusN());
        }
        if (cmd.cutoffTimezone() != null && !cmd.cutoffTimezone().isBlank()) {
            if (cmd.cutoffTimezone().length() > 40) {
                throw badRequest("cutoffTimezone must be at most 40 characters");
            }
            try {
                ZoneId.of(cmd.cutoffTimezone());
            } catch (DateTimeException e) {
                throw badRequest("cutoffTimezone must be a valid IANA zone id"
                        + " (e.g. Asia/Seoul), was: " + cmd.cutoffTimezone());
            }
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
