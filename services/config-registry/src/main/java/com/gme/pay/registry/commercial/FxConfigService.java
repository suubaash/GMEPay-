package com.gme.pay.registry.commercial;

import static com.gme.pay.registry.commercial.CommercialValidation.badRequest;
import static com.gme.pay.registry.commercial.CommercialValidation.normalizeScale4;
import static com.gme.pay.registry.commercial.CommercialValidation.requireOnboarding;
import static com.gme.pay.registry.commercial.CommercialValidation.requirePartner;
import static com.gme.pay.registry.commercial.CommercialValidation.validateBps;

import com.gme.pay.contracts.FxConfigCommand;
import com.gme.pay.contracts.FxConfigView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
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
 * Slice 6 — owns the {@code partner_fx_config} child aggregate (V019) behind
 * the wizard's step-6 commercial endpoints (see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6 — Commercial Terms").
 *
 * <p>The FX section of a step-6 PATCH is a <b>full-state replace</b>: inside
 * one transaction the current config row (if any) is superseded and a fresh
 * row inserted, both halves sharing one MICROS-truncated instant — the SCD-6
 * paired-write discipline of {@code PrefundingConfigService} (ADR-010). One
 * audit row per write ({@code aggregateType="partner_fx_config"}, ADR-007),
 * published through the {@link ObjectProvider}-resolved
 * {@link AuditLogService}.
 */
@Service
public class FxConfigService {

    /** Aggregate-type discriminator on audit rows for FX-config mutations. */
    public static final String AGGREGATE_TYPE = "partner_fx_config";

    /** Audit verb for the step-6 FX full-state replace. */
    public static final String EVENT_TYPE_SAVED = "PARTNER_FX_CONFIG_SAVED";

    /** V019 CHECK roster for reference_rate_source. */
    static final Set<String> RATE_SOURCES =
            Set.of("SEOUL_FX_BROKER", "PARTNER_PROVIDED", "MID_MARKET");

    /** V019 column DEFAULTs / CHECK bounds. */
    static final int DEFAULT_QUOTE_HOLD_SECONDS = 300;
    static final int MIN_QUOTE_HOLD_SECONDS = 60;
    static final int MAX_QUOTE_HOLD_SECONDS = 1800;

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final FxConfigRepository fxConfigRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public FxConfigService(FxConfigRepository fxConfigRepository,
                           PartnerRepository partnerRepository,
                           ObjectProvider<AuditLogService> auditLogProvider) {
        this.fxConfigRepository = fxConfigRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Full-state replace of the FX config on a draft partner (wizard step-6
     * "Next", FX section).
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner has left {@code ONBOARDING}; 400 on
     *         validation failure (missing/unknown rate source, negative or
     *         over-scale margin, hold outside 60..1800).
     */
    @Transactional
    public FxConfigView upsertFxConfig(String partnerCode, FxConfigCommand cmd, String actor) {
        if (cmd == null) {
            throw badRequest("fxConfig section required");
        }
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);
        requireOnboarding(partner, partnerCode, "step-6 fx-config");
        validate(cmd);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<FxConfigEntity> priorOpt =
                fxConfigRepository.findCurrentByPartnerId(partner.getId());

        FxConfigEntity fresh = new FxConfigEntity();
        fresh.setPartnerId(partner.getId());
        fresh.setMarginBps(normalizeScale4(
                cmd.marginBps() == null ? BigDecimal.ZERO : cmd.marginBps()));
        fresh.setReferenceRateSource(cmd.referenceRateSource());
        fresh.setQuoteHoldSeconds(cmd.quoteHoldSeconds() == null
                ? DEFAULT_QUOTE_HOLD_SECONDS : cmd.quoteHoldSeconds());
        // Step 10: optional transparency flag — null defaults to false (not disclosed).
        fresh.setDisclosedPartnerMargin(
                cmd.disclosedPartnerMargin() != null && cmd.disclosedPartnerMargin());

        FxConfigEntity saved = pairedWrite(priorOpt.orElse(null), fresh, now);
        publishAudit(partnerCode, actor,
                priorOpt.map(CommercialJson::canonicalFxConfig).orElse(null),
                CommercialJson.canonicalFxConfig(saved));
        return saved.toView();
    }

    /**
     * The CURRENT FX config for the given partner code.
     *
     * @throws ResponseStatusException 404 when the partner code is unknown OR
     *         when the partner has no FX config yet (the wizard treats both as
     *         "nothing to rehydrate"; the message disambiguates).
     */
    @Transactional(readOnly = true)
    public FxConfigView currentFxConfig(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);
        return fxConfigRepository.findCurrentByPartnerId(partner.getId())
                .map(FxConfigEntity::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no fx config for partner '" + partnerCode + "'"));
    }

    // -------------------------- Helpers --------------------------------------

    /**
     * SCD-6 paired write: supersede the prior current row (when present) and
     * insert the fresh one, both stamped with the same MICROS-truncated
     * instant — same ordering discipline as {@code PrefundingConfigService}.
     */
    private FxConfigEntity pairedWrite(FxConfigEntity prior, FxConfigEntity fresh, Instant now) {
        fresh.setRecordedAt(now);
        if (prior != null) {
            // Business time stays continuous across transaction-time writes.
            fresh.setValidFrom(prior.getValidFrom());
            fresh.setValidTo(prior.getValidTo());
            prior.setSupersededAt(now);
            fxConfigRepository.saveAndFlush(prior);
        } else {
            fresh.setValidFrom(now);
        }
        return fxConfigRepository.saveAndFlush(fresh);
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

    /** Field-format validation, whole payload before any row is touched. */
    private static void validate(FxConfigCommand cmd) {
        if (cmd.referenceRateSource() == null || cmd.referenceRateSource().isBlank()) {
            throw badRequest("fxConfig.referenceRateSource is required and must be one of "
                    + RATE_SOURCES);
        }
        if (!RATE_SOURCES.contains(cmd.referenceRateSource())) {
            throw badRequest("fxConfig.referenceRateSource must be one of " + RATE_SOURCES
                    + ", was: " + cmd.referenceRateSource());
        }
        validateBps("fxConfig.marginBps", cmd.marginBps());
        if (cmd.quoteHoldSeconds() != null
                && (cmd.quoteHoldSeconds() < MIN_QUOTE_HOLD_SECONDS
                        || cmd.quoteHoldSeconds() > MAX_QUOTE_HOLD_SECONDS)) {
            throw badRequest("fxConfig.quoteHoldSeconds must be between "
                    + MIN_QUOTE_HOLD_SECONDS + " and " + MAX_QUOTE_HOLD_SECONDS
                    + ", was: " + cmd.quoteHoldSeconds());
        }
    }
}
