package com.gme.pay.registry.commercial;

import static com.gme.pay.registry.commercial.CommercialValidation.badRequest;
import static com.gme.pay.registry.commercial.CommercialValidation.normalizeScale4;
import static com.gme.pay.registry.commercial.CommercialValidation.requireOnboarding;
import static com.gme.pay.registry.commercial.CommercialValidation.requirePartner;
import static com.gme.pay.registry.commercial.CommercialValidation.validateMoney;

import com.gme.pay.contracts.LimitsCommand;
import com.gme.pay.contracts.LimitsView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.registry.prefunding.push.CreditLimitPusher;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 6 — owns the {@code partner_limits} child aggregate (V020) behind the
 * wizard's step-6 commercial endpoints (see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6 — Commercial Terms").
 *
 * <p>The limits section of a step-6 PATCH is a <b>full-state replace</b> —
 * SCD-6 paired write per ADR-010, same discipline as {@link FxConfigService}.
 * One audit row per write ({@code aggregateType="partner_limits"}, ADR-007).
 *
 * <h2>소액해외송금업 hard enforcement</h2>
 *
 * <p>When {@code licenseType=SOAEK_HAEOEMONG} (the Korean small-amount
 * overseas remittance licence) the statutory ceilings are enforced HERE,
 * server-side, before any row is touched: {@code perTxnMaxUsd} must be
 * PRESENT and &le; {@value #SOAEK_PER_TXN_MAX} USD, {@code annualCapUsd}
 * PRESENT and &le; {@value #SOAEK_ANNUAL_MAX} USD. The V020 CHECK is the
 * storage-level backstop (it cannot enforce presence — SQL three-valued logic
 * passes NULL); no UI gating is trusted.
 */
@Service
public class LimitsService {

    /** Aggregate-type discriminator on audit rows for limits mutations. */
    public static final String AGGREGATE_TYPE = "partner_limits";

    /** Audit verb for the step-6 limits full-state replace. */
    public static final String EVENT_TYPE_SAVED = "PARTNER_LIMITS_SAVED";

    /** The Korean 소액해외송금업 licence discriminator (V020 header). */
    public static final String LICENSE_SOAEK_HAEOEMONG = "SOAEK_HAEOEMONG";

    /** 소액해외송금업 statutory ceilings, major USD units (V020 CHECK). */
    static final BigDecimal SOAEK_PER_TXN_MAX = new BigDecimal("5000");
    static final BigDecimal SOAEK_ANNUAL_MAX = new BigDecimal("50000");

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final LimitsRepository limitsRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;
    private final CreditLimitPusher creditLimitPusher;

    public LimitsService(LimitsRepository limitsRepository,
                         PartnerRepository partnerRepository,
                         ObjectProvider<AuditLogService> auditLogProvider,
                         CreditLimitPusher creditLimitPusher) {
        this.limitsRepository = limitsRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
        this.creditLimitPusher = creditLimitPusher;
    }

    /**
     * Full-state replace of the limits row on a draft partner (wizard step-6
     * "Next", limits section).
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner has left {@code ONBOARDING}; 400 on
     *         validation failure (negative/over-scale cap, min &gt; max,
     *         daily &gt; monthly &gt; annual inversions, or a 소액해외송금업
     *         breach — missing or over-statute caps).
     */
    @Transactional
    public LimitsView upsertLimits(String partnerCode, LimitsCommand cmd, String actor) {
        if (cmd == null) {
            throw badRequest("limits section required");
        }
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);
        requireOnboarding(partner, partnerCode, "step-6 limits");
        validate(cmd);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<LimitsEntity> priorOpt =
                limitsRepository.findCurrentByPartnerId(partner.getId());

        LimitsEntity fresh = new LimitsEntity();
        fresh.setPartnerId(partner.getId());
        fresh.setPerTxnMinUsd(normalizeScale4(cmd.perTxnMinUsd()));
        fresh.setPerTxnMaxUsd(normalizeScale4(cmd.perTxnMaxUsd()));
        fresh.setDailyCapUsd(normalizeScale4(cmd.dailyCapUsd()));
        fresh.setMonthlyCapUsd(normalizeScale4(cmd.monthlyCapUsd()));
        fresh.setAnnualCapUsd(normalizeScale4(cmd.annualCapUsd()));
        fresh.setLicenseType(
                cmd.licenseType() == null || cmd.licenseType().isBlank()
                        ? null : cmd.licenseType());
        fresh.setDailyTxnCountLimit(cmd.dailyTxnCountLimit());

        LimitsEntity saved = pairedWrite(priorOpt.orElse(null), fresh, now);
        publishAudit(partnerCode, actor,
                priorOpt.map(CommercialJson::canonicalLimits).orElse(null),
                CommercialJson.canonicalLimits(saved));
        // Wave-3 (IR-pf-2): the AML caps just changed — push the merged
        // credit-limit + AML caps to prefunding (gated; no-op by default).
        creditLimitPusher.pushFor(partner);
        return saved.toView();
    }

    /**
     * The CURRENT limits for the given partner code.
     *
     * @throws ResponseStatusException 404 when the partner code is unknown OR
     *         when the partner has no limits row yet.
     */
    @Transactional(readOnly = true)
    public LimitsView currentLimits(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerRepository, partnerCode);
        return limitsRepository.findCurrentByPartnerId(partner.getId())
                .map(LimitsEntity::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no limits for partner '" + partnerCode + "'"));
    }

    // -------------------------- Helpers --------------------------------------

    /** SCD-6 paired write — same ordering discipline as {@link FxConfigService}. */
    private LimitsEntity pairedWrite(LimitsEntity prior, LimitsEntity fresh, Instant now) {
        fresh.setRecordedAt(now);
        if (prior != null) {
            fresh.setValidFrom(prior.getValidFrom());
            fresh.setValidTo(prior.getValidTo());
            prior.setSupersededAt(now);
            limitsRepository.saveAndFlush(prior);
        } else {
            fresh.setValidFrom(now);
        }
        return limitsRepository.saveAndFlush(fresh);
    }

    /** ADR-007 audit row, same-transaction (commits iff the limits write commits). */
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
    private static void validate(LimitsCommand cmd) {
        validateMoney("limits.perTxnMinUsd", cmd.perTxnMinUsd(), false);
        validateMoney("limits.perTxnMaxUsd", cmd.perTxnMaxUsd(), false);
        validateMoney("limits.dailyCapUsd", cmd.dailyCapUsd(), false);
        validateMoney("limits.monthlyCapUsd", cmd.monthlyCapUsd(), false);
        validateMoney("limits.annualCapUsd", cmd.annualCapUsd(), false);

        requireOrdered("limits.perTxnMinUsd", cmd.perTxnMinUsd(),
                "limits.perTxnMaxUsd", cmd.perTxnMaxUsd());
        requireOrdered("limits.dailyCapUsd", cmd.dailyCapUsd(),
                "limits.monthlyCapUsd", cmd.monthlyCapUsd());
        requireOrdered("limits.monthlyCapUsd", cmd.monthlyCapUsd(),
                "limits.annualCapUsd", cmd.annualCapUsd());
        requireOrdered("limits.dailyCapUsd", cmd.dailyCapUsd(),
                "limits.annualCapUsd", cmd.annualCapUsd());

        if (cmd.licenseType() != null && cmd.licenseType().length() > 30) {
            throw badRequest("limits.licenseType must be at most 30 characters");
        }
        if (cmd.dailyTxnCountLimit() != null && cmd.dailyTxnCountLimit() < 0) {
            throw badRequest("limits.dailyTxnCountLimit must be >= 0, was: " + cmd.dailyTxnCountLimit());
        }
        if (LICENSE_SOAEK_HAEOEMONG.equals(cmd.licenseType())) {
            validateSoaekCaps(cmd);
        }
    }

    /** 소액해외송금업 statutory ceilings — see class javadoc. */
    private static void validateSoaekCaps(LimitsCommand cmd) {
        if (cmd.perTxnMaxUsd() == null) {
            throw badRequest("limits.perTxnMaxUsd is required for license_type="
                    + LICENSE_SOAEK_HAEOEMONG + " (소액해외송금업) and must be <= "
                    + SOAEK_PER_TXN_MAX.toPlainString() + " USD");
        }
        if (cmd.perTxnMaxUsd().compareTo(SOAEK_PER_TXN_MAX) > 0) {
            throw badRequest("limits.perTxnMaxUsd must be <= "
                    + SOAEK_PER_TXN_MAX.toPlainString() + " USD for license_type="
                    + LICENSE_SOAEK_HAEOEMONG + " (소액해외송금업), was: "
                    + cmd.perTxnMaxUsd().toPlainString());
        }
        if (cmd.annualCapUsd() == null) {
            throw badRequest("limits.annualCapUsd is required for license_type="
                    + LICENSE_SOAEK_HAEOEMONG + " (소액해외송금업) and must be <= "
                    + SOAEK_ANNUAL_MAX.toPlainString() + " USD");
        }
        if (cmd.annualCapUsd().compareTo(SOAEK_ANNUAL_MAX) > 0) {
            throw badRequest("limits.annualCapUsd must be <= "
                    + SOAEK_ANNUAL_MAX.toPlainString() + " USD for license_type="
                    + LICENSE_SOAEK_HAEOEMONG + " (소액해외송금업), was: "
                    + cmd.annualCapUsd().toPlainString());
        }
    }

    /** {@code low <= high} when both present; 400 naming both fields otherwise. */
    private static void requireOrdered(String lowField, BigDecimal low,
                                       String highField, BigDecimal high) {
        if (low != null && high != null && low.compareTo(high) > 0) {
            throw badRequest(lowField + " (" + low.toPlainString()
                    + ") must not exceed " + highField + " (" + high.toPlainString() + ")");
        }
    }
}
