package com.gme.pay.registry.kyb;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.contracts.KybCommand;
import com.gme.pay.contracts.KybView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.UboView;
import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 3 — owns the {@code partner_kyb} child aggregate (V011) behind the
 * wizard's step-3 endpoints and the screening trigger.
 *
 * <h2>Step-3 upsert semantics</h2>
 *
 * <p>The wizard's contract is "send the full step-3 state on every save", so a
 * PATCH is a <b>full-state replace</b> of the operator-editable fields: inside
 * one transaction the current KYB row (if any) is superseded
 * ({@code superseded_at = now}) and a fresh row is inserted
 * ({@code recorded_at = now}), both halves sharing the same MICROS-truncated
 * instant — the SCD-6 paired-write discipline of {@code PartnerStore.save}
 * (ADR-010). Screening fields are NOT operator-editable: they are carried
 * forward from the superseded row so a wizard save can never erase a
 * screening verdict.
 *
 * <h2>Screening (ADR-009)</h2>
 *
 * <p>{@link #runScreening} assembles the {@link KybSubject} from what the
 * registry already stores (partner identity columns + declared UBO set) —
 * callers cannot screen a subject that differs from the aggregate — and calls
 * the kyb-adapter seam ({@link KybScreeningClient}: REST in production, the
 * in-process stub by default). The verdict lands on a fresh SCD-6 row.
 * Screening is NOT gated on ONBOARDING: KoFIU/FSS expect rescreens for LIVE
 * partners too, and the result is evidence, not a status transition (ADR-009:
 * "can this partner transact" stays a 4-eyes operator decision).
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_kyb"}, keyed by
 * the partner business code, BEFORE/AFTER = {@link KybJson} canonical
 * snapshots, published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code PartnerStore} / {@code PartnerContactService}.
 */
@Service
public class KybService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(KybService.class);

    /** Aggregate-type discriminator on audit rows for KYB mutations. */
    public static final String AGGREGATE_TYPE = "partner_kyb";

    /** Audit verb for the step-3 full-state replace. */
    public static final String EVENT_TYPE_SAVED = "PARTNER_KYB_SAVED";

    /** Audit verb for a screening run landing on the row. */
    public static final String EVENT_TYPE_SCREENED = "PARTNER_KYB_SCREENED";

    /** V011 CHECK roster for risk_rating. */
    static final Set<String> RISK_RATINGS = Set.of("LOW", "MEDIUM", "HIGH");

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = AuditActors.UNATTRIBUTED;

    /** Audit verb for a full verify run landing on the row. */
    public static final String EVENT_TYPE_VERIFIED = "PARTNER_KYB_VERIFIED";

    private final KybRepository kybRepository;
    private final PartnerRepository partnerRepository;
    private final KybScreeningClient screeningClient;
    private final KybVerifyClient verifyClient;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public KybService(KybRepository kybRepository,
                      PartnerRepository partnerRepository,
                      KybScreeningClient screeningClient,
                      KybVerifyClient verifyClient,
                      ObjectProvider<AuditLogService> auditLogProvider) {
        this.kybRepository = kybRepository;
        this.partnerRepository = partnerRepository;
        this.screeningClient = screeningClient;
        this.verifyClient = verifyClient;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Full-state replace of the operator-editable KYB fields on a draft
     * partner (wizard step-3 "Next").
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner has left {@code ONBOARDING} (post-activation
     *         KYB changes go through the change_request 4-eyes path);
     *         400 on validation failure.
     */
    @Transactional
    public KybView upsertStep3(String partnerCode, KybCommand.UpdateStep3 cmd, String actor) {
        if (cmd == null) {
            throw badRequest("request body required");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-3 edits are only permitted while ONBOARDING");
        }
        validate(cmd);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<KybEntity> priorOpt = kybRepository.findCurrentByPartnerId(partner.getId());

        KybEntity fresh = new KybEntity();
        fresh.setPartnerId(partner.getId());
        applyStep3Fields(fresh, cmd);
        priorOpt.ifPresent(prior -> carryForwardScreening(prior, fresh));

        KybEntity saved = pairedWrite(priorOpt.orElse(null), fresh, now);
        publishAudit(partnerCode, actor, EVENT_TYPE_SAVED,
                priorOpt.map(KybJson::canonical).orElse(null), KybJson.canonical(saved));
        return saved.toView();
    }

    /**
     * Run sanctions screening for the partner via the kyb-adapter seam and
     * store the verdict on a fresh SCD-6 row. Works with or without a prior
     * step-3 save: with none, the fresh row carries only the screening fields
     * (the wizard backfills the rest on its next save).
     *
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         upstream 4xx/502 from {@link RestKybClient} pass through.
     */
    @Transactional
    public KybView runScreening(String partnerCode, String actor) {
        PartnerEntity partner = requirePartner(partnerCode);
        Optional<KybEntity> priorOpt = kybRepository.findCurrentByPartnerId(partner.getId());

        ScreeningResult result = screeningClient.screen(toSubject(partner, priorOpt.orElse(null)));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        KybEntity fresh = new KybEntity();
        fresh.setPartnerId(partner.getId());
        priorOpt.ifPresent(prior -> copyStep3Fields(prior, fresh));
        fresh.setScreeningStatus(result.status() == null ? null : result.status().name());
        fresh.setScreeningProviderRef(result.providerRef());
        // T1-4: WHO screened, and whether they are an authority, is stored with the
        // verdict. lib-kyb guarantees a non-authoritative run cannot arrive as
        // CLEAR, so this row cannot claim a clean screening nobody performed.
        applyProvenance(fresh, result.provenance().providerId(),
                result.authoritative(), result.caveat());
        // Defensive truncation: the stub already truncates, but a vendor
        // adapter may not — the stored TIMESTAMP must equal the entity value.
        fresh.setScreenedAt(result.screenedAt() == null
                ? now : result.screenedAt().truncatedTo(ChronoUnit.MICROS));
        if (!result.screeningPerformed()) {
            log.warn("partner {} was NOT SCREENED: provider={} status={} — activation cannot treat"
                            + " this as a satisfied sanctions pre-condition. {}",
                    partnerCode, result.provenance().providerId(), result.status(), result.caveat());
        }

        KybEntity saved = pairedWrite(priorOpt.orElse(null), fresh, now);
        publishAudit(partnerCode, actor, EVENT_TYPE_SCREENED,
                priorOpt.map(KybJson::canonical).orElse(null), KybJson.canonical(saved));
        return saved.toView();
    }

    /**
     * Wave-3 — run a FULL KYB verification for the partner via the kyb-adapter
     * verify seam ({@code POST /v1/kyb/verify}) and store the collapsed decision
     * (+ provider ref + screening status) on a fresh SCD-6 row. The
     * document-aware counterpart to {@link #runScreening}: the wizard's KYB step
     * calls this when the operator has attached the onboarding document pack.
     *
     * <p>The subject is assembled from the stored aggregate (callers cannot
     * verify a subject that differs from what the registry holds); the supplied
     * document types are passed through so kyb-adapter can downgrade a clean run
     * to MANUAL_REVIEW when a required document is missing.
     *
     * @param suppliedDocuments document types the operator attached; may be null.
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         upstream 4xx/502 from {@link RestKybVerifyClient} pass through.
     */
    @Transactional
    public KybView runVerification(String partnerCode, List<String> suppliedDocuments,
                                   boolean force, String actor) {
        PartnerEntity partner = requirePartner(partnerCode);
        Optional<KybEntity> priorOpt = kybRepository.findCurrentByPartnerId(partner.getId());

        KybVerificationResult result = verifyClient.verify(new KybVerificationRequest(
                toSubject(partner, priorOpt.orElse(null)), suppliedDocuments, force));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        KybEntity fresh = new KybEntity();
        fresh.setPartnerId(partner.getId());
        priorOpt.ifPresent(prior -> copyStep3Fields(prior, fresh));
        fresh.setScreeningStatus(result.screeningStatus());
        fresh.setScreeningProviderRef(result.providerRef());
        fresh.setScreenedAt(result.screenedAt() == null
                ? now : result.screenedAt().truncatedTo(ChronoUnit.MICROS));
        fresh.setVerificationDecision(result.decision());
        fresh.setVerificationDecisionReason(result.decisionReason());
        // T1-4: the verify result carries the same provenance as the screen result
        // (kyb-adapter threads it through), so a stored verification decision can
        // never be read as resting on a screening that did not happen.
        applyProvenance(fresh, result.screeningProviderId(),
                result.screeningAuthoritative(), result.screeningCaveat());
        if (!fresh.hasAuthoritativeScreening()) {
            log.warn("partner {} verification decision {} rests on NO SANCTIONS SCREENING"
                            + " (provider={}, screeningStatus={}). {}",
                    partnerCode, result.decision(), result.screeningProviderId(),
                    result.screeningStatus(), result.screeningCaveat());
        }

        KybEntity saved = pairedWrite(priorOpt.orElse(null), fresh, now);
        publishAudit(partnerCode, actor, EVENT_TYPE_VERIFIED,
                priorOpt.map(KybJson::canonical).orElse(null), KybJson.canonical(saved));
        return saved.toView();
    }

    /**
     * The CURRENT KYB view for the given partner code.
     *
     * @throws ResponseStatusException 404 when the partner code is unknown OR
     *         when the partner has no KYB row yet (the wizard treats both as
     *         "nothing to rehydrate"; the message disambiguates).
     */
    @Transactional(readOnly = true)
    public KybView currentKyb(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return kybRepository.findCurrentByPartnerId(partner.getId())
                .map(KybEntity::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no KYB data for partner '" + partnerCode + "'"));
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
     * instant. flush() forces the UPDATE out before the INSERT so the write
     * order in the database matches the close-out-then-open narrative — same
     * ordering discipline as {@code PartnerStore.save}.
     */
    private KybEntity pairedWrite(KybEntity prior, KybEntity fresh, Instant now) {
        fresh.setRecordedAt(now);
        if (prior != null) {
            // Business time stays continuous across transaction-time writes
            // (same carry-forward as PartnerStore.save).
            fresh.setValidFrom(prior.getValidFrom());
            fresh.setValidTo(prior.getValidTo());
            prior.setSupersededAt(now);
            kybRepository.saveAndFlush(prior);
        } else {
            fresh.setValidFrom(now);
        }
        return kybRepository.saveAndFlush(fresh);
    }

    /** ADR-007 audit row, same-transaction (commits iff the KYB write commits). */
    private void publishAudit(String partnerCode, String actor, String eventType,
                              byte[] before, byte[] after) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(
                    AGGREGATE_TYPE,
                    partnerCode,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null,
                    eventType,
                    before,
                    after);
        }
    }

    /** Stamp the operator-editable step-3 fields from the command onto a fresh row. */
    private static void applyStep3Fields(KybEntity e, KybCommand.UpdateStep3 cmd) {
        e.setRiskRating(blankToNull(cmd.riskRating()));
        e.setRiskRationale(blankToNull(cmd.riskRationale()));
        e.setNextReviewDate(cmd.nextReviewDate());
        e.setLicenseType(blankToNull(cmd.licenseType()));
        e.setLicenseNumber(blankToNull(cmd.licenseNumber()));
        e.setLicenseAuthority(blankToNull(cmd.licenseAuthority()));
        e.setLicenseExpiry(cmd.licenseExpiry());
        e.setUboSetJson(KybJson.canonicalUbos(cmd.uboList()));
        e.setCbddqDocId(cmd.cbddqDocId());
    }

    /** Copy the operator-editable fields between rows (screening path keeps step-3 state). */
    private static void copyStep3Fields(KybEntity from, KybEntity to) {
        to.setRiskRating(from.getRiskRating());
        to.setRiskRationale(from.getRiskRationale());
        to.setNextReviewDate(from.getNextReviewDate());
        to.setLicenseType(from.getLicenseType());
        to.setLicenseNumber(from.getLicenseNumber());
        to.setLicenseAuthority(from.getLicenseAuthority());
        to.setLicenseExpiry(from.getLicenseExpiry());
        to.setUboSetJson(from.getUboSetJson());
        to.setCbddqDocId(from.getCbddqDocId());
    }

    /**
     * Copy the screening + verify verdict between rows (step-3 saves keep
     * screening/verification state — a wizard save must never erase a verdict).
     *
     * <p>T1-4: the provenance travels with the verdict. If it did not, a wizard
     * save would carry a CLEAR forward while dropping the authority that vouched
     * for it — the V042 CHECK would then reject the row, which is the right
     * failure mode but a needless one.
     */
    private static void carryForwardScreening(KybEntity from, KybEntity to) {
        to.setScreeningStatus(from.getScreeningStatus());
        to.setScreeningProviderRef(from.getScreeningProviderRef());
        to.setScreenedAt(from.getScreenedAt());
        to.setVerificationDecision(from.getVerificationDecision());
        to.setVerificationDecisionReason(from.getVerificationDecisionReason());
        to.setScreeningProviderId(from.getScreeningProviderId());
        to.setScreeningAuthoritative(from.getScreeningAuthoritative());
        to.setScreeningCaveat(from.getScreeningCaveat());
    }

    /**
     * Stamp a run's provenance onto the fresh row. An absent provider id is
     * recorded as the explicitly non-authoritative {@code unknown} producer with
     * its caveat rather than left blank: a blank could be misread as "no opinion",
     * and the whole point of T1-4 is that absence of provenance is never authority.
     */
    private static void applyProvenance(KybEntity row, String providerId,
                                        boolean authoritative, String caveat) {
        if (providerId == null || providerId.isBlank()) {
            row.setScreeningProviderId(ScreeningProvenance.UNKNOWN_PROVIDER_ID);
            row.setScreeningAuthoritative(Boolean.FALSE);
            row.setScreeningCaveat(ScreeningProvenance.UNKNOWN_CAVEAT);
            return;
        }
        row.setScreeningProviderId(providerId);
        row.setScreeningAuthoritative(authoritative);
        row.setScreeningCaveat(authoritative ? null
                : (caveat == null || caveat.isBlank() ? ScreeningProvenance.UNKNOWN_CAVEAT : caveat));
    }

    /** Project the stored aggregate into the vendor-agnostic screening subject. */
    private static KybSubject toSubject(PartnerEntity partner, KybEntity kyb) {
        List<KybSubject.Ubo> ubos = new ArrayList<>();
        List<UboView> declared = kyb == null ? null : KybJson.parseUbos(kyb.getUboSetJson());
        if (declared != null) {
            for (UboView u : declared) {
                ubos.add(new KybSubject.Ubo(
                        u.name(), u.ownershipPct(), Boolean.TRUE.equals(u.isPep()), u.country()));
            }
        }
        return new KybSubject(
                partner.getPartnerCode(),
                partner.getLegalNameLocal(),
                partner.getLegalNameRomanized(),
                partner.getCountryOfIncorporation(),
                partner.getTaxId(),
                List.copyOf(ubos));
    }

    /**
     * Field-format validation, whole payload before any row is touched (same
     * fail-fast discipline as {@code PartnerContactService}). UBO messages are
     * index-qualified ({@code uboList[2].name ...}) for the multi-row editor.
     */
    private static void validate(KybCommand.UpdateStep3 cmd) {
        if (cmd.riskRating() != null && !cmd.riskRating().isBlank()
                && !RISK_RATINGS.contains(cmd.riskRating())) {
            throw badRequest("riskRating must be one of " + RISK_RATINGS
                    + ", was: " + cmd.riskRating());
        }
        if (cmd.riskRationale() != null && cmd.riskRationale().length() > 1000) {
            throw badRequest("riskRationale must be at most 1000 characters");
        }
        checkLength("licenseType", cmd.licenseType(), 50);
        checkLength("licenseNumber", cmd.licenseNumber(), 50);
        checkLength("licenseAuthority", cmd.licenseAuthority(), 100);
        if (cmd.uboList() != null) {
            for (int i = 0; i < cmd.uboList().size(); i++) {
                validateUbo(cmd.uboList().get(i), i);
            }
        }
    }

    private static void validateUbo(UboView ubo, int index) {
        String at = "uboList[" + index + "]";
        if (ubo == null) {
            throw badRequest(at + " must be an object");
        }
        if (ubo.name() == null || ubo.name().isBlank()) {
            throw badRequest(at + ".name is required");
        }
        if (ubo.name().length() > 120) {
            throw badRequest(at + ".name must be at most 120 characters");
        }
        if (ubo.ownershipPct() != null
                && (ubo.ownershipPct().compareTo(BigDecimal.ZERO) < 0
                    || ubo.ownershipPct().compareTo(new BigDecimal("100")) > 0)) {
            throw badRequest(at + ".ownershipPct must be between 0 and 100, was: "
                    + ubo.ownershipPct().toPlainString());
        }
        if (ubo.country() != null && !ubo.country().isBlank()
                && !ubo.country().matches("[A-Z]{2}")) {
            throw badRequest(at + ".country must be ISO-3166 alpha-2 (e.g. KR), was: "
                    + ubo.country());
        }
    }

    private static void checkLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw badRequest(field + " must be at most " + max + " characters");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
