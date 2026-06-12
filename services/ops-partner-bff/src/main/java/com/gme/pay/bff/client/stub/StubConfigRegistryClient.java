package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.PartnerType;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase-1 in-memory stub of {@link ConfigRegistryClient}. Lets the BFF boot and be
 * exercised end-to-end without booting config-registry. A future
 * {@code RestConfigRegistryClient} marked {@code @Primary} will take over without
 * removing this bean.
 *
 * <p>The seed dataset matches the partners used by other services' tests so the
 * Admin UI shows consistent IDs across the stack during local development. The
 * store is mutable so the Admin UI partner-form happy path can round-trip a
 * create or rounding-mode update without booting config-registry.
 */
/**
 * Default unless {@code gmepay.config-registry.client=rest} (then
 * {@link com.gme.pay.bff.client.rest.RestConfigRegistryClient} wins). Keeping
 * the stub on the classpath lets the BFF and its unit slices boot without
 * config-registry being up.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "gmepay.config-registry.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubConfigRegistryClient implements ConfigRegistryClient {

    private final Map<String, PartnerSummary> store = new LinkedHashMap<>();
    private final List<SchemeSummary> schemes;
    /** Slice 1 draft view — keyed by partner_code, mirrors what config-registry returns. */
    private final Map<String, PartnerView> draftStore = new LinkedHashMap<>();
    /** Stand-in for {@code partners_id_seq} so the stub-issued surrogate ids look real. */
    private final AtomicLong surrogateSeq = new AtomicLong(900_000L);
    /** Slice 2 contact sets — keyed by partner_code, mirrors the bulk-replace semantics. */
    private final Map<String, List<com.gme.pay.contracts.ContactView>> contactStore = new LinkedHashMap<>();
    /** Stand-in for the {@code partner_contact} BIGSERIAL. */
    private final AtomicLong contactSeq = new AtomicLong(800_000L);

    /** Mirrors {@code PartnerContactService.PHONE_E164} (config-registry). */
    private static final java.util.regex.Pattern PHONE_E164 =
            java.util.regex.Pattern.compile("^\\+[1-9]\\d{1,14}$");
    /** Mirrors {@code PartnerContactService.EMAIL} (config-registry). */
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** Mirrors config-registry's {@code ContactRole} roster (V009 CHECK constraint). */
    private static final java.util.Set<String> CONTACT_ROLES = java.util.Set.of(
            "OPS_24X7", "FINANCE", "COMPLIANCE_MLRO", "TECH", "LEGAL", "INCIDENT");
    /** Mirrors config-registry's {@code KybService.RISK_RATINGS} (V011 CHECK constraint). */
    private static final java.util.Set<String> RISK_RATINGS = java.util.Set.of(
            "LOW", "MEDIUM", "HIGH");
    /** Slice 3 KYB views — keyed by partner_code, mirrors the SCD-6 current row. */
    private final Map<String, com.gme.pay.contracts.KybView> kybStore = new LinkedHashMap<>();
    /** Stand-in for the {@code partner_kyb} BIGSERIAL. */
    private final AtomicLong kybSeq = new AtomicLong(700_000L);

    public StubConfigRegistryClient() {
        store.put("partner_test_001", new PartnerSummary(
                "partner_test_001", "OVERSEAS", "USD", RoundingMode.HALF_UP));
        store.put("partner_test_002", new PartnerSummary(
                "partner_test_002", "LOCAL", "KRW", RoundingMode.DOWN));
        store.put("partner_test_003", new PartnerSummary(
                "partner_test_003", "OVERSEAS", "JPY", RoundingMode.HALF_EVEN));
        schemes = List.of(
                new SchemeSummary("zeropay_kr", "ZeroPay KR", "KR", "KRW", "DOMESTIC", "ACTIVE"),
                new SchemeSummary("paynow_sg",  "PayNow SG",  "SG", "SGD", "OVERSEAS", "ACTIVE"),
                new SchemeSummary("upi_in",     "UPI IN",     "IN", "INR", "OVERSEAS", "PILOT"));
    }

    @Override
    public PartnerSummary getPartner(String partnerId) {
        return store.get(partnerId);
    }

    @Override
    public List<PartnerSummary> listPartners() {
        return List.copyOf(store.values());
    }

    @Override
    public synchronized PartnerSummary createPartner(PartnerCreateRequest request) {
        RoundingMode mode = parseMode(request.settlementRoundingMode());
        PartnerSummary created = new PartnerSummary(
                request.partnerId(), request.type(), request.settlementCurrency(), mode);
        store.put(created.partnerId(), created);
        return created;
    }

    @Override
    public synchronized PartnerSummary updateRoundingMode(String partnerId, String mode) {
        PartnerSummary existing = store.get(partnerId);
        if (existing == null) {
            return null;
        }
        PartnerSummary updated = new PartnerSummary(
                existing.partnerId(),
                existing.type(),
                existing.settlementCurrency(),
                parseMode(mode));
        store.put(partnerId, updated);
        return updated;
    }

    @Override
    public List<SchemeSummary> listSchemes() {
        return new ArrayList<>(schemes);
    }

    // -------- Slice 1 (1C.2) draft endpoints (ADR-012) -----------------------

    @Override
    public synchronized PartnerView createDraft(PartnerCommand.CreateDraft request) {
        if (request == null || request.partnerCode() == null || request.partnerCode().isBlank()) {
            throw new IllegalArgumentException("partnerCode is required");
        }
        if (draftStore.containsKey(request.partnerCode())
                || store.containsKey(request.partnerCode())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "partner '" + request.partnerCode() + "' already exists");
        }
        PartnerView view = buildView(surrogateSeq.getAndIncrement(), request);
        draftStore.put(request.partnerCode(), view);
        return view;
    }

    @Override
    public synchronized PartnerView patchDraftStep1(String partnerCode,
                                                    PartnerCommand.UpdateStep1 request) {
        PartnerView prior = draftStore.get(partnerCode);
        if (prior == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        PartnerView merged = mergeStep1(prior, request);
        draftStore.put(partnerCode, merged);
        return merged;
    }

    @Override
    public synchronized PartnerView getDraft(String partnerCode) {
        return draftStore.get(partnerCode);
    }

    @Override
    public synchronized List<PartnerView> listDrafts() {
        return new ArrayList<>(draftStore.values());
    }

    // -------- Slice 2 (2A.1) contact endpoints (PARTNER_SETUP_PLAN §Slice 2) --

    /**
     * In-memory bulk replace mirroring config-registry's
     * {@code PartnerContactService.replaceDraftContacts}: the incoming list is
     * the FULL desired set and overwrites whatever was stored before. The same
     * server-side validation (role roster, name/email required, E.164 phone) is
     * applied here so MockMvc tests exercise the 400 path through the stub.
     */
    @Override
    public synchronized List<com.gme.pay.contracts.ContactView> patchDraftStep2(
            String partnerCode, PartnerCommand.UpdateStep2 request) {
        if (!draftStore.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        if (request == null || request.contacts() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "contacts is required (send an empty list to clear all contacts)");
        }
        List<com.gme.pay.contracts.ContactCommand> contacts = request.contacts();
        for (int i = 0; i < contacts.size(); i++) {
            validateContact(contacts.get(i), i);
        }
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        List<com.gme.pay.contracts.ContactView> fresh = new ArrayList<>(contacts.size());
        for (com.gme.pay.contracts.ContactCommand cmd : contacts) {
            fresh.add(new com.gme.pay.contracts.ContactView(
                    contactSeq.getAndIncrement(),
                    cmd.role(),
                    cmd.name(),
                    cmd.email(),
                    cmd.phoneE164() == null || cmd.phoneE164().isBlank() ? null : cmd.phoneE164(),
                    Boolean.TRUE.equals(cmd.authorizedSignatory()),
                    cmd.notes() == null || cmd.notes().isBlank() ? null : cmd.notes(),
                    now,
                    null,
                    now));
        }
        contactStore.put(partnerCode, fresh);
        return new ArrayList<>(fresh);
    }

    @Override
    public synchronized List<com.gme.pay.contracts.ContactView> listContacts(String partnerCode) {
        if (!draftStore.containsKey(partnerCode) && !store.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        return new ArrayList<>(contactStore.getOrDefault(partnerCode, List.of()));
    }

    // -------- Slice 4 (4A.1) bank-account endpoints (PARTNER_SETUP_PLAN §Slice 4)

    /** Slice 4 bank-account sets — keyed by partner_code, mirrors the bulk-replace semantics. */
    private final Map<String, List<com.gme.pay.contracts.BankAccountView>> bankAccountStore =
            new LinkedHashMap<>();
    /** Stand-in for the {@code partner_bank_account} BIGSERIAL. */
    private final AtomicLong bankAccountSeq = new AtomicLong(500_000L);

    /** Mirrors {@code PartnerBankAccountService.BIC} (config-registry). */
    private static final java.util.regex.Pattern BIC =
            java.util.regex.Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");
    /** Mirrors {@code PartnerBankAccountService.IBAN_PREFIX} — "looks like an IBAN". */
    private static final java.util.regex.Pattern IBAN_PREFIX =
            java.util.regex.Pattern.compile("^[A-Z]{2}\\d{2}.*$");
    /** Mirrors {@code PartnerBankAccountService.IBAN_SHAPE}. */
    private static final java.util.regex.Pattern IBAN_SHAPE =
            java.util.regex.Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{1,30}$");
    /** Mirrors config-registry's V012 swift_charge_bearer CHECK roster. */
    private static final java.util.Set<String> CHARGE_BEARERS = java.util.Set.of("OUR", "BEN", "SHA");
    /** Mirrors config-registry's V012 purpose CHECK roster. */
    private static final java.util.Set<String> BANK_PURPOSES =
            java.util.Set.of("PAYOUT", "FLOAT_TOPUP", "REFUND");

    /**
     * In-memory bulk replace mirroring config-registry's
     * {@code PartnerBankAccountService.replaceDraftBankAccounts}: the incoming
     * list is the FULL desired set and overwrites whatever was stored before,
     * carrying verification verdicts forward when the (currency, account
     * number) pair is unchanged. The same server-side validation (BIC shape,
     * IBAN mod-97 dispatch, one-primary-per-currency) is applied here so
     * MockMvc tests exercise the 400 path through the stub.
     */
    @Override
    public synchronized List<com.gme.pay.contracts.BankAccountView> patchDraftStep4(
            String partnerCode, PartnerCommand.UpdateStep4 request) {
        if (!draftStore.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        if (request == null || request.bankAccounts() == null) {
            throw badRequest(
                    "bankAccounts is required (send an empty list to clear all bank accounts)");
        }
        List<com.gme.pay.contracts.BankAccountCommand> accounts = request.bankAccounts();
        java.util.Set<String> primaryCurrencies = new java.util.HashSet<>();
        for (int i = 0; i < accounts.size(); i++) {
            validateBankAccount(accounts.get(i), i);
            if (Boolean.TRUE.equals(accounts.get(i).primary())
                    && !primaryCurrencies.add(accounts.get(i).currency())) {
                throw badRequest("bankAccounts[" + i + "]: more than one primary account"
                        + " for currency " + accounts.get(i).currency()
                        + " (at most one primary per currency)");
            }
        }
        // Verification carry-forward index, mirroring upstream.
        Map<String, com.gme.pay.contracts.BankAccountView> verifiedByKey = new LinkedHashMap<>();
        for (com.gme.pay.contracts.BankAccountView prior
                : bankAccountStore.getOrDefault(partnerCode, List.of())) {
            if (prior.verificationStatus() != null
                    && !"UNVERIFIED".equals(prior.verificationStatus())) {
                verifiedByKey.put(prior.currency() + " " + prior.ibanOrAccountNumber(), prior);
            }
        }
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        List<com.gme.pay.contracts.BankAccountView> fresh = new ArrayList<>(accounts.size());
        for (com.gme.pay.contracts.BankAccountCommand cmd : accounts) {
            com.gme.pay.contracts.BankAccountView carried =
                    verifiedByKey.get(cmd.currency() + " " + cmd.ibanOrAccountNumber());
            fresh.add(new com.gme.pay.contracts.BankAccountView(
                    bankAccountSeq.getAndIncrement(),
                    cmd.currency(),
                    cmd.bankName(),
                    blankToNull(cmd.bicSwift()),
                    cmd.ibanOrAccountNumber(),
                    cmd.accountHolderName(),
                    cmd.bankCountry(),
                    blankToNull(cmd.intermediaryBic()),
                    carried == null ? "UNVERIFIED" : carried.verificationStatus(),
                    cmd.verificationEvidenceDocId() != null
                            ? cmd.verificationEvidenceDocId()
                            : carried == null ? null : carried.verificationEvidenceDocId(),
                    carried == null ? null : carried.verificationDate(),
                    Boolean.TRUE.equals(cmd.primary()),
                    blankToNull(cmd.swiftChargeBearer()),
                    cmd.purpose() == null || cmd.purpose().isBlank() ? "PAYOUT" : cmd.purpose(),
                    now,
                    null,
                    now));
        }
        bankAccountStore.put(partnerCode, fresh);
        return new ArrayList<>(fresh);
    }

    @Override
    public synchronized List<com.gme.pay.contracts.BankAccountView> listBankAccounts(
            String partnerCode) {
        requireKnownPartner(partnerCode);
        return new ArrayList<>(bankAccountStore.getOrDefault(partnerCode, List.of()));
    }

    /**
     * Deterministic in-memory verification mirroring config-registry's
     * {@code StubVerificationAdapter}: {@code bankCountry == "KR"} →
     * KFTC_VERIFIED, anything else → BANK_LETTER. Like upstream's SCD-6 paired
     * write, the verdict lands on a FRESH row id and replaces the old row in
     * the current set.
     */
    @Override
    public synchronized com.gme.pay.contracts.BankAccountView verifyBankAccount(
            String partnerCode, Long accountId) {
        requireKnownPartner(partnerCode);
        List<com.gme.pay.contracts.BankAccountView> current =
                new ArrayList<>(bankAccountStore.getOrDefault(partnerCode, List.of()));
        com.gme.pay.contracts.BankAccountView target = current.stream()
                .filter(b -> b.id().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "no current bank account " + accountId
                                + " for partner '" + partnerCode + "'"));
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        com.gme.pay.contracts.BankAccountView verified = new com.gme.pay.contracts.BankAccountView(
                bankAccountSeq.getAndIncrement(),
                target.currency(),
                target.bankName(),
                target.bicSwift(),
                target.ibanOrAccountNumber(),
                target.accountHolderName(),
                target.bankCountry(),
                target.intermediaryBic(),
                "KR".equalsIgnoreCase(target.bankCountry()) ? "KFTC_VERIFIED" : "BANK_LETTER",
                target.verificationEvidenceDocId(),
                java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                target.primary(),
                target.swiftChargeBearer(),
                target.purpose(),
                now,
                null,
                now);
        current.set(current.indexOf(target), verified);
        bankAccountStore.put(partnerCode, current);
        return verified;
    }

    // -------- Slice 4 (4B.1) settlement-config endpoints (PARTNER_SETUP_PLAN §Slice 4)

    /** Slice 4 settlement configs — keyed by partner_code, mirrors the SCD-6 current row. */
    private final Map<String, com.gme.pay.contracts.SettlementConfigView> settlementStore =
            new LinkedHashMap<>();
    /** Stand-in for the {@code partner_settlement_config} BIGSERIAL. */
    private final AtomicLong settlementSeq = new AtomicLong(400_000L);

    /** Mirrors config-registry's V013 settlement_method CHECK roster. */
    private static final java.util.Set<String> SETTLEMENT_METHODS = java.util.Set.of(
            "SWIFT_MT103", "KR_FIRM_BANKING", "BAKONG",
            "NAPAS_247", "PROMPT_PAY", "FAST_SG", "OTHER");

    /**
     * In-memory step-4 settlement upsert mirroring config-registry's
     * {@code SettlementConfigService.upsertStep4Settlement}: full-state
     * replace with the same defaults (T+1, 16:30, Asia/Seoul) and the same
     * validation roster (method, cycle range, IANA zone) so MockMvc tests
     * exercise the 400 path through the stub.
     */
    @Override
    public synchronized com.gme.pay.contracts.SettlementConfigView patchDraftStep4Settlement(
            String partnerCode, PartnerCommand.UpdateStep4Settlement request) {
        if (!draftStore.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        if (request == null) {
            throw badRequest("request body required");
        }
        validateSettlement(request);
        com.gme.pay.contracts.SettlementConfigView prior = settlementStore.get(partnerCode);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        com.gme.pay.contracts.SettlementConfigView fresh =
                new com.gme.pay.contracts.SettlementConfigView(
                        settlementSeq.getAndIncrement(),
                        request.cycleTPlusN() == null ? 1 : request.cycleTPlusN(),
                        request.cutoffTime() == null
                                ? java.time.LocalTime.of(16, 30) : request.cutoffTime(),
                        request.cutoffTimezone() == null || request.cutoffTimezone().isBlank()
                                ? "Asia/Seoul" : request.cutoffTimezone(),
                        request.settlementMethod(),
                        prior == null ? now : prior.validFrom(),
                        null,
                        now);
        settlementStore.put(partnerCode, fresh);
        return fresh;
    }

    @Override
    public synchronized com.gme.pay.contracts.SettlementConfigView getSettlementConfig(
            String partnerCode) {
        requireKnownPartner(partnerCode);
        com.gme.pay.contracts.SettlementConfigView view = settlementStore.get(partnerCode);
        if (view == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no settlement config for partner '" + partnerCode + "'");
        }
        return view;
    }

    /**
     * Simplified in-memory preview: cutoff + T+N walk skipping WEEKENDS only.
     * The stub has no {@code business_day_calendar} (V014 lives in
     * config-registry's database), so holiday skips — including the
     * cross-country union — are exercised by the config-registry slice tests,
     * not here; the BFF stub keeps the pass-through testable offline.
     */
    @Override
    public synchronized com.gme.pay.contracts.SettlementPreview getSettlementPreview(
            String partnerCode, String txnInstant, String bankCountry) {
        com.gme.pay.contracts.SettlementConfigView config = getSettlementConfig(partnerCode);
        Instant instant;
        try {
            instant = Instant.parse(txnInstant);
        } catch (RuntimeException e) {
            throw badRequest("txnInstant must be an ISO-8601 instant"
                    + " (e.g. 2026-09-23T08:30:00Z), was: " + txnInstant);
        }
        java.time.ZoneId zone = java.time.ZoneId.of(config.cutoffTimezone());
        java.time.ZonedDateTime local = instant.atZone(zone);
        java.util.List<String> trail = new ArrayList<>();
        java.time.LocalDate date = local.toLocalDate();
        if (local.toLocalTime().isAfter(config.cutoffTime())) {
            date = date.plusDays(1);
            trail.add("Transaction is AFTER the " + config.cutoffTime() + " " + zone
                    + " cutoff - value date moves to " + date + ".");
        } else {
            trail.add("Transaction is within the " + config.cutoffTime() + " " + zone
                    + " cutoff - value date " + date + ".");
        }
        date = skipWeekend(date, trail);
        for (int i = 0; i < config.cycleTPlusN(); i++) {
            date = skipWeekend(date.plusDays(1), trail);
        }
        trail.add("T+" + config.cycleTPlusN() + " business day(s) - payout date " + date
                + " (stub: weekends only, no holiday calendar).");
        return new com.gme.pay.contracts.SettlementPreview(date, trail);
    }

    private static java.time.LocalDate skipWeekend(java.time.LocalDate date,
                                                   java.util.List<String> trail) {
        while (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            trail.add(date + " skipped - weekend (" + date.getDayOfWeek() + ").");
            date = date.plusDays(1);
        }
        return date;
    }

    /** Mirror of config-registry's {@code SettlementConfigService.validate} (same messages). */
    private static void validateSettlement(PartnerCommand.UpdateStep4Settlement cmd) {
        if (cmd.settlementMethod() == null || cmd.settlementMethod().isBlank()
                || !SETTLEMENT_METHODS.contains(cmd.settlementMethod())) {
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
                java.time.ZoneId.of(cmd.cutoffTimezone());
            } catch (java.time.DateTimeException e) {
                throw badRequest("cutoffTimezone must be a valid IANA zone id"
                        + " (e.g. Asia/Seoul), was: " + cmd.cutoffTimezone());
            }
        }
    }

    /**
     * Mirror of config-registry's per-element bank-account validation
     * ({@code PartnerBankAccountService.validate} — same message shapes).
     */
    private static void validateBankAccount(
            com.gme.pay.contracts.BankAccountCommand cmd, int index) {
        String at = "bankAccounts[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.currency() == null || !cmd.currency().matches("[A-Z]{3}")) {
            throw badRequest(at + ".currency must be an ISO-4217 code"
                    + " (3 uppercase letters, e.g. KRW), was: " + cmd.currency());
        }
        if (cmd.bankName() == null || cmd.bankName().isBlank() || cmd.bankName().length() > 140) {
            throw badRequest(at + ".bankName is required (max 140 characters)");
        }
        if (cmd.bicSwift() != null && !cmd.bicSwift().isBlank()
                && !BIC.matcher(cmd.bicSwift()).matches()) {
            throw badRequest(at + ".bicSwift must be a BIC-8 or BIC-11, was: " + cmd.bicSwift());
        }
        if (cmd.intermediaryBic() != null && !cmd.intermediaryBic().isBlank()
                && !BIC.matcher(cmd.intermediaryBic()).matches()) {
            throw badRequest(at + ".intermediaryBic must be a BIC-8 or BIC-11, was: "
                    + cmd.intermediaryBic());
        }
        String acct = cmd.ibanOrAccountNumber();
        if (acct == null || acct.isBlank() || acct.length() > 34) {
            throw badRequest(at + ".ibanOrAccountNumber is required (max 34 characters)");
        }
        if (IBAN_PREFIX.matcher(acct).matches()) {
            if (!IBAN_SHAPE.matcher(acct).matches() || !ibanChecksumOk(acct)) {
                throw badRequest(at + ".ibanOrAccountNumber failed IBAN validation: " + acct);
            }
        } else if (!acct.matches("[A-Za-z0-9-]{1,34}")) {
            throw badRequest(at + ".ibanOrAccountNumber must contain only letters, digits"
                    + " and hyphens, was: " + acct);
        }
        if (cmd.accountHolderName() == null || cmd.accountHolderName().isBlank()
                || cmd.accountHolderName().length() > 140) {
            throw badRequest(at + ".accountHolderName is required (max 140 characters)");
        }
        if (cmd.bankCountry() == null || !cmd.bankCountry().matches("[A-Z]{2}")) {
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
                && !BANK_PURPOSES.contains(cmd.purpose())) {
            throw badRequest(at + ".purpose must be one of " + BANK_PURPOSES
                    + ", was: " + cmd.purpose());
        }
    }

    /** ISO 13616 mod-97-10, digit-by-digit — mirrors {@code PartnerBankAccountService}. */
    private static boolean ibanChecksumOk(String iban) {
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

    // -------- Slice 5 (5A.1) prefunding-config endpoints (PARTNER_SETUP_PLAN §Slice 5)

    /** Slice 5 prefunding configs — keyed by partner_code, mirrors the SCD-6 current row. */
    private final Map<String, com.gme.pay.contracts.PrefundingConfigView> prefundingStore =
            new LinkedHashMap<>();
    /** Stand-in for the {@code partner_prefunding_config} BIGSERIAL. */
    private final AtomicLong prefundingSeq = new AtomicLong(300_000L);

    /** Mirrors config-registry's V015 funding_model CHECK roster. */
    private static final java.util.Set<String> FUNDING_MODELS = java.util.Set.of(
            "PREFUNDED", "POSTPAID", "HYBRID");
    /** Mirrors {@code PrefundingConfigService.DEFAULT_TOP_UP_REFERENCE_PATTERN}. */
    private static final String DEFAULT_TOP_UP_REFERENCE_PATTERN =
            "GMP-{partner_code}-{yyyyMMdd}";

    /**
     * In-memory step-5 prefunding upsert mirroring config-registry's
     * {@code PrefundingConfigService.upsertStep5}: full-state replace with the
     * same defaults (threshold 10000, tiers armed, auto-suspend on, the
     * GMP-{partner_code}-{yyyyMMdd} pattern) and the same validation roster
     * (funding model, positive scale-4 money, {partner_code} placeholder,
     * FLOAT_TOPUP purpose checked against the stub's own bank-account set) so
     * MockMvc tests exercise the 400 path through the stub.
     */
    @Override
    public synchronized com.gme.pay.contracts.PrefundingConfigView patchDraftStep5(
            String partnerCode, PartnerCommand.UpdateStep5 request) {
        if (!draftStore.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        if (request == null) {
            throw badRequest("request body required");
        }
        validatePrefunding(request);
        validateTopUpAccount(partnerCode, request.floatTopUpBankAccountId());

        com.gme.pay.contracts.PrefundingConfigView prior = prefundingStore.get(partnerCode);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        com.gme.pay.contracts.PrefundingConfigView fresh =
                new com.gme.pay.contracts.PrefundingConfigView(
                        prefundingSeq.getAndIncrement(),
                        request.fundingModel(),
                        scale4(request.openingBalanceUsd()),
                        scale4(request.lowBalanceThresholdUsd() == null
                                ? new java.math.BigDecimal("10000")
                                : request.lowBalanceThresholdUsd()),
                        request.alertTier70() == null || request.alertTier70(),
                        request.alertTier85() == null || request.alertTier85(),
                        request.alertTier95() == null || request.alertTier95(),
                        scale4(request.creditLimitUsd()),
                        request.autoSuspendOnBreach() == null || request.autoSuspendOnBreach(),
                        request.floatTopUpBankAccountId(),
                        request.topUpReferencePattern() == null
                                || request.topUpReferencePattern().isBlank()
                                ? DEFAULT_TOP_UP_REFERENCE_PATTERN
                                : request.topUpReferencePattern(),
                        scale4(request.collateralAmountUsd()),
                        prior == null ? now : prior.validFrom(),
                        null,
                        now);
        prefundingStore.put(partnerCode, fresh);
        return fresh;
    }

    @Override
    public synchronized com.gme.pay.contracts.PrefundingConfigView getPrefundingConfig(
            String partnerCode) {
        requireKnownPartner(partnerCode);
        com.gme.pay.contracts.PrefundingConfigView view = prefundingStore.get(partnerCode);
        if (view == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no prefunding config for partner '" + partnerCode + "'");
        }
        return view;
    }

    // -------- Slice 6 (6A.1) pricing-rule endpoints (PARTNER_SETUP_PLAN §Slice 6)

    /** Slice 6 rule sets — keyed by partner_code, mirrors the bulk-replace semantics. */
    private final Map<String, List<com.gme.pay.contracts.RuleView>> ruleStore =
            new LinkedHashMap<>();
    /** Stand-in for the {@code partner_rule} BIGSERIAL. */
    private final AtomicLong ruleSeq = new AtomicLong(400_000L);

    /** Mirrors config-registry's V017 direction CHECK roster. */
    private static final java.util.Set<String> RULE_DIRECTIONS = java.util.Set.of(
            "INBOUND", "OUTBOUND", "BOTH");

    /**
     * In-memory step-6 rule bulk replace mirroring config-registry's
     * {@code RuleService.replaceDraftRules}: full-set replace with the same
     * validation roster (direction, NUMERIC(7,4) margins, NUMERIC(19,4)
     * service charge, duplicate (scheme, direction) keys) and the SAME
     * lib-domain {@code Rule.validate} margin invariant — evaluated against
     * the draft view's V016 collection/settle split (falling back to the
     * legacy settlement currency) — so MockMvc tests exercise the 400 paths
     * through the stub.
     */
    @Override
    public synchronized List<com.gme.pay.contracts.RuleView> patchDraftStep6Rules(
            String partnerCode, PartnerCommand.UpdateStep6Rules request) {
        PartnerView draft = draftStore.get(partnerCode);
        if (draft == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        if (request == null || request.rules() == null) {
            throw badRequest("rules is required (send an empty list to clear all rules)");
        }
        List<com.gme.pay.contracts.RuleCommand> rules = request.rules();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            validateRule(rules.get(i), i);
            com.gme.pay.contracts.RuleCommand cmd = rules.get(i);
            if (!seenKeys.add(cmd.schemeId() + ":" + cmd.direction())) {
                throw badRequest("rules[" + i + "]: duplicate rule for scheme "
                        + cmd.schemeId() + " direction " + cmd.direction()
                        + " (at most one rule per scheme and direction)");
            }
        }
        // The SAME lib-domain invariant config-registry runs (RATE-04 §11).
        String settleA = draft.settleACcy() != null
                ? draft.settleACcy() : draft.settlementCurrency();
        String collection = draft.collectionCcy() != null
                ? draft.collectionCcy() : draft.settlementCurrency();
        for (int i = 0; i < rules.size(); i++) {
            com.gme.pay.contracts.RuleCommand cmd = rules.get(i);
            try {
                new com.gme.pay.domain.Rule(partnerCode, cmd.schemeId(),
                        domainDirection(cmd.direction()), settleA, collection,
                        cmd.mA(), cmd.mB(), cmd.serviceChargeUsd()).validate();
            } catch (com.gme.pay.errors.ApiException invariant) {
                throw badRequest("rules[" + i + "]: " + invariant.getMessage());
            }
        }

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        List<com.gme.pay.contracts.RuleView> fresh = new ArrayList<>(rules.size());
        for (com.gme.pay.contracts.RuleCommand cmd : rules) {
            fresh.add(new com.gme.pay.contracts.RuleView(
                    ruleSeq.getAndIncrement(),
                    cmd.schemeId(),
                    cmd.direction(),
                    cmd.mA().setScale(4),
                    cmd.mB().setScale(4),
                    (cmd.serviceChargeUsd() == null
                            ? java.math.BigDecimal.ZERO : cmd.serviceChargeUsd()).setScale(4),
                    now,
                    null,
                    now));
        }
        ruleStore.put(partnerCode, List.copyOf(fresh));
        return List.copyOf(fresh);
    }

    @Override
    public synchronized List<com.gme.pay.contracts.RuleView> listRules(String partnerCode) {
        if (!draftStore.containsKey(partnerCode) && !store.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        return ruleStore.getOrDefault(partnerCode, List.of());
    }

    /** Mirror of config-registry's {@code RuleService.validate} (same messages shape). */
    private static void validateRule(com.gme.pay.contracts.RuleCommand cmd, int index) {
        String at = "rules[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.schemeId() == null || cmd.schemeId().isBlank() || cmd.schemeId().length() > 40) {
            throw badRequest(at + ".schemeId is required (max 40 characters), was: "
                    + cmd.schemeId());
        }
        if (cmd.direction() == null || !RULE_DIRECTIONS.contains(cmd.direction())) {
            throw badRequest(at + ".direction must be one of " + RULE_DIRECTIONS
                    + ", was: " + cmd.direction());
        }
        validateRuleMargin(at + ".mA", cmd.mA());
        validateRuleMargin(at + ".mB", cmd.mB());
        if (cmd.serviceChargeUsd() != null) {
            if (cmd.serviceChargeUsd().signum() < 0) {
                throw badRequest(at + ".serviceChargeUsd must not be negative, was: "
                        + cmd.serviceChargeUsd().toPlainString());
            }
            if (cmd.serviceChargeUsd().stripTrailingZeros().scale() > 4) {
                throw badRequest(at + ".serviceChargeUsd must have at most 4 decimal"
                        + " places (NUMERIC(19,4)), was: "
                        + cmd.serviceChargeUsd().toPlainString());
            }
        }
    }

    /** One margin against the NUMERIC(7,4) envelope — mirrors {@code RuleService}. */
    private static void validateRuleMargin(String field, java.math.BigDecimal value) {
        if (value == null) {
            throw badRequest(field + " is required (a decimal fraction, e.g. 0.0150 = 1.50%)");
        }
        if (value.signum() < 0) {
            throw badRequest(field + " must not be negative, was: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > 4) {
            throw badRequest(field + " must have at most 4 decimal places"
                    + " (NUMERIC(7,4)), was: " + value.toPlainString());
        }
        if (value.precision() - value.scale() > 3) {
            throw badRequest(field + " exceeds NUMERIC(7,4) (at most 3 integer digits),"
                    + " was: " + value.toPlainString());
        }
    }

    /**
     * V017 direction string → lib-domain {@code Direction} for the invariant
     * check; {@code BOTH} maps to {@code null} (safe — {@code Rule.validate}
     * prices on the currency pair, not the direction).
     */
    private static com.gme.pay.domain.Direction domainDirection(String direction) {
        return switch (direction) {
            case "INBOUND" -> com.gme.pay.domain.Direction.INBOUND;
            case "OUTBOUND" -> com.gme.pay.domain.Direction.OUTBOUND;
            default -> null;
        };
    }

    // -------- Slice 6 (6B.1) commercial-terms endpoints (PARTNER_SETUP_PLAN §Slice 6)

    /** Slice 6 fee sets — keyed by partner_code, mirrors the bulk-replace semantics. */
    private final Map<String, List<com.gme.pay.contracts.FeeScheduleView>> feeScheduleStore =
            new LinkedHashMap<>();
    /** Slice 6 single-row commercial sub-resources — keyed by partner_code. */
    private final Map<String, com.gme.pay.contracts.FxConfigView> fxConfigStore =
            new LinkedHashMap<>();
    private final Map<String, com.gme.pay.contracts.LimitsView> limitsStore =
            new LinkedHashMap<>();
    private final Map<String, com.gme.pay.contracts.ContractView> contractStore =
            new LinkedHashMap<>();
    /** Stand-in for the V018..V021 BIGSERIALs (shared — ids only need uniqueness). */
    private final AtomicLong commercialSeq = new AtomicLong(200_000L);

    /** Mirrors config-registry's V019 reference_rate_source CHECK roster. */
    private static final java.util.Set<String> FX_RATE_SOURCES = java.util.Set.of(
            "SEOUL_FX_BROKER", "PARTNER_PROVIDED", "MID_MARKET");
    /** Mirrors config-registry's V021 refund_chargeback_policy CHECK roster. */
    private static final java.util.Set<String> CHARGEBACK_POLICIES = java.util.Set.of(
            "PARTNER_BEARS", "MERCHANT_BEARS", "SHARED");
    /** Mirrors {@code LimitsService}'s 소액해외송금업 statutory ceilings (V020). */
    private static final java.math.BigDecimal SOAEK_PER_TXN_MAX =
            new java.math.BigDecimal("5000");
    private static final java.math.BigDecimal SOAEK_ANNUAL_MAX =
            new java.math.BigDecimal("50000");

    /**
     * In-memory step-6 commercial composite mirroring config-registry's
     * {@code CommercialTermsService.upsertStep6Commercial}: each non-null
     * section is a full-state (fees: bulk) replace with the same validation
     * roster — direction/source/policy rosters, NUMERIC(19,4)/(7,4)
     * envelopes, tier ascent, duplicate fee keys, limits ordering and the
     * 소액해외송금업 caps — validated up front so a bad later section leaves
     * earlier sections untouched (the stub equivalent of the one-transaction
     * rollback). Null sections are untouched; all-null is a 400.
     */
    @Override
    public synchronized com.gme.pay.contracts.CommercialTermsView patchDraftStep6Commercial(
            String partnerCode, PartnerCommand.UpdateStep6Commercial request) {
        if (!draftStore.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        if (request == null) {
            throw badRequest("request body required");
        }
        if (request.feeSchedules() == null && request.fxConfig() == null
                && request.limits() == null && request.contract() == null) {
            throw badRequest("at least one of feeSchedules, fxConfig, limits, contract"
                    + " must be present (null sections are left untouched)");
        }
        // Validate ALL sections before applying ANY (atomicity mirror).
        if (request.feeSchedules() != null) {
            validateFeeSchedules(request.feeSchedules());
        }
        if (request.fxConfig() != null) {
            validateFxConfig(request.fxConfig());
        }
        if (request.limits() != null) {
            validateLimits(request.limits());
        }
        if (request.contract() != null) {
            validateContract(request.contract());
        }

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        List<com.gme.pay.contracts.FeeScheduleView> fees = null;
        if (request.feeSchedules() != null) {
            List<com.gme.pay.contracts.FeeScheduleView> fresh =
                    new ArrayList<>(request.feeSchedules().size());
            for (com.gme.pay.contracts.FeeScheduleCommand cmd : request.feeSchedules()) {
                List<com.gme.pay.contracts.FeeTier> tiers =
                        cmd.tiers() == null || cmd.tiers().isEmpty() ? null
                                : cmd.tiers().stream()
                                        .map(t -> new com.gme.pay.contracts.FeeTier(
                                                scale4(t.fromVolumeUsd()),
                                                scale4(t.bpsOverride())))
                                        .toList();
                fresh.add(new com.gme.pay.contracts.FeeScheduleView(
                        commercialSeq.getAndIncrement(),
                        blankToNull(cmd.schemeId()),
                        blankToNull(cmd.direction()),
                        scale4(cmd.fixedFeeUsd() == null
                                ? java.math.BigDecimal.ZERO : cmd.fixedFeeUsd()),
                        scale4(cmd.bpsFee() == null
                                ? java.math.BigDecimal.ZERO : cmd.bpsFee()),
                        tiers,
                        now,
                        null,
                        now));
            }
            fees = List.copyOf(fresh);
            feeScheduleStore.put(partnerCode, fees);
        }

        com.gme.pay.contracts.FxConfigView fx = null;
        if (request.fxConfig() != null) {
            com.gme.pay.contracts.FxConfigView priorFx = fxConfigStore.get(partnerCode);
            fx = new com.gme.pay.contracts.FxConfigView(
                    commercialSeq.getAndIncrement(),
                    scale4(request.fxConfig().marginBps() == null
                            ? java.math.BigDecimal.ZERO : request.fxConfig().marginBps()),
                    request.fxConfig().referenceRateSource(),
                    request.fxConfig().quoteHoldSeconds() == null
                            ? 300 : request.fxConfig().quoteHoldSeconds(),
                    priorFx == null ? now : priorFx.validFrom(),
                    null,
                    now);
            fxConfigStore.put(partnerCode, fx);
        }

        com.gme.pay.contracts.LimitsView limits = null;
        if (request.limits() != null) {
            com.gme.pay.contracts.LimitsView priorLimits = limitsStore.get(partnerCode);
            limits = new com.gme.pay.contracts.LimitsView(
                    commercialSeq.getAndIncrement(),
                    scale4(request.limits().perTxnMinUsd()),
                    scale4(request.limits().perTxnMaxUsd()),
                    scale4(request.limits().dailyCapUsd()),
                    scale4(request.limits().monthlyCapUsd()),
                    scale4(request.limits().annualCapUsd()),
                    blankToNull(request.limits().licenseType()),
                    priorLimits == null ? now : priorLimits.validFrom(),
                    null,
                    now);
            limitsStore.put(partnerCode, limits);
        }

        com.gme.pay.contracts.ContractView contract = null;
        if (request.contract() != null) {
            com.gme.pay.contracts.ContractView priorContract = contractStore.get(partnerCode);
            contract = new com.gme.pay.contracts.ContractView(
                    commercialSeq.getAndIncrement(),
                    request.contract().effectiveFrom(),
                    request.contract().effectiveTo(),
                    Boolean.TRUE.equals(request.contract().autoRenewal()),
                    request.contract().noticePeriodDays(),
                    blankToNull(request.contract().refundChargebackPolicy()),
                    blankToNull(request.contract().terminationReason()),
                    priorContract == null ? now : priorContract.validFrom(),
                    null,
                    now);
            contractStore.put(partnerCode, contract);
        }

        return new com.gme.pay.contracts.CommercialTermsView(fees, fx, limits, contract);
    }

    @Override
    public synchronized List<com.gme.pay.contracts.FeeScheduleView> getFeeSchedules(
            String partnerCode) {
        requireKnownPartner(partnerCode);
        return feeScheduleStore.getOrDefault(partnerCode, List.of());
    }

    @Override
    public synchronized com.gme.pay.contracts.FxConfigView getFxConfig(String partnerCode) {
        requireKnownPartner(partnerCode);
        com.gme.pay.contracts.FxConfigView view = fxConfigStore.get(partnerCode);
        if (view == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no fx config for partner '" + partnerCode + "'");
        }
        return view;
    }

    @Override
    public synchronized com.gme.pay.contracts.LimitsView getLimits(String partnerCode) {
        requireKnownPartner(partnerCode);
        com.gme.pay.contracts.LimitsView view = limitsStore.get(partnerCode);
        if (view == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no limits for partner '" + partnerCode + "'");
        }
        return view;
    }

    @Override
    public synchronized com.gme.pay.contracts.ContractView getContract(String partnerCode) {
        requireKnownPartner(partnerCode);
        com.gme.pay.contracts.ContractView view = contractStore.get(partnerCode);
        if (view == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no contract for partner '" + partnerCode + "'");
        }
        return view;
    }

    /** Mirror of config-registry's {@code FeeScheduleService} validation (same shapes). */
    private static void validateFeeSchedules(
            List<com.gme.pay.contracts.FeeScheduleCommand> fees) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < fees.size(); i++) {
            com.gme.pay.contracts.FeeScheduleCommand cmd = fees.get(i);
            String at = "feeSchedules[" + i + "]";
            if (cmd == null) {
                throw badRequest(at + " must be an object");
            }
            if (cmd.schemeId() != null && !cmd.schemeId().isBlank()
                    && cmd.schemeId().length() > 40) {
                throw badRequest(at + ".schemeId must be at most 40 characters");
            }
            if (cmd.direction() != null && !cmd.direction().isBlank()
                    && !RULE_DIRECTIONS.contains(cmd.direction())) {
                throw badRequest(at + ".direction must be one of " + RULE_DIRECTIONS
                        + " (or null for all directions), was: " + cmd.direction());
            }
            validateMoney(at + ".fixedFeeUsd", cmd.fixedFeeUsd(), false);
            validateBps4(at + ".bpsFee", cmd.bpsFee());
            if (cmd.tiers() != null) {
                java.math.BigDecimal previousFrom = null;
                for (int t = 0; t < cmd.tiers().size(); t++) {
                    com.gme.pay.contracts.FeeTier tier = cmd.tiers().get(t);
                    String tierAt = at + ".tiers[" + t + "]";
                    if (tier == null || tier.fromVolumeUsd() == null
                            || tier.bpsOverride() == null) {
                        throw badRequest(tierAt
                                + " requires both fromVolumeUsd and bpsOverride");
                    }
                    validateMoney(tierAt + ".fromVolumeUsd", tier.fromVolumeUsd(), false);
                    validateBps4(tierAt + ".bpsOverride", tier.bpsOverride());
                    if (previousFrom != null
                            && tier.fromVolumeUsd().compareTo(previousFrom) <= 0) {
                        throw badRequest(tierAt + ".fromVolumeUsd must be strictly"
                                + " greater than the previous band's ("
                                + previousFrom.toPlainString() + "), was: "
                                + tier.fromVolumeUsd().toPlainString());
                    }
                    previousFrom = tier.fromVolumeUsd();
                }
            }
            String key = (blankToNull(cmd.schemeId()) == null ? "*" : cmd.schemeId())
                    + ":" + (blankToNull(cmd.direction()) == null ? "*" : cmd.direction());
            if (!seen.add(key)) {
                throw badRequest(at + ": duplicate (schemeId, direction) pair " + key
                        + " — at most one fee row per pair");
            }
        }
    }

    /** Mirror of config-registry's {@code FxConfigService.validate} (same messages). */
    private static void validateFxConfig(com.gme.pay.contracts.FxConfigCommand cmd) {
        if (cmd.referenceRateSource() == null || cmd.referenceRateSource().isBlank()
                || !FX_RATE_SOURCES.contains(cmd.referenceRateSource())) {
            throw badRequest("fxConfig.referenceRateSource must be one of " + FX_RATE_SOURCES
                    + ", was: " + cmd.referenceRateSource());
        }
        validateBps4("fxConfig.marginBps", cmd.marginBps());
        if (cmd.quoteHoldSeconds() != null
                && (cmd.quoteHoldSeconds() < 60 || cmd.quoteHoldSeconds() > 1800)) {
            throw badRequest("fxConfig.quoteHoldSeconds must be between 60 and 1800, was: "
                    + cmd.quoteHoldSeconds());
        }
    }

    /** Mirror of config-registry's {@code LimitsService.validate} incl. 소액해외송금업 caps. */
    private static void validateLimits(com.gme.pay.contracts.LimitsCommand cmd) {
        validateMoney("limits.perTxnMinUsd", cmd.perTxnMinUsd(), false);
        validateMoney("limits.perTxnMaxUsd", cmd.perTxnMaxUsd(), false);
        validateMoney("limits.dailyCapUsd", cmd.dailyCapUsd(), false);
        validateMoney("limits.monthlyCapUsd", cmd.monthlyCapUsd(), false);
        validateMoney("limits.annualCapUsd", cmd.annualCapUsd(), false);
        requireOrderedCaps("limits.perTxnMinUsd", cmd.perTxnMinUsd(),
                "limits.perTxnMaxUsd", cmd.perTxnMaxUsd());
        requireOrderedCaps("limits.dailyCapUsd", cmd.dailyCapUsd(),
                "limits.monthlyCapUsd", cmd.monthlyCapUsd());
        requireOrderedCaps("limits.monthlyCapUsd", cmd.monthlyCapUsd(),
                "limits.annualCapUsd", cmd.annualCapUsd());
        requireOrderedCaps("limits.dailyCapUsd", cmd.dailyCapUsd(),
                "limits.annualCapUsd", cmd.annualCapUsd());
        if (cmd.licenseType() != null && cmd.licenseType().length() > 30) {
            throw badRequest("limits.licenseType must be at most 30 characters");
        }
        if ("SOAEK_HAEOEMONG".equals(cmd.licenseType())) {
            if (cmd.perTxnMaxUsd() == null
                    || cmd.perTxnMaxUsd().compareTo(SOAEK_PER_TXN_MAX) > 0) {
                throw badRequest("limits.perTxnMaxUsd is required for"
                        + " license_type=SOAEK_HAEOEMONG (소액해외송금업) and must be <= "
                        + SOAEK_PER_TXN_MAX.toPlainString() + " USD, was: "
                        + (cmd.perTxnMaxUsd() == null
                                ? "null" : cmd.perTxnMaxUsd().toPlainString()));
            }
            if (cmd.annualCapUsd() == null
                    || cmd.annualCapUsd().compareTo(SOAEK_ANNUAL_MAX) > 0) {
                throw badRequest("limits.annualCapUsd is required for"
                        + " license_type=SOAEK_HAEOEMONG (소액해외송금업) and must be <= "
                        + SOAEK_ANNUAL_MAX.toPlainString() + " USD, was: "
                        + (cmd.annualCapUsd() == null
                                ? "null" : cmd.annualCapUsd().toPlainString()));
            }
        }
    }

    /** Mirror of config-registry's {@code ContractService.validate} (same messages). */
    private static void validateContract(com.gme.pay.contracts.ContractCommand cmd) {
        if (cmd.effectiveFrom() == null) {
            throw badRequest("contract.effectiveFrom is required (ISO-8601 date)");
        }
        if (cmd.effectiveTo() != null && cmd.effectiveTo().isBefore(cmd.effectiveFrom())) {
            throw badRequest("contract.effectiveTo (" + cmd.effectiveTo()
                    + ") must not be before contract.effectiveFrom ("
                    + cmd.effectiveFrom() + ")");
        }
        if (cmd.noticePeriodDays() != null && cmd.noticePeriodDays() < 0) {
            throw badRequest("contract.noticePeriodDays must not be negative, was: "
                    + cmd.noticePeriodDays());
        }
        if (cmd.refundChargebackPolicy() != null && !cmd.refundChargebackPolicy().isBlank()
                && !CHARGEBACK_POLICIES.contains(cmd.refundChargebackPolicy())) {
            throw badRequest("contract.refundChargebackPolicy must be one of "
                    + CHARGEBACK_POLICIES + ", was: " + cmd.refundChargebackPolicy());
        }
        if (cmd.terminationReason() != null && cmd.terminationReason().length() > 200) {
            throw badRequest("contract.terminationReason must be at most 200 characters");
        }
    }

    /** Nullable bps against the NUMERIC(7,4) envelope (mirrors {@code CommercialValidation}). */
    private static void validateBps4(String field, java.math.BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            throw badRequest(field + " must not be negative, was: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > 4) {
            throw badRequest(field + " must have at most 4 decimal places"
                    + " (NUMERIC(7,4)), was: " + value.toPlainString());
        }
        if (value.precision() - value.scale() > 3) {
            throw badRequest(field + " exceeds NUMERIC(7,4) (at most 3 integer digits"
                    + " / 999.9999 bps), was: " + value.toPlainString());
        }
    }

    /** {@code low <= high} when both present (mirrors {@code LimitsService}). */
    private static void requireOrderedCaps(String lowField, java.math.BigDecimal low,
                                           String highField, java.math.BigDecimal high) {
        if (low != null && high != null && low.compareTo(high) > 0) {
            throw badRequest(lowField + " (" + low.toPlainString()
                    + ") must not exceed " + highField + " (" + high.toPlainString() + ")");
        }
    }

    /** Mirror of config-registry's {@code PrefundingConfigService.validate} (same messages). */
    private static void validatePrefunding(PartnerCommand.UpdateStep5 cmd) {
        if (cmd.fundingModel() == null || cmd.fundingModel().isBlank()
                || !FUNDING_MODELS.contains(cmd.fundingModel())) {
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
            if (!cmd.topUpReferencePattern().contains("{partner_code}")) {
                throw badRequest("topUpReferencePattern must contain the {partner_code}"
                        + " placeholder (top-up wires auto-reconcile on it), was: "
                        + cmd.topUpReferencePattern());
            }
        }
        if (cmd.floatTopUpBankAccountId() != null && cmd.floatTopUpBankAccountId() <= 0) {
            throw badRequest("floatTopUpBankAccountId must be a positive bank-account id");
        }
    }

    /** Mirror of config-registry's {@code PrefundingConfigService.validateMoney}. */
    private static void validateMoney(String field, java.math.BigDecimal value,
                                      boolean strictlyPositive) {
        if (value == null) {
            return;
        }
        if (strictlyPositive && value.signum() <= 0) {
            throw badRequest(field + " must be greater than 0, was: " + value.toPlainString());
        }
        if (!strictlyPositive && value.signum() < 0) {
            throw badRequest(field + " must not be negative, was: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > 4) {
            throw badRequest(field + " must have at most 4 decimal places"
                    + " (NUMERIC(19,4)), was: " + value.toPlainString());
        }
    }

    /**
     * Mirror of config-registry's
     * {@code PrefundingConfigService.validateTopUpAccount}: the referenced row
     * must be in the partner's CURRENT bank-account set with
     * purpose=FLOAT_TOPUP.
     */
    private void validateTopUpAccount(String partnerCode, Long accountId) {
        if (accountId == null) {
            return;
        }
        com.gme.pay.contracts.BankAccountView account =
                bankAccountStore.getOrDefault(partnerCode, List.of()).stream()
                        .filter(b -> accountId.equals(b.id()))
                        .findFirst()
                        .orElseThrow(() -> badRequest("floatTopUpBankAccountId " + accountId
                                + " is not a current bank account of partner '"
                                + partnerCode + "'"));
        if (!"FLOAT_TOPUP".equals(account.purpose())) {
            throw badRequest("floatTopUpBankAccountId " + accountId
                    + " must reference a bank account with purpose=FLOAT_TOPUP,"
                    + " but its purpose is " + account.purpose());
        }
    }

    /** Scale-4 normalisation mirroring {@code PrefundingConfigService.normalizeMoney}. */
    private static java.math.BigDecimal scale4(java.math.BigDecimal value) {
        return value == null ? null : value.setScale(4);
    }

    // -------- Slice 3 (3B.1) KYB endpoints (PARTNER_SETUP_PLAN §Slice 3) ------

    /**
     * In-memory step-3 upsert mirroring config-registry's
     * {@code KybService.upsertStep3}: full-state replace of the
     * operator-editable fields, screening verdict carried forward. The same
     * server-side validation roster (risk rating, UBO pct range, lengths) is
     * applied here so MockMvc tests exercise the 400 path through the stub.
     */
    @Override
    public synchronized com.gme.pay.contracts.KybView patchDraftStep3(
            String partnerCode, com.gme.pay.contracts.KybCommand.UpdateStep3 request) {
        if (!draftStore.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        if (request == null) {
            throw badRequest("request body required");
        }
        validateKyb(request);
        com.gme.pay.contracts.KybView prior = kybStore.get(partnerCode);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        com.gme.pay.contracts.KybView fresh = new com.gme.pay.contracts.KybView(
                kybSeq.getAndIncrement(),
                blankToNull(request.riskRating()),
                blankToNull(request.riskRationale()),
                request.nextReviewDate(),
                blankToNull(request.licenseType()),
                blankToNull(request.licenseNumber()),
                blankToNull(request.licenseAuthority()),
                request.licenseExpiry(),
                request.uboList() == null ? null : List.copyOf(request.uboList()),
                request.cbddqDocId(),
                // Screening fields are NOT operator-editable: carried forward.
                prior == null ? null : prior.screeningStatus(),
                prior == null ? null : prior.screeningProviderRef(),
                prior == null ? null : prior.screenedAt(),
                prior == null ? now : prior.validFrom(),
                null,
                now);
        kybStore.put(partnerCode, fresh);
        return fresh;
    }

    @Override
    public synchronized com.gme.pay.contracts.KybView getKyb(String partnerCode) {
        if (!draftStore.containsKey(partnerCode) && !store.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        com.gme.pay.contracts.KybView view = kybStore.get(partnerCode);
        if (view == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no KYB data for partner '" + partnerCode + "'");
        }
        return view;
    }

    /**
     * Deterministic in-memory screening mirroring {@code StubKybAdapter}
     * (lib-kyb): any screened name containing {@code SANCTIONED} → HIT,
     * otherwise containing {@code REVIEW} → NEEDS_REVIEW, else CLEAR. Names
     * screened = the draft's legal names + every declared UBO name — same
     * subject assembly as config-registry's {@code KybService.runScreening}.
     */
    @Override
    public synchronized com.gme.pay.contracts.KybView runKybScreening(String partnerCode) {
        PartnerView draft = draftStore.get(partnerCode);
        if (draft == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        com.gme.pay.contracts.KybView prior = kybStore.get(partnerCode);

        StringBuilder names = new StringBuilder();
        if (draft.legalNameLocal() != null) {
            names.append(draft.legalNameLocal()).append('|');
        }
        if (draft.legalNameRomanized() != null) {
            names.append(draft.legalNameRomanized()).append('|');
        }
        if (prior != null && prior.uboList() != null) {
            for (com.gme.pay.contracts.UboView u : prior.uboList()) {
                if (u != null && u.name() != null) {
                    names.append(u.name()).append('|');
                }
            }
        }
        String upper = names.toString().toUpperCase(java.util.Locale.ROOT);
        String status = upper.contains("SANCTIONED") ? "HIT"
                : upper.contains("REVIEW") ? "NEEDS_REVIEW" : "CLEAR";

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        com.gme.pay.contracts.KybView screened = new com.gme.pay.contracts.KybView(
                kybSeq.getAndIncrement(),
                prior == null ? null : prior.riskRating(),
                prior == null ? null : prior.riskRationale(),
                prior == null ? null : prior.nextReviewDate(),
                prior == null ? null : prior.licenseType(),
                prior == null ? null : prior.licenseNumber(),
                prior == null ? null : prior.licenseAuthority(),
                prior == null ? null : prior.licenseExpiry(),
                prior == null ? null : prior.uboList(),
                prior == null ? null : prior.cbddqDocId(),
                status,
                "stub-bff-" + Integer.toHexString(upper.hashCode()),
                now,
                prior == null ? now : prior.validFrom(),
                null,
                now);
        kybStore.put(partnerCode, screened);
        return screened;
    }

    // -------- Slice 3 (3A.1) document vault endpoints (ADR-006) ---------------

    /** Mirrors config-registry's {@code DocumentType} roster (V010 CHECK constraint). */
    private static final java.util.Set<String> DOC_TYPES = java.util.Set.of(
            "LICENSE", "CERT_INCORPORATION", "AOA", "BOARD_RESOLUTION",
            "UBO_DECLARATION", "FINANCIALS", "CBDDQ", "OTHER");

    /** Slice 3 CURRENT document sets — keyed by partner_code, one row per doc type. */
    private final Map<String, List<com.gme.pay.contracts.DocumentView>> documentStore =
            new LinkedHashMap<>();
    /** Every row version ever minted (superseded ids stay downloadable — version history). */
    private final Map<Long, com.gme.pay.contracts.DocumentView> documentById = new LinkedHashMap<>();
    /** Owning partner_code per row id (cross-partner probing 404s, like upstream). */
    private final Map<Long, String> documentOwner = new LinkedHashMap<>();
    /** Stored bytes per row id so the download passthrough round-trips. */
    private final Map<Long, byte[]> documentBytes = new LinkedHashMap<>();
    /** Stand-in for the {@code partner_document} BIGSERIAL. */
    private final AtomicLong documentSeq = new AtomicLong(600_000L);

    /**
     * In-memory upload mirroring config-registry's
     * {@code PartnerDocumentService.upload}: doc-type roster validation, sha256
     * over the actual bytes, version bump + supersede per (partner, docType),
     * vault-shaped URI. Prior versions stay downloadable by id.
     */
    @Override
    public synchronized com.gme.pay.contracts.DocumentView uploadDocument(
            String partnerCode, String docType, String expiryDate,
            String filename, String contentType, byte[] content) {
        requireKnownPartner(partnerCode);
        if (docType == null || docType.isBlank() || !DOC_TYPES.contains(docType)) {
            throw badRequest("docType must be one of " + DOC_TYPES + ", was: " + docType);
        }
        if (filename == null || filename.isBlank()) {
            throw badRequest("filename is required");
        }
        if (content == null || content.length == 0) {
            throw badRequest("file is required and must not be empty");
        }
        java.time.LocalDate expiry = null;
        if (expiryDate != null && !expiryDate.isBlank()) {
            try {
                expiry = java.time.LocalDate.parse(expiryDate);
            } catch (java.time.format.DateTimeParseException e) {
                throw badRequest("expiryDate must be an ISO-8601 date (yyyy-MM-dd), was: "
                        + expiryDate);
            }
        }

        List<com.gme.pay.contracts.DocumentView> current =
                new ArrayList<>(documentStore.getOrDefault(partnerCode, List.of()));
        com.gme.pay.contracts.DocumentView prior = current.stream()
                .filter(d -> docType.equals(d.docType()))
                .findFirst()
                .orElse(null);
        int version = prior == null ? 1 : prior.version() + 1;
        if (prior != null) {
            current.remove(prior);
        }

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Long id = documentSeq.getAndIncrement();
        com.gme.pay.contracts.DocumentView fresh = new com.gme.pay.contracts.DocumentView(
                id,
                docType,
                filename,
                contentType == null || contentType.isBlank()
                        ? "application/octet-stream" : contentType,
                "mem://gmepay-partner-vault/" + partnerCode + "/" + docType
                        + "/stub-" + id + "/v" + version,
                version,
                sha256Hex(content),
                expiry,
                null,
                null,
                now,
                null,
                now);
        current.add(fresh);
        documentStore.put(partnerCode, current);
        documentById.put(id, fresh);
        documentOwner.put(id, partnerCode);
        documentBytes.put(id, content.clone());
        return fresh;
    }

    @Override
    public synchronized List<com.gme.pay.contracts.DocumentView> listDocuments(String partnerCode) {
        requireKnownPartner(partnerCode);
        return new ArrayList<>(documentStore.getOrDefault(partnerCode, List.of()));
    }

    @Override
    public synchronized DocumentContent downloadDocument(String partnerCode, Long docId) {
        requireKnownPartner(partnerCode);
        com.gme.pay.contracts.DocumentView meta = documentById.get(docId);
        if (meta == null || !partnerCode.equals(documentOwner.get(docId))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no document " + docId + " for partner '" + partnerCode + "'");
        }
        return new DocumentContent(meta.filename(), meta.contentType(),
                documentBytes.get(docId).clone());
    }

    private void requireKnownPartner(String partnerCode) {
        if (!draftStore.containsKey(partnerCode) && !store.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
    }

    /** Lowercase hex SHA-256, mirroring lib-vault's digest discipline. */
    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }

    /** Mirror of config-registry's {@code KybService.validate} (same message shapes). */
    private static void validateKyb(com.gme.pay.contracts.KybCommand.UpdateStep3 cmd) {
        if (cmd.riskRating() != null && !cmd.riskRating().isBlank()
                && !RISK_RATINGS.contains(cmd.riskRating())) {
            throw badRequest("riskRating must be one of " + RISK_RATINGS
                    + ", was: " + cmd.riskRating());
        }
        if (cmd.riskRationale() != null && cmd.riskRationale().length() > 1000) {
            throw badRequest("riskRationale must be at most 1000 characters");
        }
        if (cmd.licenseType() != null && cmd.licenseType().length() > 50) {
            throw badRequest("licenseType must be at most 50 characters");
        }
        if (cmd.licenseNumber() != null && cmd.licenseNumber().length() > 50) {
            throw badRequest("licenseNumber must be at most 50 characters");
        }
        if (cmd.licenseAuthority() != null && cmd.licenseAuthority().length() > 100) {
            throw badRequest("licenseAuthority must be at most 100 characters");
        }
        if (cmd.uboList() != null) {
            for (int i = 0; i < cmd.uboList().size(); i++) {
                com.gme.pay.contracts.UboView u = cmd.uboList().get(i);
                String at = "uboList[" + i + "]";
                if (u == null || u.name() == null || u.name().isBlank()) {
                    throw badRequest(at + ".name is required");
                }
                if (u.name().length() > 120) {
                    throw badRequest(at + ".name must be at most 120 characters");
                }
                if (u.ownershipPct() != null
                        && (u.ownershipPct().compareTo(java.math.BigDecimal.ZERO) < 0
                            || u.ownershipPct().compareTo(new java.math.BigDecimal("100")) > 0)) {
                    throw badRequest(at + ".ownershipPct must be between 0 and 100, was: "
                            + u.ownershipPct().toPlainString());
                }
                if (u.country() != null && !u.country().isBlank()
                        && !u.country().matches("[A-Z]{2}")) {
                    throw badRequest(at + ".country must be ISO-3166 alpha-2 (e.g. KR), was: "
                            + u.country());
                }
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Mirror of config-registry's per-element contact validation (same messages shape). */
    private static void validateContact(com.gme.pay.contracts.ContactCommand cmd, int index) {
        String at = "contacts[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.role() == null || cmd.role().isBlank() || !CONTACT_ROLES.contains(cmd.role())) {
            throw badRequest(at + ".role must be one of " + CONTACT_ROLES + ", was: " + cmd.role());
        }
        if (cmd.name() == null || cmd.name().isBlank() || cmd.name().length() > 120) {
            throw badRequest(at + ".name is required (max 120 characters)");
        }
        if (cmd.email() == null || cmd.email().isBlank()
                || cmd.email().length() > 254 || !EMAIL.matcher(cmd.email()).matches()) {
            throw badRequest(at + ".email is not a valid email address: " + cmd.email());
        }
        if (cmd.phoneE164() != null && !cmd.phoneE164().isBlank()
                && !PHONE_E164.matcher(cmd.phoneE164()).matches()) {
            throw badRequest(at + ".phoneE164 must be E.164 format"
                    + " (+ followed by up to 15 digits), was: " + cmd.phoneE164());
        }
        if (cmd.notes() != null && cmd.notes().length() > 500) {
            throw badRequest(at + ".notes must be at most 500 characters");
        }
    }

    private static org.springframework.web.server.ResponseStatusException badRequest(String message) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }

    private static PartnerView buildView(Long id, PartnerCommand.CreateDraft req) {
        return new PartnerView(
                id,
                req.partnerCode(),
                req.type(),
                req.settlementCurrency(),
                req.settlementRoundingMode() == null ? RoundingMode.HALF_UP : req.settlementRoundingMode(),
                // V016 Expand-phase mirror: before the commercial-terms step
                // writes a real split, collection and settlement are the same
                // fact — same defensive default as PartnerEntity.onPersist.
                req.settlementCurrency(),
                req.settlementCurrency(),
                req.legalNameLocal(),
                req.legalNameRomanized(),
                req.taxId(),
                req.taxIdType(),
                req.countryOfIncorporation(),
                req.legalForm(),
                req.registeredAddress() == null ? null : req.registeredAddress().toView(),
                req.operatingAddress() == null ? null : req.operatingAddress().toView(),
                req.lei(),
                PartnerStatus.ONBOARDING,
                Instant.EPOCH,
                null,
                Instant.now());
    }

    /** Apply non-null Step-1 fields from the request onto the prior view, returning a new PartnerView. */
    private static PartnerView mergeStep1(PartnerView prior, PartnerCommand.UpdateStep1 req) {
        PartnerType type = req.type() != null ? req.type() : prior.type();
        String settlementCurrency = req.settlementCurrency() != null
                ? req.settlementCurrency() : prior.settlementCurrency();
        RoundingMode roundingMode = req.settlementRoundingMode() != null
                ? req.settlementRoundingMode() : prior.settlementRoundingMode();
        // V016 split carry-forward (mirrors PartnerStore.save): keep a genuine
        // split (either side differing from the prior legacy value); otherwise
        // re-mirror the possibly-updated settlement currency on both sides.
        boolean priorSplitIsRealConfig =
                (prior.collectionCcy() != null
                        && !prior.collectionCcy().equals(prior.settlementCurrency()))
                || (prior.settleACcy() != null
                        && !prior.settleACcy().equals(prior.settlementCurrency()));
        String collectionCcy = priorSplitIsRealConfig ? prior.collectionCcy() : settlementCurrency;
        String settleACcy = priorSplitIsRealConfig ? prior.settleACcy() : settlementCurrency;
        return new PartnerView(
                prior.id(),
                prior.partnerCode(),
                type,
                settlementCurrency,
                roundingMode,
                collectionCcy,
                settleACcy,
                req.legalNameLocal() != null ? req.legalNameLocal() : prior.legalNameLocal(),
                req.legalNameRomanized() != null ? req.legalNameRomanized() : prior.legalNameRomanized(),
                req.taxId() != null ? req.taxId() : prior.taxId(),
                req.taxIdType() != null ? req.taxIdType() : prior.taxIdType(),
                req.countryOfIncorporation() != null ? req.countryOfIncorporation() : prior.countryOfIncorporation(),
                req.legalForm() != null ? req.legalForm() : prior.legalForm(),
                req.registeredAddress() != null ? req.registeredAddress().toView() : prior.registeredAddress(),
                req.operatingAddress() != null ? req.operatingAddress().toView() : prior.operatingAddress(),
                req.lei() != null ? req.lei() : prior.lei(),
                PartnerStatus.ONBOARDING,
                prior.validFrom(),
                prior.validTo(),
                Instant.now());
    }

    private static RoundingMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return RoundingMode.HALF_UP;
        }
        return RoundingMode.valueOf(raw);
    }

    // -------- Slice 7 (7A/7B) scheme-enablement + corridor endpoints (PARTNER_SETUP_PLAN §Slice 7)

    /** Slice 7 scheme-enablement sets — keyed by partner_code, mirrors bulk-replace semantics. */
    private final Map<String, List<com.gme.pay.contracts.PartnerSchemeView>> schemeEnablementStore =
            new LinkedHashMap<>();
    /** Slice 7 corridor sets — keyed by partner_code, mirrors bulk-replace semantics. */
    private final Map<String, List<com.gme.pay.contracts.PartnerCorridorView>> corridorStore =
            new LinkedHashMap<>();
    /** Stand-in for the V022 + V023 BIGSERIALs (not needed for the BFF view — partnerId echoed). */

    /** Mirrors config-registry's V022 schemeId CHECK roster. */
    private static final java.util.Set<String> SCHEME_IDS = java.util.Set.of(
            "ZEROPAY", "BAKONG", "NAPAS_247", "PROMPT_PAY", "FAST_SG", "QRIS", "KHQR");
    /** Mirrors config-registry's V022 direction CHECK roster. */
    private static final java.util.Set<String> SCHEME_DIRECTIONS = java.util.Set.of(
            "INBOUND", "OUTBOUND", "BOTH");
    /** Mirrors config-registry's V022 role CHECK roster. */
    private static final java.util.Set<String> SCHEME_ROLES = java.util.Set.of(
            "ACQUIRER", "ISSUER", "BOTH");
    /** Mirrors config-registry's V022 approval-method CHECK roster. */
    private static final java.util.Set<String> APPROVAL_METHODS = java.util.Set.of(
            "CONFIRMATION", "SILENT");

    /**
     * In-memory step-7 scheme bulk replace mirroring config-registry's
     * {@code SchemeService.replaceDraftSchemes}: full-set replace with the same
     * validation roster (schemeId/direction/role rosters, duplicate schemeId key,
     * enabled-ZEROPAY wiring invariant) so MockMvc tests exercise the 400 paths.
     */
    @Override
    public synchronized List<com.gme.pay.contracts.PartnerSchemeView> patchDraftStep7Schemes(
            String partnerCode, PartnerCommand.UpdateStep7Schemes request) {
        PartnerView draft = draftStore.get(partnerCode);
        if (draft == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        if (request == null || request.schemes() == null) {
            throw badRequest("schemes is required (send an empty list to clear all schemes)");
        }
        List<com.gme.pay.contracts.PartnerSchemeCommand> schemes = request.schemes();
        java.util.Set<String> seenSchemes = new java.util.HashSet<>();
        for (int i = 0; i < schemes.size(); i++) {
            validateSchemeCommand(schemes.get(i), i);
            if (!seenSchemes.add(schemes.get(i).schemeId())) {
                throw badRequest("schemes[" + i + "]: duplicate schemeId '"
                        + schemes.get(i).schemeId()
                        + "' (at most one row per schemeId)");
            }
        }
        List<com.gme.pay.contracts.PartnerSchemeView> fresh = new ArrayList<>(schemes.size());
        for (com.gme.pay.contracts.PartnerSchemeCommand cmd : schemes) {
            fresh.add(new com.gme.pay.contracts.PartnerSchemeView(
                    draft.id(),
                    cmd.schemeId(),
                    cmd.direction(),
                    cmd.role(),
                    blankToNull(cmd.zeropayMerchantId()),
                    blankToNull(cmd.zeropaySubMerchantId()),
                    blankToNull(cmd.kftcInstitutionCode()),
                    blankToNull(cmd.partnerTypeChar()),
                    blankToNull(cmd.vaultSecretId()),
                    blankToNull(cmd.approvalMethodCpm()),
                    blankToNull(cmd.approvalMethodMpm()),
                    cmd.enabled() == null || cmd.enabled()));
        }
        schemeEnablementStore.put(partnerCode, List.copyOf(fresh));
        return List.copyOf(fresh);
    }

    @Override
    public synchronized List<com.gme.pay.contracts.PartnerSchemeView> listSchemeEnablements(
            String partnerCode) {
        if (!draftStore.containsKey(partnerCode) && !store.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        return schemeEnablementStore.getOrDefault(partnerCode, List.of());
    }

    /** Mirror of config-registry's {@code SchemeService.validateSchemeCommand} (same messages). */
    private static void validateSchemeCommand(
            com.gme.pay.contracts.PartnerSchemeCommand cmd, int index) {
        String at = "schemes[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.schemeId() == null || cmd.schemeId().isBlank()
                || !SCHEME_IDS.contains(cmd.schemeId())) {
            throw badRequest(at + ".schemeId must be one of " + SCHEME_IDS
                    + ", was: " + cmd.schemeId());
        }
        if (cmd.direction() == null || !SCHEME_DIRECTIONS.contains(cmd.direction())) {
            throw badRequest(at + ".direction must be one of " + SCHEME_DIRECTIONS
                    + ", was: " + cmd.direction());
        }
        if (cmd.role() == null || !SCHEME_ROLES.contains(cmd.role())) {
            throw badRequest(at + ".role must be one of " + SCHEME_ROLES
                    + ", was: " + cmd.role());
        }
        if (cmd.zeropayMerchantId() != null && cmd.zeropayMerchantId().length() > 40) {
            throw badRequest(at + ".zeropayMerchantId must be at most 40 characters");
        }
        if (cmd.kftcInstitutionCode() != null && cmd.kftcInstitutionCode().length() > 20) {
            throw badRequest(at + ".kftcInstitutionCode must be at most 20 characters");
        }
        if (cmd.approvalMethodCpm() != null && !cmd.approvalMethodCpm().isBlank()
                && !APPROVAL_METHODS.contains(cmd.approvalMethodCpm())) {
            throw badRequest(at + ".approvalMethodCpm must be one of " + APPROVAL_METHODS
                    + ", was: " + cmd.approvalMethodCpm());
        }
        if (cmd.approvalMethodMpm() != null && !cmd.approvalMethodMpm().isBlank()
                && !APPROVAL_METHODS.contains(cmd.approvalMethodMpm())) {
            throw badRequest(at + ".approvalMethodMpm must be one of " + APPROVAL_METHODS
                    + ", was: " + cmd.approvalMethodMpm());
        }
        // Enabled ZEROPAY must have zeropayMerchantId + kftcInstitutionCode (service invariant).
        boolean isEnabled = cmd.enabled() == null || cmd.enabled();
        if (isEnabled && "ZEROPAY".equals(cmd.schemeId())) {
            if (cmd.zeropayMerchantId() == null || cmd.zeropayMerchantId().isBlank()) {
                throw badRequest(at + ": an enabled ZEROPAY scheme row requires"
                        + " zeropayMerchantId");
            }
            if (cmd.kftcInstitutionCode() == null || cmd.kftcInstitutionCode().isBlank()) {
                throw badRequest(at + ": an enabled ZEROPAY scheme row requires"
                        + " kftcInstitutionCode");
            }
        }
    }

    /**
     * In-memory step-7 corridor bulk replace mirroring config-registry's
     * {@code CorridorService.replaceDraftCorridors}: full-set replace with the
     * same validation roster (country/currency format, duplicate lane key) so
     * MockMvc tests exercise the 400 paths.
     */
    @Override
    public synchronized List<com.gme.pay.contracts.PartnerCorridorView> patchDraftStep7Corridors(
            String partnerCode, PartnerCommand.UpdateStep7Corridors request) {
        PartnerView draft = draftStore.get(partnerCode);
        if (draft == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        if (request == null || request.corridors() == null) {
            throw badRequest("corridors is required (send an empty list to clear all corridors)");
        }
        List<com.gme.pay.contracts.PartnerCorridorCommand> corridors = request.corridors();
        java.util.Set<String> seenLanes = new java.util.HashSet<>();
        for (int i = 0; i < corridors.size(); i++) {
            validateCorridorCommand(corridors.get(i), i);
            com.gme.pay.contracts.PartnerCorridorCommand cmd = corridors.get(i);
            String lane = cmd.srcCountry() + ":" + cmd.srcCcy() + "->"
                    + cmd.dstCountry() + ":" + cmd.dstCcy();
            if (!seenLanes.add(lane)) {
                throw badRequest("corridors[" + i + "]: duplicate lane " + lane
                        + " (at most one row per corridor)");
            }
        }
        List<com.gme.pay.contracts.PartnerCorridorView> fresh = new ArrayList<>(corridors.size());
        for (com.gme.pay.contracts.PartnerCorridorCommand cmd : corridors) {
            fresh.add(new com.gme.pay.contracts.PartnerCorridorView(
                    draft.id(),
                    cmd.srcCountry(),
                    cmd.srcCcy(),
                    cmd.dstCountry(),
                    cmd.dstCcy(),
                    cmd.goLiveDate(),
                    cmd.isActive() == null || cmd.isActive()));
        }
        corridorStore.put(partnerCode, List.copyOf(fresh));
        return List.copyOf(fresh);
    }

    @Override
    public synchronized List<com.gme.pay.contracts.PartnerCorridorView> listCorridors(
            String partnerCode) {
        if (!draftStore.containsKey(partnerCode) && !store.containsKey(partnerCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "no partner '" + partnerCode + "'");
        }
        return corridorStore.getOrDefault(partnerCode, List.of());
    }

    /** Mirror of config-registry's {@code CorridorService.validateCorridorCommand} (same messages). */
    private static void validateCorridorCommand(
            com.gme.pay.contracts.PartnerCorridorCommand cmd, int index) {
        String at = "corridors[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.srcCountry() == null || !cmd.srcCountry().matches("[A-Z]{2}")) {
            throw badRequest(at + ".srcCountry must be ISO-3166 alpha-2"
                    + " (2 uppercase letters), was: " + cmd.srcCountry());
        }
        if (cmd.srcCcy() == null || !cmd.srcCcy().matches("[A-Z]{3}")) {
            throw badRequest(at + ".srcCcy must be ISO-4217 (3 uppercase letters), was: "
                    + cmd.srcCcy());
        }
        if (cmd.dstCountry() == null || !cmd.dstCountry().matches("[A-Z]{2}")) {
            throw badRequest(at + ".dstCountry must be ISO-3166 alpha-2"
                    + " (2 uppercase letters), was: " + cmd.dstCountry());
        }
        if (cmd.dstCcy() == null || !cmd.dstCcy().matches("[A-Z]{3}")) {
            throw badRequest(at + ".dstCcy must be ISO-4217 (3 uppercase letters), was: "
                    + cmd.dstCcy());
        }
    }

    /**
     * Returns the seed operating-hours rows for well-known scheme ids; empty list
     * for unknown ones (no 404 — reference data, unsupported schemeId is not an
     * error from the BFF's perspective).
     */
    @Override
    public List<com.gme.pay.contracts.SchemeOperatingHoursView> getSchemeOperatingHours(
            String schemeId) {
        if (schemeId == null || schemeId.isBlank()) {
            return List.of();
        }
        return switch (schemeId.toUpperCase(java.util.Locale.ROOT)) {
            // ZeroPay KR: Mon-Fri 09:00-22:00, Sat 09:00-17:00 (Asia/Seoul),
            // 21:00 settlement cutoff Mon-Fri — approximate reference data.
            case "ZEROPAY" -> List.of(
                    hours(schemeId, 0, "09:00", "22:00", "21:00", "Asia/Seoul"),
                    hours(schemeId, 1, "09:00", "22:00", "21:00", "Asia/Seoul"),
                    hours(schemeId, 2, "09:00", "22:00", "21:00", "Asia/Seoul"),
                    hours(schemeId, 3, "09:00", "22:00", "21:00", "Asia/Seoul"),
                    hours(schemeId, 4, "09:00", "22:00", "21:00", "Asia/Seoul"),
                    hours(schemeId, 5, "09:00", "17:00", null, "Asia/Seoul"));
            // PayNow SG: 24x7 (Singapore Standard Time).
            case "FAST_SG" -> List.of(
                    hours(schemeId, 0, "00:00", "23:59:59", null, "Asia/Singapore"),
                    hours(schemeId, 1, "00:00", "23:59:59", null, "Asia/Singapore"),
                    hours(schemeId, 2, "00:00", "23:59:59", null, "Asia/Singapore"),
                    hours(schemeId, 3, "00:00", "23:59:59", null, "Asia/Singapore"),
                    hours(schemeId, 4, "00:00", "23:59:59", null, "Asia/Singapore"),
                    hours(schemeId, 5, "00:00", "23:59:59", null, "Asia/Singapore"),
                    hours(schemeId, 6, "00:00", "23:59:59", null, "Asia/Singapore"));
            default -> List.of();
        };
    }

    private static com.gme.pay.contracts.SchemeOperatingHoursView hours(
            String schemeId, int weekday, String open, String close,
            String cutoff, String tz) {
        return new com.gme.pay.contracts.SchemeOperatingHoursView(
                schemeId,
                weekday,
                java.time.LocalTime.parse(open),
                java.time.LocalTime.parse(close),
                cutoff == null ? null : java.time.LocalTime.parse(cutoff),
                tz);
    }
}
