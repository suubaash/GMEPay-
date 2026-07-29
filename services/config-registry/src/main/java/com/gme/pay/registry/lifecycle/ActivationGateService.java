package com.gme.pay.registry.lifecycle;

import com.gme.pay.contracts.ActivationGateView;
import com.gme.pay.contracts.UnmetConditionView;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.bank.BankAccountEntity;
import com.gme.pay.registry.bank.BankAccountRepository;
import com.gme.pay.registry.bank.BankVerificationStatus;
import com.gme.pay.registry.commercial.ContractEntity;
import com.gme.pay.registry.commercial.ContractRepository;
import com.gme.pay.registry.contact.ContactEntity;
import com.gme.pay.registry.contact.ContactRepository;
import com.gme.pay.registry.contact.ContactRole;
import com.gme.pay.registry.kyb.KybEntity;
import com.gme.pay.registry.kyb.KybRepository;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.prefunding.PrefundingConfigRepository;
import com.gme.pay.registry.scheme.PartnerSchemeEntity;
import com.gme.pay.registry.scheme.PartnerSchemeRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice 8 activation pre-condition gate (ADR-011): the pure decision function
 * behind {@code UAT → LIVE}. Reads the partner aggregate + its child rows and
 * answers "may this partner go live?" with an itemised list of unmet
 * conditions — it NEVER mutates anything.
 *
 * <p>Evaluated twice per activation: non-mutating via
 * {@code GET /v1/admin/partners/{code}/lifecycle/preconditions} (the Admin UI
 * checklist) and decisively inside the 4-eyes approval of the ACTIVATE
 * change_request (422 + unmet[] when it fails; the change_request stays
 * PROPOSED so the partner can be fixed and re-approved).
 *
 * <h2>Condition roster</h2>
 *
 * <ol>
 *   <li>{@link #LEGAL_NAME_MISSING} — legal_name_local + legal_name_romanized
 *       both non-blank.</li>
 *   <li>{@link #KYB_NOT_APPROVED} — current partner_kyb row exists with
 *       risk_rating LOW/MEDIUM (the plan doc's GREEN/AMBER), or HIGH carrying
 *       an operator override note ({@code risk_rationale}).</li>
 *   <li>{@link #SANCTIONS_NOT_SCREENED} — a screening was actually PERFORMED by an
 *       authoritative provider. <b>Not overridable</b> (see below).</li>
 *   <li>{@link #SANCTIONS_NOT_CLEAR} — that screening came back CLEAR, or an
 *       operator override note on the KYB row explains why a HIT / NEEDS_REVIEW
 *       is acceptable.</li>
 *   <li>{@link #BANK_ACCOUNT_UNVERIFIED} — ≥1 current bank account in the
 *       settle_a_ccy whose verification_status is not UNVERIFIED.</li>
 *   <li>{@link #CONTRACT_MISSING} / {@link #CONTRACT_NOT_SIGNED} /
 *       {@link #CONTRACT_NOT_EFFECTIVE} — current partner_contract row with
 *       signed_at non-null and effective_from ≤ today.</li>
 *   <li>{@link #PREFUNDING_MISSING} / {@link #PREFUNDING_NOT_APPLICABLE} —
 *       prefunding config exists IFF partner_type = OVERSEAS.</li>
 *   <li>{@link #CONTACT_ROLES_INSUFFICIENT} — ≥4 distinct {@link ContactRole}s
 *       covered by current contacts.</li>
 *   <li>{@link #SCHEME_MISSING} — ≥1 current partner_scheme row with
 *       enabled = TRUE.</li>
 * </ol>
 *
 * <h2>An unscreened partner cannot be activated (gap T1-4)</h2>
 *
 * <p>The sanctions pre-condition used to be satisfied by the literal string
 * {@code "CLEAR"} in {@code partner_kyb.screening_status}. Because no environment
 * set {@code GMEPAY_KYB_ADAPTER_CLIENT}, that string was written by the in-process
 * {@code StubKybAdapter} — keyword matching on the partner's own names, consulting
 * no sanctions, PEP or adverse-media source. Any partner not literally named
 * "SANCTIONED …" therefore arrived at this gate with a passed sanctions check that
 * no screening had produced.
 *
 * <p>The gate now requires the KYB row to carry AUTHORITATIVE provenance
 * ({@link com.gme.pay.registry.kyb.KybEntity#hasAuthoritativeScreening()}).
 * Crucially, {@link #SANCTIONS_NOT_SCREENED} is <b>not</b> overridable by the
 * {@code risk_rationale} note: a documented rationale can explain why a real
 * HIT is acceptable, but no note can substitute for a screening that never ran.
 *
 * <p>Local / dev onboarding would otherwise be impossible (there is no vendor to
 * screen with — ADR-014). The single escape hatch is
 * {@code gmepay.activation.allow-unscreened-kyb=true}, which is <b>false by
 * default and must never be true in production</b>. When it is on the gate still
 * refuses to call the partner screened: it passes the condition but returns a
 * non-null {@link ActivationGateResult#unscreenedBasis()}, which
 * {@code PartnerLifecycleChangeRequestApplier} writes to the ADR-007 audit log as
 * a {@code PARTNER_ACTIVATED_UNSCREENED} event — so an activation that proceeded
 * without a sanctions check says so, permanently, in the regulated record.
 */
@Service
public class ActivationGateService {

    private static final Logger log = LoggerFactory.getLogger(ActivationGateService.class);

    public static final String LEGAL_NAME_MISSING = "LEGAL_NAME_MISSING";
    public static final String KYB_NOT_APPROVED = "KYB_NOT_APPROVED";
    public static final String SANCTIONS_NOT_CLEAR = "SANCTIONS_NOT_CLEAR";

    /**
     * No sanctions screening has been PERFORMED by an authoritative provider —
     * distinct from {@link #SANCTIONS_NOT_CLEAR} (a screening ran and found
     * something). Deliberately not satisfiable by an operator override note.
     */
    public static final String SANCTIONS_NOT_SCREENED = "SANCTIONS_NOT_SCREENED";
    public static final String BANK_ACCOUNT_UNVERIFIED = "BANK_ACCOUNT_UNVERIFIED";
    public static final String CONTRACT_MISSING = "CONTRACT_MISSING";
    public static final String CONTRACT_NOT_SIGNED = "CONTRACT_NOT_SIGNED";
    public static final String CONTRACT_NOT_EFFECTIVE = "CONTRACT_NOT_EFFECTIVE";
    public static final String PREFUNDING_MISSING = "PREFUNDING_MISSING";
    public static final String PREFUNDING_NOT_APPLICABLE = "PREFUNDING_NOT_APPLICABLE";
    public static final String CONTACT_ROLES_INSUFFICIENT = "CONTACT_ROLES_INSUFFICIENT";
    public static final String SCHEME_MISSING = "SCHEME_MISSING";

    /** Distinct contact roles required before Go-live (the ADR-011 roster minimum). */
    static final int REQUIRED_DISTINCT_CONTACT_ROLES = 4;

    /** Risk ratings the plan doc calls GREEN/AMBER — approvable without override. */
    private static final Set<String> APPROVABLE_RISK_RATINGS = Set.of("LOW", "MEDIUM");

    private final KybRepository kybRepository;
    private final BankAccountRepository bankAccountRepository;
    private final ContactRepository contactRepository;
    private final ContractRepository contractRepository;
    private final PrefundingConfigRepository prefundingConfigRepository;
    private final PartnerSchemeRepository partnerSchemeRepository;

    /**
     * NON-PRODUCTION escape hatch (T1-4). {@code false} by default: without a real
     * KYB provider the sanctions pre-condition cannot be met and activation is
     * refused. Turning it on does NOT make the partner screened — it records that
     * activation proceeded on an unscreened basis.
     */
    private final boolean allowUnscreenedKyb;

    public ActivationGateService(KybRepository kybRepository,
                                 BankAccountRepository bankAccountRepository,
                                 ContactRepository contactRepository,
                                 ContractRepository contractRepository,
                                 PrefundingConfigRepository prefundingConfigRepository,
                                 PartnerSchemeRepository partnerSchemeRepository,
                                 @Value("${gmepay.activation.allow-unscreened-kyb:false}")
                                 boolean allowUnscreenedKyb) {
        this.kybRepository = kybRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.contactRepository = contactRepository;
        this.contractRepository = contractRepository;
        this.prefundingConfigRepository = prefundingConfigRepository;
        this.partnerSchemeRepository = partnerSchemeRepository;
        this.allowUnscreenedKyb = allowUnscreenedKyb;
        if (allowUnscreenedKyb) {
            log.warn("gmepay.activation.allow-unscreened-kyb=true — partners may be ACTIVATED"
                    + " WITHOUT A SANCTIONS SCREENING. Every such activation is recorded as"
                    + " PARTNER_ACTIVATED_UNSCREENED in the audit log. This must be false in"
                    + " production.");
        }
    }

    /**
     * One unmet pre-condition. Service-layer twin of the wire
     * {@link UnmetConditionView}; kept separate so the gate's internals never
     * leak wire-shape concerns and vice versa.
     */
    public record UnmetCondition(String code, String description) {
    }

    /**
     * The gate's verdict: passes iff {@code unmet} is empty.
     *
     * @param unscreenedBasis non-null ONLY when the gate let an unscreened partner
     *        through because {@code gmepay.activation.allow-unscreened-kyb} is on
     *        (T1-4). It states, in one line, that no sanctions screening backs this
     *        activation; the applier persists it to the audit log. A passing gate
     *        with a non-null value here has NOT verified the partner's sanctions
     *        status and callers must not present it as having done so.
     */
    public record ActivationGateResult(boolean passes, List<UnmetCondition> unmet,
                                       String unscreenedBasis) {

        /** Two-arg form: a verdict with no unscreened-basis carve-out. */
        public ActivationGateResult(boolean passes, List<UnmetCondition> unmet) {
            this(passes, unmet, null);
        }

        /** Adapt to the canonical lib-api-contracts wire DTO. */
        public ActivationGateView toView() {
            return new ActivationGateView(passes,
                    unmet.stream()
                            .map(u -> new UnmetConditionView(u.code(), u.description()))
                            .toList());
        }
    }

    /**
     * Evaluate every pre-condition for the given CURRENT partner row. Pure
     * read — repeated calls with unchanged data return the same result.
     */
    @Transactional(readOnly = true)
    public ActivationGateResult check(PartnerEntity partner) {
        List<UnmetCondition> unmet = new ArrayList<>();
        checkLegalNames(partner, unmet);
        String unscreenedBasis = checkKyb(partner, unmet);
        checkBankAccounts(partner, unmet);
        checkContract(partner, unmet);
        checkPrefunding(partner, unmet);
        checkContacts(partner, unmet);
        checkSchemes(partner, unmet);
        return new ActivationGateResult(unmet.isEmpty(), List.copyOf(unmet), unscreenedBasis);
    }

    // -------------------------- Individual checks ----------------------------

    private static void checkLegalNames(PartnerEntity partner, List<UnmetCondition> unmet) {
        boolean localMissing = isBlank(partner.getLegalNameLocal());
        boolean romanizedMissing = isBlank(partner.getLegalNameRomanized());
        if (localMissing || romanizedMissing) {
            unmet.add(new UnmetCondition(LEGAL_NAME_MISSING,
                    "legal_name_local and legal_name_romanized must both be set"
                            + " (missing: "
                            + (localMissing && romanizedMissing
                                    ? "both"
                                    : localMissing ? "legal_name_local" : "legal_name_romanized")
                            + ")"));
        }
    }

    /**
     * KYB approval + sanctions clearance. The "operator override note" the plan
     * doc allows is the {@code risk_rationale} column — compliance documents WHY
     * a HIGH rating / non-CLEAR screening is acceptable, and that documented
     * rationale is what the gate honours.
     *
     * <p>T1-4: the override does NOT extend to the absence of a screening. A
     * rationale can justify accepting a HIT; nothing justifies calling an
     * un-run check passed. See the class javadoc for the non-prod carve-out.
     *
     * @return the unscreened-basis line when the carve-out let an unscreened
     *         partner through, otherwise {@code null}.
     */
    private String checkKyb(PartnerEntity partner, List<UnmetCondition> unmet) {
        Optional<KybEntity> kybOpt = kybRepository.findCurrentByPartnerId(partner.getId());
        if (kybOpt.isEmpty()) {
            unmet.add(new UnmetCondition(KYB_NOT_APPROVED,
                    "no KYB record exists for this partner (wizard step-3 never saved)"));
            unmet.add(new UnmetCondition(SANCTIONS_NOT_SCREENED,
                    "no sanctions screening has been run (no KYB record)"));
            return null;
        }
        KybEntity kyb = kybOpt.get();
        boolean hasOverrideNote = !isBlank(kyb.getRiskRationale());

        String rating = kyb.getRiskRating();
        boolean kybApproved = (rating != null && APPROVABLE_RISK_RATINGS.contains(rating))
                || (rating != null && hasOverrideNote);
        if (!kybApproved) {
            unmet.add(new UnmetCondition(KYB_NOT_APPROVED,
                    rating == null
                            ? "KYB risk rating has not been assessed"
                            : "KYB risk rating is " + rating
                                    + " without an operator override note (risk_rationale)"));
        }

        // ---- Was a screening PERFORMED at all? (T1-4) --------------------------
        if (!kyb.hasAuthoritativeScreening()) {
            String what = describeUnscreened(kyb);
            if (!allowUnscreenedKyb) {
                unmet.add(new UnmetCondition(SANCTIONS_NOT_SCREENED, what
                        + " No operator override can satisfy this condition — a risk rationale can"
                        + " justify accepting a screening result, not the absence of one."
                        + " Configure a real KYB provider (GMEPAY_KYB_ADAPTER_CLIENT=rest +"
                        + " gmepay.kyb.provider) and re-screen, or set"
                        + " gmepay.activation.allow-unscreened-kyb=true in a NON-PRODUCTION"
                        + " environment to activate on a recorded unscreened basis."));
                return null;
            }
            log.warn("partner {} is being allowed through the activation gate WITHOUT A SANCTIONS"
                    + " SCREENING (gmepay.activation.allow-unscreened-kyb=true). {}",
                    partner.getPartnerCode(), what);
            return "ACTIVATED WITHOUT A SANCTIONS SCREENING. " + what
                    + " Permitted only because gmepay.activation.allow-unscreened-kyb=true"
                    + " (a non-production setting). This partner's sanctions status is UNKNOWN.";
        }

        // ---- A screening ran: is it clear, or overridden? ----------------------
        boolean sanctionsClear = "CLEAR".equals(kyb.getScreeningStatus()) || hasOverrideNote;
        if (!sanctionsClear) {
            unmet.add(new UnmetCondition(SANCTIONS_NOT_CLEAR,
                    "sanctions screening status is " + kyb.getScreeningStatus()
                            + " without an operator override note (risk_rationale)"));
        }
        return null;
    }

    /** One line naming exactly what is missing, for the checklist and the audit row. */
    private static String describeUnscreened(KybEntity kyb) {
        if (kyb.getScreeningStatus() == null) {
            return "No sanctions screening has been run for this partner.";
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("The stored sanctions screening is NOT AUTHORITATIVE: status=")
                .append(kyb.getScreeningStatus())
                .append(", provider=")
                .append(kyb.getScreeningProviderId() == null
                        ? "undeclared" : kyb.getScreeningProviderId())
                .append('.');
        if (!isBlank(kyb.getScreeningCaveat())) {
            sb.append(' ').append(kyb.getScreeningCaveat());
        }
        return sb.toString();
    }

    /**
     * ≥1 VERIFIED bank account in the settlement currency. "Verified" means any
     * V012 verification rail has landed (KFTC_VERIFIED / BANK_LETTER /
     * MICRO_DEPOSIT) — i.e. anything but UNVERIFIED. The settlement currency is
     * the V016 {@code settle_a_ccy}, falling back to the legacy
     * {@code settlement_currency} mirror per the ADR-013 Expand contract.
     */
    private void checkBankAccounts(PartnerEntity partner, List<UnmetCondition> unmet) {
        String settleCcy = partner.getSettleACcy() != null
                ? partner.getSettleACcy() : partner.getSettlementCurrency();
        if (settleCcy == null) {
            unmet.add(new UnmetCondition(BANK_ACCOUNT_UNVERIFIED,
                    "no settlement currency configured, so no verified settlement"
                            + " bank account can exist"));
            return;
        }
        List<BankAccountEntity> accounts =
                bankAccountRepository.findCurrentByPartnerId(partner.getId());
        boolean verified = accounts.stream().anyMatch(a ->
                settleCcy.equals(a.getCurrency())
                        && a.getVerificationStatus() != null
                        && a.getVerificationStatus() != BankVerificationStatus.UNVERIFIED);
        if (!verified) {
            unmet.add(new UnmetCondition(BANK_ACCOUNT_UNVERIFIED,
                    "no verified bank account exists in settle_a_ccy " + settleCcy));
        }
    }

    /** Contract row exists, is countersigned, and its term has started. */
    private void checkContract(PartnerEntity partner, List<UnmetCondition> unmet) {
        Optional<ContractEntity> contractOpt =
                contractRepository.findCurrentByPartnerId(partner.getId());
        if (contractOpt.isEmpty()) {
            unmet.add(new UnmetCondition(CONTRACT_MISSING,
                    "no partner_contract row exists (wizard step-6 never saved)"));
            return;
        }
        ContractEntity contract = contractOpt.get();
        if (contract.getSignedAt() == null) {
            unmet.add(new UnmetCondition(CONTRACT_NOT_SIGNED,
                    "the contract has not been countersigned (signed_at is not set)"));
        }
        if (contract.getEffectiveFrom() != null
                && contract.getEffectiveFrom().isAfter(LocalDate.now())) {
            unmet.add(new UnmetCondition(CONTRACT_NOT_EFFECTIVE,
                    "the contract term starts " + contract.getEffectiveFrom()
                            + ", which is after today"));
        }
    }

    /**
     * Prefunding config must exist IFF the partner is OVERSEAS: an overseas
     * partner without a float cannot settle (Slice 5), and a LOCAL partner with
     * one indicates a misconfigured wizard flow that would mis-route settlement.
     */
    private void checkPrefunding(PartnerEntity partner, List<UnmetCondition> unmet) {
        boolean present = prefundingConfigRepository
                .findCurrentByPartnerId(partner.getId()).isPresent();
        if (partner.getType() == PartnerType.OVERSEAS && !present) {
            unmet.add(new UnmetCondition(PREFUNDING_MISSING,
                    "partner_type is OVERSEAS but no prefunding config exists"
                            + " (wizard step-5 never saved)"));
        } else if (partner.getType() == PartnerType.LOCAL && present) {
            unmet.add(new UnmetCondition(PREFUNDING_NOT_APPLICABLE,
                    "partner_type is LOCAL but a prefunding config exists —"
                            + " remove it before activation"));
        }
    }

    /** ≥4 distinct roles covered by the current contact set. */
    private void checkContacts(PartnerEntity partner, List<UnmetCondition> unmet) {
        List<ContactEntity> contacts =
                contactRepository.findCurrentByPartnerId(partner.getId());
        Set<ContactRole> roles = EnumSet.noneOf(ContactRole.class);
        for (ContactEntity contact : contacts) {
            if (contact.getRole() != null) {
                roles.add(contact.getRole());
            }
        }
        if (roles.size() < REQUIRED_DISTINCT_CONTACT_ROLES) {
            unmet.add(new UnmetCondition(CONTACT_ROLES_INSUFFICIENT,
                    "contacts must cover at least " + REQUIRED_DISTINCT_CONTACT_ROLES
                            + " distinct roles, currently " + roles.size()
                            + " (" + roles + ")"));
        }
    }

    /** ≥1 enabled scheme enablement. */
    private void checkSchemes(PartnerEntity partner, List<UnmetCondition> unmet) {
        List<PartnerSchemeEntity> schemes =
                partnerSchemeRepository.findAllCurrentByPartnerId(partner.getId());
        boolean anyEnabled = schemes.stream()
                .anyMatch(s -> Boolean.TRUE.equals(s.getEnabled()));
        if (!anyEnabled) {
            unmet.add(new UnmetCondition(SCHEME_MISSING,
                    "no enabled partner_scheme row exists (wizard step-7 never"
                            + " enabled a scheme)"));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
