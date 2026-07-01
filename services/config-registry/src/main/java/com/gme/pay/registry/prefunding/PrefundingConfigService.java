package com.gme.pay.registry.prefunding;

import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PrefundingConfigView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.bank.BankAccountEntity;
import com.gme.pay.registry.bank.BankAccountPurpose;
import com.gme.pay.registry.bank.BankAccountRepository;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.registry.prefunding.push.CreditLimitPusher;
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
 * Slice 5 — owns the {@code partner_prefunding_config} child aggregate (V015)
 * behind the wizard's step-5 prefunding endpoints (see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 5 — Prefunding").
 *
 * <h2>Step-5 upsert semantics</h2>
 *
 * <p>The wizard's contract is "send the full prefunding panel on every save",
 * so a PATCH is a <b>full-state replace</b>: inside one transaction the
 * current config row (if any) is superseded ({@code superseded_at = now}) and
 * a fresh row is inserted ({@code recorded_at = now}), both halves sharing the
 * same MICROS-truncated instant — the SCD-6 paired-write discipline of
 * {@code SettlementConfigService.upsertStep4Settlement} (ADR-010).
 *
 * <p>During ONBOARDING these writes go direct (audited). Post-activation
 * prefunding changes ride the change_request approval flow with the Slice 8
 * FSM — until then, non-ONBOARDING partners get a 409 here, same as every
 * other step service.
 *
 * <h2>Money ({@code docs/MONEY_CONVENTION.md})</h2>
 *
 * <p>Every {@code *Usd} field is {@link BigDecimal} in major USD units, at
 * most 4 decimal places (NUMERIC(19,4)). The service normalises accepted
 * values to scale 4 before persisting so stored == in-memory on both engines
 * and the audit snapshot bytes are deterministic.
 *
 * <h2>Top-up account validation</h2>
 *
 * <p>{@code floatTopUpBankAccountId} must reference a CURRENT
 * {@code partner_bank_account} row (V012) of THIS partner with
 * {@code purpose=FLOAT_TOPUP} — checked through {@link BankAccountRepository}
 * at write time. The reference is loose (no DB FK; the referenced row id is
 * superseded on every bank-account bulk replace), so the write-time check is
 * the integrity seam.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_prefunding_config"},
 * keyed by the partner business code, BEFORE/AFTER = {@link PrefundingJson}
 * canonical snapshots, published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code PartnerStore} / {@code SettlementConfigService}.
 */
@Service
public class PrefundingConfigService {

    /** Aggregate-type discriminator on audit rows for prefunding-config mutations. */
    public static final String AGGREGATE_TYPE = "partner_prefunding_config";

    /** Audit verb for the step-5 prefunding full-state replace. */
    public static final String EVENT_TYPE_SAVED = "PARTNER_PREFUNDING_CONFIG_SAVED";

    /** V015 CHECK roster for funding_model. */
    static final Set<String> FUNDING_MODELS = Set.of("PREFUNDED", "POSTPAID", "HYBRID");

    /** Defaults mirroring the V015 column DEFAULTs (applied to null command fields). */
    static final BigDecimal DEFAULT_LOW_BALANCE_THRESHOLD_USD = new BigDecimal("10000");
    static final String DEFAULT_TOP_UP_REFERENCE_PATTERN = "GMP-{partner_code}-{yyyyMMdd}";

    /** The placeholder every top-up reference pattern must carry (auto-reconciliation key). */
    static final String PARTNER_CODE_PLACEHOLDER = "{partner_code}";

    /** NUMERIC(19,4): at most 4 decimal places, at most 15 integer digits. */
    static final int MONEY_SCALE = 4;
    static final int MONEY_MAX_INTEGER_DIGITS = 15;

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final PrefundingConfigRepository configRepository;
    private final BankAccountRepository bankAccountRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;
    private final CreditLimitPusher creditLimitPusher;

    public PrefundingConfigService(PrefundingConfigRepository configRepository,
                                   BankAccountRepository bankAccountRepository,
                                   PartnerRepository partnerRepository,
                                   ObjectProvider<AuditLogService> auditLogProvider,
                                   CreditLimitPusher creditLimitPusher) {
        this.configRepository = configRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
        this.creditLimitPusher = creditLimitPusher;
    }

    /**
     * Full-state replace of the prefunding config on a draft partner (wizard
     * step-5 "Next").
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner has left {@code ONBOARDING} (post-activation
     *         prefunding changes wait for the Slice 8 approval flow);
     *         400 on validation failure (bad model roster / non-positive
     *         threshold / pattern without {@code {partner_code}} / top-up
     *         account that is not a current FLOAT_TOPUP row of this partner).
     */
    @Transactional
    public PrefundingConfigView upsertStep5(String partnerCode,
                                            PartnerCommand.UpdateStep5 cmd,
                                            String actor) {
        if (cmd == null) {
            throw badRequest("request body required");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-5 prefunding edits are only permitted while ONBOARDING");
        }
        validate(cmd);
        validateTopUpAccount(partner.getId(), partnerCode, cmd.floatTopUpBankAccountId());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<PrefundingConfigEntity> priorOpt =
                configRepository.findCurrentByPartnerId(partner.getId());

        PrefundingConfigEntity fresh = new PrefundingConfigEntity();
        fresh.setPartnerId(partner.getId());
        fresh.setFundingModel(cmd.fundingModel());
        fresh.setOpeningBalanceUsd(normalizeMoney(cmd.openingBalanceUsd()));
        fresh.setLowBalanceThresholdUsd(normalizeMoney(
                cmd.lowBalanceThresholdUsd() == null
                        ? DEFAULT_LOW_BALANCE_THRESHOLD_USD
                        : cmd.lowBalanceThresholdUsd()));
        fresh.setAlertTier70(cmd.alertTier70() == null || cmd.alertTier70());
        fresh.setAlertTier85(cmd.alertTier85() == null || cmd.alertTier85());
        fresh.setAlertTier95(cmd.alertTier95() == null || cmd.alertTier95());
        fresh.setCreditLimitUsd(normalizeMoney(cmd.creditLimitUsd()));
        fresh.setAutoSuspendOnBreach(
                cmd.autoSuspendOnBreach() == null || cmd.autoSuspendOnBreach());
        fresh.setFloatTopUpBankAccountId(cmd.floatTopUpBankAccountId());
        fresh.setTopUpReferencePattern(
                cmd.topUpReferencePattern() == null || cmd.topUpReferencePattern().isBlank()
                        ? DEFAULT_TOP_UP_REFERENCE_PATTERN
                        : cmd.topUpReferencePattern());
        fresh.setCollateralAmountUsd(normalizeMoney(cmd.collateralAmountUsd()));

        PrefundingConfigEntity saved = pairedWrite(priorOpt.orElse(null), fresh, now);
        publishAudit(partnerCode, actor,
                priorOpt.map(PrefundingJson::canonical).orElse(null),
                PrefundingJson.canonical(saved));
        // Wave-3 (IR-pf-2): the credit line just changed — push the merged
        // credit-limit + AML caps to prefunding (gated; no-op by default).
        creditLimitPusher.pushFor(partner);
        return saved.toView();
    }

    /**
     * The CURRENT prefunding config for the given partner code.
     *
     * @throws ResponseStatusException 404 when the partner code is unknown OR
     *         when the partner has no prefunding config yet (the wizard treats
     *         both as "nothing to rehydrate"; the message disambiguates).
     */
    @Transactional(readOnly = true)
    public PrefundingConfigView currentConfig(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return configRepository.findCurrentByPartnerId(partner.getId())
                .map(PrefundingConfigEntity::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no prefunding config for partner '" + partnerCode + "'"));
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
     * discipline as {@code PartnerStore.save} / {@code SettlementConfigService}.
     */
    private PrefundingConfigEntity pairedWrite(PrefundingConfigEntity prior,
                                               PrefundingConfigEntity fresh,
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
     * fail-fast discipline as {@code SettlementConfigService.validate}). The
     * top-up account reference is checked separately (it needs the partner's
     * surrogate id).
     */
    private static void validate(PartnerCommand.UpdateStep5 cmd) {
        if (cmd.fundingModel() == null || cmd.fundingModel().isBlank()) {
            throw badRequest("fundingModel is required and must be one of " + FUNDING_MODELS);
        }
        if (!FUNDING_MODELS.contains(cmd.fundingModel())) {
            throw badRequest("fundingModel must be one of " + FUNDING_MODELS
                    + ", was: " + cmd.fundingModel());
        }
        validateMoney("openingBalanceUsd", cmd.openingBalanceUsd(), false);
        validateMoney("lowBalanceThresholdUsd", cmd.lowBalanceThresholdUsd(), true);
        validateMoney("creditLimitUsd", cmd.creditLimitUsd(), false);
        validateMoney("collateralAmountUsd", cmd.collateralAmountUsd(), false);
        if (cmd.topUpReferencePattern() != null && !cmd.topUpReferencePattern().isBlank()) {
            if (cmd.topUpReferencePattern().length() > 60) {
                throw badRequest("topUpReferencePattern must be at most 60 characters");
            }
            if (!cmd.topUpReferencePattern().contains(PARTNER_CODE_PLACEHOLDER)) {
                throw badRequest("topUpReferencePattern must contain the "
                        + PARTNER_CODE_PLACEHOLDER + " placeholder"
                        + " (top-up wires auto-reconcile on it), was: "
                        + cmd.topUpReferencePattern());
            }
        }
        if (cmd.floatTopUpBankAccountId() != null && cmd.floatTopUpBankAccountId() <= 0) {
            throw badRequest("floatTopUpBankAccountId must be a positive bank-account id");
        }
    }

    /**
     * One money field against the MONEY_CONVENTION envelope: NUMERIC(19,4)
     * shape (≤ 4 decimal places, ≤ 15 integer digits) and the sign rule —
     * strictly positive for the threshold, non-negative for the rest.
     */
    private static void validateMoney(String field, BigDecimal value, boolean strictlyPositive) {
        if (value == null) {
            return;
        }
        if (strictlyPositive && value.signum() <= 0) {
            throw badRequest(field + " must be greater than 0, was: " + value.toPlainString());
        }
        if (!strictlyPositive && value.signum() < 0) {
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
     * The top-up account must be a CURRENT bank-account row of THIS partner
     * with {@code purpose=FLOAT_TOPUP} (V012). Anything else — unknown id,
     * another partner's row, a superseded row, or a PAYOUT/REFUND row — is a
     * 400 with a message that names the failure.
     */
    private void validateTopUpAccount(Long partnerId, String partnerCode, Long accountId) {
        if (accountId == null) {
            return;
        }
        BankAccountEntity account = bankAccountRepository.findCurrentByPartnerId(partnerId)
                .stream()
                .filter(b -> accountId.equals(b.getId()))
                .findFirst()
                .orElseThrow(() -> badRequest("floatTopUpBankAccountId " + accountId
                        + " is not a current bank account of partner '" + partnerCode + "'"));
        if (account.getPurpose() != BankAccountPurpose.FLOAT_TOPUP) {
            throw badRequest("floatTopUpBankAccountId " + accountId
                    + " must reference a bank account with purpose=FLOAT_TOPUP,"
                    + " but its purpose is " + account.getPurpose());
        }
    }

    /**
     * Normalise an accepted money value to scale 4 so the persisted
     * NUMERIC(19,4) equals the in-memory value on both engines and the
     * {@link PrefundingJson} audit bytes are deterministic. Values arrive
     * pre-validated (≤ 4 dp), so the setScale never rounds.
     */
    private static BigDecimal normalizeMoney(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
