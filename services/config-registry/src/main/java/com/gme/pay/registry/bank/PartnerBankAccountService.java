package com.gme.pay.registry.bank;

import com.gme.pay.contracts.BankAccountCommand;
import com.gme.pay.contracts.BankAccountView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.bank.verify.AccountVerificationProvider;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 4 — owns the {@code partner_bank_account} child aggregate (V012)
 * behind the wizard's step-4 endpoints and the verification trigger.
 *
 * <h2>Bulk-replace semantics</h2>
 *
 * <p>The wizard's contract is "send the full step-4 state on every save", so a
 * PATCH is a <b>bulk replace</b>: inside one transaction every current
 * bank-account row for the partner is superseded ({@code superseded_at = now})
 * and the new set is inserted ({@code recorded_at = now}), both halves sharing
 * the same MICROS-truncated instant — the SCD-6 paired-write discipline of
 * {@code PartnerContactService} (ADR-010). Sending an empty list clears all
 * accounts; {@code null} is a 400.
 *
 * <p>Verification fields are NOT operator-editable: a replace carries the
 * verdict (status / evidence doc / date) forward from a superseded row when
 * the (currency, ibanOrAccountNumber) pair is unchanged — the same
 * carry-forward rule {@code KybService} applies to screening verdicts — and
 * resets to UNVERIFIED when the coordinates changed (a re-typed account number
 * is a new account as far as the regulator is concerned).
 *
 * <h2>Validation</h2>
 *
 * <p>Server-side, before any row is touched: ISO-4217 currency shape, BIC-8/11
 * ({@code ^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$}) for {@code bicSwift} and
 * {@code intermediaryBic}, ISO 13616 mod-97 checksum WHEN the account value is
 * IBAN-shaped (two letters + two digits — KR accounts are NOT IBAN and pass as
 * raw account numbers), required-field + length caps, charge-bearer / purpose
 * rosters (V012 CHECKs), and at most ONE {@code primary} account per currency
 * across the payload. Failures surface as 400 with the offending
 * {@code bankAccounts[i]} index so the multi-row editor can highlight the row.
 *
 * <h2>Verification (port seam)</h2>
 *
 * <p>{@link #verifyBankAccount} runs the {@link AccountVerificationProvider}
 * (KFTC for KR / stub by default — the ADR-009-style port) against the stored
 * CURRENT row and records the verdict on a fresh SCD-6 row: status +
 * verification date stamped, all account coordinates copied. Like KYB
 * screening, verification is NOT gated on ONBOARDING — re-verifying a LIVE
 * partner's account produces evidence, not a status transition; mutating the
 * coordinates themselves post-activation stays locked until the Slice 8
 * 2-signatory flow.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_bank_account"},
 * keyed by the partner business code, BEFORE/AFTER = {@link BankAccountJson}
 * canonical snapshots, published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code PartnerStore} / {@code PartnerContactService}.
 */
@Service
public class PartnerBankAccountService {

    /** Aggregate-type discriminator on audit rows for bank-account mutations. */
    public static final String AGGREGATE_TYPE = "partner_bank_account";

    /** Audit verb for the step-4 bulk replace. */
    public static final String EVENT_TYPE_REPLACED = "PARTNER_BANK_ACCOUNTS_REPLACED";

    /** Audit verb for a verification verdict landing on one row. */
    public static final String EVENT_TYPE_VERIFIED = "PARTNER_BANK_ACCOUNT_VERIFIED";

    /** ISO 9362 BIC-8 / BIC-11. */
    static final Pattern BIC = Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");

    /** ISO-4217 alphabetic code shape (the full roster is not enumerated). */
    static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** ISO-3166 alpha-2 shape. */
    static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    /**
     * "Looks like an IBAN": two letters (country) + two digits (check). Values
     * matching this prefix get the full ISO 13616 treatment (charset + mod-97);
     * everything else — KR domestic account numbers in particular — is a raw
     * account number.
     */
    static final Pattern IBAN_PREFIX = Pattern.compile("^[A-Z]{2}\\d{2}.*$");

    /** Full IBAN charset/shape once the prefix says IBAN (length 5..34 overall). */
    static final Pattern IBAN_SHAPE = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{1,30}$");

    /** Raw (non-IBAN) account number: digits/letters/hyphens, no whitespace. */
    static final Pattern RAW_ACCOUNT = Pattern.compile("^[A-Za-z0-9-]{1,34}$");

    /** V012 CHECK roster for swift_charge_bearer. */
    static final Set<String> CHARGE_BEARERS = Set.of("OUR", "BEN", "SHA");

    /** V012 CHECK roster for purpose. */
    static final Set<String> PURPOSES = Set.of("PAYOUT", "FLOAT_TOPUP", "REFUND");

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private static final Logger log = LoggerFactory.getLogger(PartnerBankAccountService.class);

    private final BankAccountRepository bankAccountRepository;
    private final PartnerRepository partnerRepository;
    private final AccountVerificationProvider verificationProvider;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerBankAccountService(BankAccountRepository bankAccountRepository,
                                     PartnerRepository partnerRepository,
                                     AccountVerificationProvider verificationProvider,
                                     ObjectProvider<AuditLogService> auditLogProvider) {
        this.bankAccountRepository = bankAccountRepository;
        this.partnerRepository = partnerRepository;
        this.verificationProvider = verificationProvider;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the bank-account set on a draft partner (wizard step-4
     * "Next").
     *
     * @param partnerCode the human-facing business code routing the PATCH.
     * @param accounts    the FULL desired set; empty clears, {@code null} is a 400.
     * @param actor       the operator (X-Actor header); {@code "system"} when absent.
     * @return the freshly-inserted current set as canonical {@link BankAccountView}s.
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner is no longer in {@code ONBOARDING} (drafts
     *         write direct while ONBOARDING; post-activation bank changes wait
     *         for the Slice 8 2-authorized-signatory flow); 400 on any
     *         validation failure.
     */
    @Transactional
    public List<BankAccountView> replaceDraftBankAccounts(String partnerCode,
                                                          List<BankAccountCommand> accounts,
                                                          String actor) {
        if (accounts == null) {
            throw badRequest(
                    "bankAccounts is required (send an empty list to clear all bank accounts)");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-4 edits are only permitted while ONBOARDING"
                            + " (post-activation bank changes require the Slice 8"
                            + " 2-authorized-signatory flow)");
        }
        // Validate the WHOLE payload before touching any row — a bad element
        // must not leave the set half-replaced (fail fast, side-effect free).
        for (int i = 0; i < accounts.size(); i++) {
            validate(accounts.get(i), i);
        }
        validateOnePrimaryPerCurrency(accounts);

        // One transaction-time instant shared by both halves of the paired
        // write (supersede + insert), truncated to MICROS — see PartnerStore.save.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        List<BankAccountEntity> prior = bankAccountRepository.findCurrentByPartnerId(partner.getId());
        byte[] before = prior.isEmpty() ? null : BankAccountJson.canonical(prior);

        // Verification carry-forward index: a verdict survives a replace only
        // when the account coordinates (currency + number) are unchanged.
        Map<String, BankAccountEntity> verifiedByKey = new HashMap<>();
        for (BankAccountEntity p : prior) {
            if (p.getVerificationStatus() != null
                    && p.getVerificationStatus() != BankVerificationStatus.UNVERIFIED) {
                verifiedByKey.put(carryKey(p.getCurrency(), p.getIbanOrAccountNumber()), p);
            }
        }

        // Supersede the prior current set first (flush forces the UPDATEs out
        // before the INSERTs — same SCD-6 write ordering as PartnerContactService).
        if (!prior.isEmpty()) {
            for (BankAccountEntity p : prior) {
                p.setSupersededAt(now);
            }
            bankAccountRepository.saveAllAndFlush(prior);
        }

        // Insert the new current set. IDENTITY ids are assigned at flush; the
        // RETURNED managed entities carry them, which the audit AFTER snapshot
        // and the response views both need.
        List<BankAccountEntity> fresh = new ArrayList<>(accounts.size());
        for (BankAccountCommand cmd : accounts) {
            BankAccountEntity e = toEntity(partner.getId(), cmd, now);
            BankAccountEntity carried = verifiedByKey.get(
                    carryKey(e.getCurrency(), e.getIbanOrAccountNumber()));
            if (carried != null) {
                e.setVerificationStatus(carried.getVerificationStatus());
                e.setVerificationDate(carried.getVerificationDate());
                if (e.getVerificationEvidenceDocId() == null) {
                    e.setVerificationEvidenceDocId(carried.getVerificationEvidenceDocId());
                }
            }
            fresh.add(e);
        }
        List<BankAccountEntity> saved = bankAccountRepository.saveAllAndFlush(fresh);

        publishAudit(partnerCode, actor, EVENT_TYPE_REPLACED,
                before, BankAccountJson.canonical(saved));

        return saved.stream().map(BankAccountEntity::toView).toList();
    }

    /**
     * The CURRENT bank-account set for the given partner code (no historical
     * rows).
     *
     * @throws ResponseStatusException 404 when no current partner row matches —
     *         "partner exists with zero accounts" returns an empty list, only
     *         an unknown code 404s.
     */
    @Transactional(readOnly = true)
    public List<BankAccountView> currentBankAccounts(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return bankAccountRepository.findCurrentByPartnerId(partner.getId()).stream()
                .map(BankAccountEntity::toView)
                .toList();
    }

    /**
     * Run the {@link AccountVerificationProvider} against the CURRENT row
     * {@code accountId} and record the verdict (status + date) on a fresh
     * SCD-6 row — the single-row paired write.
     *
     * <p>Not gated on ONBOARDING (verification is evidence, not a mutation of
     * the account coordinates — same rationale as KYB rescreens).
     *
     * @return the fresh current row carrying the verdict (note: a NEW row id).
     * @throws ResponseStatusException 404 when the partner code is unknown, or
     *         when {@code accountId} is not a CURRENT bank-account row of that
     *         partner (superseded ids are history, not verifiable subjects);
     *         502 when the provider rail is unavailable.
     */
    @Transactional
    public BankAccountView verifyBankAccount(String partnerCode, Long accountId, String actor) {
        PartnerEntity partner = requirePartner(partnerCode);
        BankAccountEntity current = bankAccountRepository.findById(accountId)
                .filter(b -> b.getPartnerId().equals(partner.getId()))
                .filter(b -> b.getSupersededAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no current bank account " + accountId
                                + " for partner '" + partnerCode + "'"));

        AccountVerificationProvider.VerificationResult result;
        try {
            result = verificationProvider.verify(new AccountVerificationProvider.AccountRef(
                    partnerCode,
                    current.getBankCountry(),
                    current.getCurrency(),
                    current.getIbanOrAccountNumber(),
                    current.getAccountHolderName()));
        } catch (UnsupportedOperationException e) {
            // The KFTC placeholder (certificate pending) — an explicit operator
            // action must fail loudly, never silently no-op.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "account verification rail unavailable: " + e.getMessage());
        }
        if (result == null || result.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "account verification provider returned no verdict");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Defensive truncation, same as KybService.runScreening: a vendor
        // adapter may hand back nanosecond instants.
        Instant verifiedAt = result.verifiedAt() == null
                ? now : result.verifiedAt().truncatedTo(ChronoUnit.MICROS);

        byte[] before = BankAccountJson.canonical(List.of(current));

        // SCD-6 paired write on the single affected row: supersede, then insert
        // the copy carrying the verdict — both halves share `now`.
        current.setSupersededAt(now);
        bankAccountRepository.saveAndFlush(current);

        BankAccountEntity fresh = copyCoordinates(current);
        fresh.setVerificationStatus(result.status());
        fresh.setVerificationDate(verifiedAt.atZone(ZoneOffset.UTC).toLocalDate());
        fresh.setRecordedAt(now);
        fresh.setValidFrom(now);
        BankAccountEntity saved = bankAccountRepository.saveAndFlush(fresh);

        // The provider-side reference is operational evidence, not part of the
        // row contract (the durable document lands in the vault separately).
        log.info("bank account {} for partner '{}' verified as {} (providerRef={})",
                saved.getId(), partnerCode, result.status(), result.evidenceRef());

        publishAudit(partnerCode, actor, EVENT_TYPE_VERIFIED,
                before, BankAccountJson.canonical(List.of(saved)));

        return saved.toView();
    }

    // -------------------------- Helpers --------------------------------------

    private PartnerEntity requirePartner(String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    /** ADR-007 audit row, same-transaction (commits iff the business write commits). */
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

    /** Build a fresh current row from one validated command. */
    private static BankAccountEntity toEntity(Long partnerId, BankAccountCommand cmd, Instant now) {
        BankAccountEntity e = new BankAccountEntity();
        e.setPartnerId(partnerId);
        e.setCurrency(cmd.currency());
        e.setBankName(cmd.bankName());
        e.setBicSwift(blankToNull(cmd.bicSwift()));
        e.setIbanOrAccountNumber(cmd.ibanOrAccountNumber());
        e.setAccountHolderName(cmd.accountHolderName());
        e.setBankCountry(cmd.bankCountry());
        e.setIntermediaryBic(blankToNull(cmd.intermediaryBic()));
        e.setVerificationStatus(BankVerificationStatus.UNVERIFIED);
        e.setVerificationEvidenceDocId(cmd.verificationEvidenceDocId());
        e.setPrimaryAccount(Boolean.TRUE.equals(cmd.primary()));
        e.setSwiftChargeBearer(cmd.swiftChargeBearer() == null || cmd.swiftChargeBearer().isBlank()
                ? null : SwiftChargeBearer.valueOf(cmd.swiftChargeBearer()));
        e.setPurpose(cmd.purpose() == null || cmd.purpose().isBlank()
                ? BankAccountPurpose.PAYOUT : BankAccountPurpose.valueOf(cmd.purpose()));
        e.setRecordedAt(now);
        // Business time starts at capture — the wizard does not back-date accounts.
        e.setValidFrom(now);
        return e;
    }

    /** Copy the account coordinates (everything except the verdict + stamps). */
    private static BankAccountEntity copyCoordinates(BankAccountEntity src) {
        BankAccountEntity e = new BankAccountEntity();
        e.setPartnerId(src.getPartnerId());
        e.setCurrency(src.getCurrency());
        e.setBankName(src.getBankName());
        e.setBicSwift(src.getBicSwift());
        e.setIbanOrAccountNumber(src.getIbanOrAccountNumber());
        e.setAccountHolderName(src.getAccountHolderName());
        e.setBankCountry(src.getBankCountry());
        e.setIntermediaryBic(src.getIntermediaryBic());
        e.setVerificationEvidenceDocId(src.getVerificationEvidenceDocId());
        e.setPrimaryAccount(src.isPrimaryAccount());
        e.setSwiftChargeBearer(src.getSwiftChargeBearer());
        e.setPurpose(src.getPurpose());
        return e;
    }

    /** Carry-forward identity of an account across a bulk replace. */
    private static String carryKey(String currency, String ibanOrAccountNumber) {
        return currency + " " + ibanOrAccountNumber;
    }

    /**
     * Field-format validation for one bank-account element. Index-qualified
     * messages ({@code bankAccounts[2].bicSwift ...}) so the multi-row editor
     * can map the 400 to the offending row.
     */
    private static void validate(BankAccountCommand cmd, int index) {
        String at = "bankAccounts[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.currency() == null || !CURRENCY.matcher(cmd.currency()).matches()) {
            throw badRequest(at + ".currency must be an ISO-4217 code"
                    + " (3 uppercase letters, e.g. KRW), was: " + cmd.currency());
        }
        if (cmd.bankName() == null || cmd.bankName().isBlank()) {
            throw badRequest(at + ".bankName is required");
        }
        if (cmd.bankName().length() > 140) {
            throw badRequest(at + ".bankName must be at most 140 characters");
        }
        if (cmd.bicSwift() != null && !cmd.bicSwift().isBlank()
                && !BIC.matcher(cmd.bicSwift()).matches()) {
            throw badRequest(at + ".bicSwift must be a BIC-8 or BIC-11"
                    + " (e.g. SHBKKRSE or SHBKKRSEXXX), was: " + cmd.bicSwift());
        }
        if (cmd.intermediaryBic() != null && !cmd.intermediaryBic().isBlank()
                && !BIC.matcher(cmd.intermediaryBic()).matches()) {
            throw badRequest(at + ".intermediaryBic must be a BIC-8 or BIC-11, was: "
                    + cmd.intermediaryBic());
        }
        validateAccountNumber(cmd.ibanOrAccountNumber(), at);
        if (cmd.accountHolderName() == null || cmd.accountHolderName().isBlank()) {
            throw badRequest(at + ".accountHolderName is required");
        }
        if (cmd.accountHolderName().length() > 140) {
            throw badRequest(at + ".accountHolderName must be at most 140 characters");
        }
        if (cmd.bankCountry() == null || !COUNTRY.matcher(cmd.bankCountry()).matches()) {
            throw badRequest(at + ".bankCountry must be an ISO-3166 alpha-2 code"
                    + " (2 uppercase letters, e.g. KR), was: " + cmd.bankCountry());
        }
        if (cmd.verificationEvidenceDocId() != null && cmd.verificationEvidenceDocId() <= 0) {
            throw badRequest(at + ".verificationEvidenceDocId must be a positive document id");
        }
        if (cmd.swiftChargeBearer() != null && !cmd.swiftChargeBearer().isBlank()
                && !CHARGE_BEARERS.contains(cmd.swiftChargeBearer())) {
            throw badRequest(at + ".swiftChargeBearer must be one of " + CHARGE_BEARERS
                    + ", was: " + cmd.swiftChargeBearer());
        }
        if (cmd.purpose() != null && !cmd.purpose().isBlank()
                && !PURPOSES.contains(cmd.purpose())) {
            throw badRequest(at + ".purpose must be one of " + PURPOSES
                    + ", was: " + cmd.purpose());
        }
    }

    /**
     * IBAN-or-raw dispatch: a value starting with two letters + two digits is
     * an IBAN and must pass charset + ISO 13616 mod-97; anything else (KR
     * domestic account numbers in particular) is a raw account number checked
     * only for shape (digits/letters/hyphens, &le; 34 chars).
     */
    private static void validateAccountNumber(String value, String at) {
        if (value == null || value.isBlank()) {
            throw badRequest(at + ".ibanOrAccountNumber is required");
        }
        if (value.length() > 34) {
            throw badRequest(at + ".ibanOrAccountNumber must be at most 34 characters");
        }
        if (IBAN_PREFIX.matcher(value).matches()) {
            if (!IBAN_SHAPE.matcher(value).matches()) {
                throw badRequest(at + ".ibanOrAccountNumber looks like an IBAN but contains"
                        + " invalid characters: " + value);
            }
            if (!ibanChecksumOk(value)) {
                throw badRequest(at + ".ibanOrAccountNumber failed the IBAN mod-97 checksum: "
                        + value);
            }
        } else if (!RAW_ACCOUNT.matcher(value).matches()) {
            throw badRequest(at + ".ibanOrAccountNumber must contain only letters, digits"
                    + " and hyphens, was: " + value);
        }
    }

    /**
     * ISO 13616 mod-97-10 check: move the first four characters to the end,
     * map letters to 10..35, and the resulting integer mod 97 must equal 1.
     * Computed digit-by-digit so 34-char IBANs never overflow.
     */
    static boolean ibanChecksumOk(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            int value = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            remainder = value < 10
                    ? (remainder * 10 + value) % 97
                    : (remainder * 100 + value) % 97;
        }
        return remainder == 1;
    }

    /**
     * At most one {@code primary=true} account per currency across the payload
     * (the payload IS the full current state under replace semantics, so the
     * payload-level check is the table-level invariant).
     */
    private static void validateOnePrimaryPerCurrency(List<BankAccountCommand> accounts) {
        Set<String> primaryCurrencies = new HashSet<>();
        for (int i = 0; i < accounts.size(); i++) {
            BankAccountCommand cmd = accounts.get(i);
            if (Boolean.TRUE.equals(cmd.primary()) && !primaryCurrencies.add(cmd.currency())) {
                throw badRequest("bankAccounts[" + i + "]: more than one primary account"
                        + " for currency " + cmd.currency()
                        + " (at most one primary per currency)");
            }
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
