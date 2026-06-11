package com.gme.pay.registry.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.BankAccountCommand;
import com.gme.pay.contracts.BankAccountView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.bank.verify.StubVerificationAdapter;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 4 (4A.1) acceptance test for {@link PartnerBankAccountService} — the
 * {@code partner_bank_account} bulk-replace + verification path (V012) wired
 * end-to-end against the H2 PostgreSQL-mode database with Flyway V001..V012
 * applied. Mirrors the {@code ContactServiceTest} slice-test pattern:
 * {@code @DataJpaTest} + explicit {@code @Import} of the service/audit beans +
 * a {@link RecordingAuditPublisher} to observe ADR-007 publication, with the
 * deterministic {@link StubVerificationAdapter} backing the verification port.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Bulk replace inserts the new set as CURRENT rows and a second replace
 *       supersedes the first set — paired SCD-6 writes sharing one
 *       MICROS-truncated instant.</li>
 *   <li>Validation: BIC-8/11 shape, IBAN mod-97 checksum (only when the value
 *       is IBAN-shaped — KR raw account numbers pass untouched), ISO-4217 /
 *       ISO-3166 shapes, charge-bearer + purpose rosters, at most one primary
 *       account per currency — all 400, side-effect free, index-qualified.</li>
 *   <li>The verify endpoint path: stub returns KFTC_VERIFIED for KR accounts /
 *       BANK_LETTER otherwise; the verdict lands on a FRESH SCD-6 row with the
 *       verification date stamped, and survives a subsequent bulk replace when
 *       the (currency, account number) pair is unchanged.</li>
 *   <li>One {@code partner_bank_account} audit event per write, BEFORE null on
 *       the first replace and carrying the superseded snapshot afterwards.</li>
 *   <li>Unknown partner code → 404; partner outside ONBOARDING → 409 on the
 *       replace (but verification stays available — evidence, not mutation).</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BankAccountServiceTest.TestConfig.class, PartnerBankAccountService.class,
        StubVerificationAdapter.class, AuditLogService.class, PartnerStore.class,
        CacheConfig.class})
class BankAccountServiceTest {

    @Autowired
    private PartnerBankAccountService service;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code ContactServiceTest}: record what ADR-007 fans out. */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        com.gme.pay.audit.AuditPublisher auditPublisher(RecordingAuditPublisher recording) {
            return recording;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A canonically VALID IBAN (the ISO 13616 reference example). */
    private static final String VALID_IBAN = "GB82WEST12345698765432";

    /** Same IBAN with the last digit flipped — fails mod-97. */
    private static final String BAD_CHECKSUM_IBAN = "GB82WEST12345698765431";

    /** Create a partner draft through the canonical store path; returns its surrogate id. */
    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    /** A valid KR domestic payout account (raw account number — NOT IBAN). */
    private static BankAccountCommand krAccount(boolean primary) {
        return new BankAccountCommand("KRW", "Shinhan Bank", "SHBKKRSE",
                "110-123-456789", "GME Partner Co Ltd", "KR", null,
                null, primary, null, "PAYOUT");
    }

    /** A valid GB cross-border account with an IBAN + SWIFT charge bearer. */
    private static BankAccountCommand gbAccount(boolean primary) {
        return new BankAccountCommand("GBP", "NatWest", "NWBKGB2L",
                VALID_IBAN, "GME Partner Co Ltd", "GB", "CHASUS33XXX",
                null, primary, "SHA", "PAYOUT");
    }

    // -------------------------------------------------------------------- tests

    @Test
    void bulkReplace_insertsCurrentSetAndSupersedesPriorSet() {
        Long partnerId = seedPartner("BA_REPLACE");

        List<BankAccountView> first = service.replaceDraftBankAccounts("BA_REPLACE",
                List.of(krAccount(true), gbAccount(true)), "maker_kim");

        assertThat(first).hasSize(2);
        assertThat(first.get(0).id()).isNotNull();
        assertThat(first.get(0).currency()).isEqualTo("KRW");
        assertThat(first.get(0).verificationStatus()).isEqualTo("UNVERIFIED");
        assertThat(first.get(0).primary()).isTrue();
        assertThat(first.get(1).swiftChargeBearer()).isEqualTo("SHA");
        assertThat(first.get(1).purpose()).isEqualTo("PAYOUT");
        assertThat(bankAccountRepository.findCurrentByPartnerId(partnerId)).hasSize(2);

        // Second save: replace with a different single-account set.
        List<BankAccountView> second = service.replaceDraftBankAccounts("BA_REPLACE",
                List.of(new BankAccountCommand("USD", "Citibank Korea", "CITIKRSX",
                        "987654321012", "GME Partner Co Ltd", "KR", null,
                        null, true, "OUR", "FLOAT_TOPUP")),
                "maker_kim");

        assertThat(second).hasSize(1);
        assertThat(second.get(0).purpose()).isEqualTo("FLOAT_TOPUP");

        // Current view is exactly the second set.
        List<BankAccountEntity> current = bankAccountRepository.findCurrentByPartnerId(partnerId);
        assertThat(current).hasSize(1);
        assertThat(current.get(0).getCurrency()).isEqualTo("USD");

        // SCD-6: nothing deleted — 3 rows total, the first 2 superseded with a
        // superseded_at EXACTLY equal to the second set's recorded_at (the
        // paired-write instants are shared and MICROS-truncated).
        List<BankAccountEntity> all = bankAccountRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(3);
        List<BankAccountEntity> superseded = all.stream()
                .filter(e -> e.getSupersededAt() != null).toList();
        assertThat(superseded).hasSize(2);
        java.time.Instant freshRecordedAt = current.get(0).getRecordedAt();
        assertThat(superseded).allSatisfy(e ->
                assertThat(e.getSupersededAt())
                        .as("prior superseded_at must equal the fresh recorded_at (paired write)")
                        .isEqualTo(freshRecordedAt));
        // MICROS discipline: stored instants must carry no sub-microsecond part.
        assertThat(freshRecordedAt.getNano() % 1000).isZero();
    }

    @Test
    void bulkReplace_emptyListClearsAllAccounts() {
        Long partnerId = seedPartner("BA_CLEAR");
        service.replaceDraftBankAccounts("BA_CLEAR", List.of(krAccount(true)), null);
        assertThat(bankAccountRepository.findCurrentByPartnerId(partnerId)).hasSize(1);

        List<BankAccountView> cleared = service.replaceDraftBankAccounts("BA_CLEAR", List.of(), null);

        assertThat(cleared).isEmpty();
        assertThat(bankAccountRepository.findCurrentByPartnerId(partnerId)).isEmpty();
        assertThat(service.currentBankAccounts("BA_CLEAR")).isEmpty();
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("BA_INVALID");

        record Bad(String label, BankAccountCommand cmd) {}
        List<Bad> bads = List.of(
                new Bad("bad currency", new BankAccountCommand("KRWX", "Bank", null,
                        "123456", "Holder", "KR", null, null, false, null, null)),
                new Bad("lowercase currency", new BankAccountCommand("krw", "Bank", null,
                        "123456", "Holder", "KR", null, null, false, null, null)),
                new Bad("missing bank name", new BankAccountCommand("KRW", "  ", null,
                        "123456", "Holder", "KR", null, null, false, null, null)),
                new Bad("bad BIC (5 letters)", new BankAccountCommand("KRW", "Bank", "SHBKK",
                        "123456", "Holder", "KR", null, null, false, null, null)),
                new Bad("bad BIC (lowercase)", new BankAccountCommand("KRW", "Bank", "shbkkrse",
                        "123456", "Holder", "KR", null, null, false, null, null)),
                new Bad("bad intermediary BIC", new BankAccountCommand("GBP", "Bank", "NWBKGB2L",
                        VALID_IBAN, "Holder", "GB", "NOPE", null, false, null, null)),
                new Bad("IBAN failing mod-97", new BankAccountCommand("GBP", "Bank", "NWBKGB2L",
                        BAD_CHECKSUM_IBAN, "Holder", "GB", null, null, false, null, null)),
                new Bad("missing account number", new BankAccountCommand("KRW", "Bank", null,
                        " ", "Holder", "KR", null, null, false, null, null)),
                new Bad("account number with spaces", new BankAccountCommand("KRW", "Bank", null,
                        "110 123 456789", "Holder", "KR", null, null, false, null, null)),
                new Bad("missing holder", new BankAccountCommand("KRW", "Bank", null,
                        "123456", "  ", "KR", null, null, false, null, null)),
                new Bad("bad country", new BankAccountCommand("KRW", "Bank", null,
                        "123456", "Holder", "KOR", null, null, false, null, null)),
                new Bad("bad charge bearer", new BankAccountCommand("KRW", "Bank", null,
                        "123456", "Holder", "KR", null, null, false, "ALL", null)),
                new Bad("bad purpose", new BankAccountCommand("KRW", "Bank", null,
                        "123456", "Holder", "KR", null, null, false, null, "SLUSH_FUND")),
                new Bad("non-positive evidence doc id", new BankAccountCommand("KRW", "Bank", null,
                        "123456", "Holder", "KR", null, 0L, false, null, null)));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.replaceDraftBankAccounts(
                    "BA_INVALID", List.of(bad.cmd()), "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        // A 400 must be side-effect free: no rows landed.
        assertThat(bankAccountRepository.findCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void validation_badElementIndexIsCarriedInTheMessage() {
        seedPartner("BA_INDEX");
        assertThatThrownBy(() -> service.replaceDraftBankAccounts("BA_INDEX", List.of(
                krAccount(false),
                new BankAccountCommand("KRW", "Bank", "BADBIC", "123456",
                        "Holder", "KR", null, null, false, null, null)),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("bankAccounts[1].bicSwift");
                });
    }

    @Test
    void validation_ibanDispatch_validIbanAndKoreanRawAccountBothPass() {
        seedPartner("BA_IBAN");

        // GB82... passes mod-97; the KR dashed account is NOT IBAN-shaped and is
        // accepted as a raw account number without any checksum attempt.
        List<BankAccountView> saved = service.replaceDraftBankAccounts("BA_IBAN",
                List.of(gbAccount(true), krAccount(true)), null);

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).ibanOrAccountNumber()).isEqualTo(VALID_IBAN);
        assertThat(saved.get(1).ibanOrAccountNumber()).isEqualTo("110-123-456789");
    }

    @Test
    void validation_atMostOnePrimaryPerCurrency() {
        seedPartner("BA_PRIMARY");

        // Two primaries in the SAME currency → 400.
        assertThatThrownBy(() -> service.replaceDraftBankAccounts("BA_PRIMARY", List.of(
                krAccount(true),
                new BankAccountCommand("KRW", "Kookmin Bank", "CZNBKRSE",
                        "456-789-012345", "GME Partner Co Ltd", "KR", null,
                        null, true, null, "REFUND")),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("primary").contains("KRW");
                });

        // One primary per DIFFERENT currency is fine.
        List<BankAccountView> saved = service.replaceDraftBankAccounts("BA_PRIMARY",
                List.of(krAccount(true), gbAccount(true)), "maker_kim");
        assertThat(saved).extracting(BankAccountView::primary).containsExactly(true, true);
    }

    @Test
    void verify_stampsKftcVerifiedForKoreanAccount_onAFreshScd6Row() {
        Long partnerId = seedPartner("BA_VERIFY_KR");
        List<BankAccountView> saved = service.replaceDraftBankAccounts("BA_VERIFY_KR",
                List.of(krAccount(true)), "maker_kim");
        Long originalId = saved.get(0).id();
        publisher.clear();

        BankAccountView verified = service.verifyBankAccount("BA_VERIFY_KR", originalId, "checker_lee");

        // Stub contract: bank_country=KR → KFTC_VERIFIED, with the date stamped.
        assertThat(verified.verificationStatus()).isEqualTo("KFTC_VERIFIED");
        assertThat(verified.verificationDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        // SCD-6: the verdict lands on a FRESH row; the original is superseded.
        assertThat(verified.id()).isNotEqualTo(originalId);
        assertThat(verified.ibanOrAccountNumber()).isEqualTo("110-123-456789");
        BankAccountEntity original = bankAccountRepository.findById(originalId).orElseThrow();
        assertThat(original.getSupersededAt()).isNotNull();
        assertThat(bankAccountRepository.findCurrentByPartnerId(partnerId)).hasSize(1);

        // One PARTNER_BANK_ACCOUNT_VERIFIED audit event, before/after snapshots.
        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).aggregateType()).isEqualTo("partner_bank_account");
        assertThat(events.get(0).eventType()).isEqualTo("PARTNER_BANK_ACCOUNT_VERIFIED");
        assertThat(events.get(0).actorId()).isEqualTo("checker_lee");
        assertThat(new String(events.get(0).beforeJsonb(), StandardCharsets.UTF_8))
                .contains("\"verificationStatus\":\"UNVERIFIED\"");
        assertThat(new String(events.get(0).afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"verificationStatus\":\"KFTC_VERIFIED\"");
    }

    @Test
    void verify_stampsBankLetterForOverseasAccount() {
        seedPartner("BA_VERIFY_GB");
        List<BankAccountView> saved = service.replaceDraftBankAccounts("BA_VERIFY_GB",
                List.of(gbAccount(true)), null);

        BankAccountView verified = service.verifyBankAccount(
                "BA_VERIFY_GB", saved.get(0).id(), null);

        assertThat(verified.verificationStatus()).isEqualTo("BANK_LETTER");
        assertThat(verified.verificationDate()).isNotNull();
    }

    @Test
    void verify_supersededOrForeignRowIds404() {
        seedPartner("BA_VERIFY_404");
        List<BankAccountView> saved = service.replaceDraftBankAccounts("BA_VERIFY_404",
                List.of(krAccount(true)), null);
        Long oldId = saved.get(0).id();
        // Replace mints a fresh row; the old id is now history.
        service.replaceDraftBankAccounts("BA_VERIFY_404", List.of(krAccount(true)), null);

        assertThatThrownBy(() -> service.verifyBankAccount("BA_VERIFY_404", oldId, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.verifyBankAccount("BA_VERIFY_404", 999_999L, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // Another partner cannot verify this partner's row (cross-partner probe).
        seedPartner("BA_VERIFY_OTHER");
        Long currentId = service.currentBankAccounts("BA_VERIFY_404").get(0).id();
        assertThatThrownBy(() -> service.verifyBankAccount("BA_VERIFY_OTHER", currentId, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void bulkReplace_carriesVerificationForward_whenAccountCoordinatesUnchanged() {
        seedPartner("BA_CARRY");
        List<BankAccountView> saved = service.replaceDraftBankAccounts("BA_CARRY",
                List.of(krAccount(true)), null);
        service.verifyBankAccount("BA_CARRY", saved.get(0).id(), null);

        // Replace keeping the same (currency, account number): verdict survives.
        List<BankAccountView> kept = service.replaceDraftBankAccounts("BA_CARRY",
                List.of(new BankAccountCommand("KRW", "Shinhan Bank (renamed)", "SHBKKRSE",
                        "110-123-456789", "GME Partner Co Ltd", "KR", null,
                        null, true, null, "PAYOUT")),
                null);
        assertThat(kept.get(0).verificationStatus()).isEqualTo("KFTC_VERIFIED");
        assertThat(kept.get(0).verificationDate()).isNotNull();

        // Replace with a DIFFERENT account number: verification resets.
        List<BankAccountView> reset = service.replaceDraftBankAccounts("BA_CARRY",
                List.of(new BankAccountCommand("KRW", "Shinhan Bank", "SHBKKRSE",
                        "999-999-999999", "GME Partner Co Ltd", "KR", null,
                        null, true, null, "PAYOUT")),
                null);
        assertThat(reset.get(0).verificationStatus()).isEqualTo("UNVERIFIED");
        assertThat(reset.get(0).verificationDate()).isNull();
    }

    @Test
    void audit_publishesOneEventPerReplace_withBeforeAfterSnapshots() {
        seedPartner("BA_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.replaceDraftBankAccounts("BA_AUDIT", List.of(krAccount(true)), "maker_kim");
        service.replaceDraftBankAccounts("BA_AUDIT", List.of(gbAccount(true)), "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).as("one audit event per bulk replace").hasSize(2);

        AuditEvent firstWrite = events.get(0);
        assertThat(firstWrite.aggregateType()).isEqualTo("partner_bank_account");
        assertThat(firstWrite.aggregateId()).isEqualTo("BA_AUDIT");
        assertThat(firstWrite.eventType()).isEqualTo("PARTNER_BANK_ACCOUNTS_REPLACED");
        assertThat(firstWrite.actorId()).isEqualTo("maker_kim");
        assertThat(firstWrite.beforeJsonb())
                .as("first replace has no prior set — BEFORE must be null").isNull();
        assertThat(new String(firstWrite.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"bankName\":\"Shinhan Bank\"")
                .contains("\"primary\":true");

        AuditEvent secondWrite = events.get(1);
        assertThat(secondWrite.actorId()).isEqualTo("checker_lee");
        assertThat(new String(secondWrite.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded set")
                .contains("\"bankName\":\"Shinhan Bank\"");
        assertThat(new String(secondWrite.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"bankName\":\"NatWest\"")
                .contains("\"swiftChargeBearer\":\"SHA\"");
    }

    @Test
    void unknownPartner_404_onReadWriteAndVerify() {
        assertThatThrownBy(() -> service.replaceDraftBankAccounts("BA_GHOST", List.of(), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentBankAccounts("BA_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.verifyBankAccount("BA_GHOST", 1L, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void nullAccountsList_isRejectedWith400() {
        seedPartner("BA_NULL");
        assertThatThrownBy(() -> service.replaceDraftBankAccounts("BA_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void nonOnboardingPartner_409onReplace_butVerifyStaysAvailable() {
        seedPartner("BA_LIVE");
        List<BankAccountView> saved = service.replaceDraftBankAccounts("BA_LIVE",
                List.of(krAccount(true)), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("BA_LIVE").orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        // Coordinate mutations are locked once the draft leaves ONBOARDING
        // (Slice 8 brings the 2-authorized-signatory flow for those).
        assertThatThrownBy(() -> service.replaceDraftBankAccounts("BA_LIVE",
                List.of(krAccount(true)), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads and re-verification (evidence, not mutation) still work.
        assertThat(service.currentBankAccounts("BA_LIVE")).hasSize(1);
        BankAccountView verified = service.verifyBankAccount("BA_LIVE", saved.get(0).id(), "checker_lee");
        assertThat(verified.verificationStatus()).isEqualTo("KFTC_VERIFIED");
    }
}
