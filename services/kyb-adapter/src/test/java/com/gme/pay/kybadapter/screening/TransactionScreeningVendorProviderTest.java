package com.gme.pay.kybadapter.screening;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningPort;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kyb.TransactionScreeningEvidence;
import com.gme.pay.kyb.TransactionScreeningPolicy;
import com.gme.pay.kyb.UnscreenedReason;
import com.gme.pay.kybadapter.audit.TransactionScreeningAuditor;
import com.gme.pay.kybadapter.testsupport.TestInternalAuth;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Screening configured AND available (gap T5-3): the shape of the world after a vendor is integrated.
 *
 * <p><b>This test does not fake a sanctions list.</b> {@link FakeVendorPort} is a test double that
 * demonstrates the SEAM — it answers from a hard-coded two-name table and exists only so the recording,
 * provenance and refusal paths can be exercised. It lives in the test source set, is never packaged,
 * and no production bean resembles it. The reason that distinction is laboured is that a
 * plausible-looking in-process "screening provider" is precisely what produced gap T1-4, and the fix
 * for that gap was to make such a thing structurally unable to report a clean result
 * ({@link ScreeningProvenance} refuses to let {@code stub} claim authority). A test double that CAN
 * claim authority is therefore only safe because it cannot be reached from any production wiring.
 */
@SpringBootTest(properties = {
        TestInternalAuth.SECRET_PROPERTY,
        "gmepay.screening.transaction.required-parties=BENEFICIARY",
        "gmepay.environment=prod"
})
class TransactionScreeningVendorProviderTest {

    /** Not a sanctions list. Two names, in a test source set, to exercise the seam. */
    static final String LISTED_NAME = "TEST FIXTURE LISTED ENTITY";

    @TestConfiguration
    static class FakeVendor {

        @Bean
        @Primary
        PaymentScreeningPort fakeVendorPort() {
            return new FakeVendorPort();
        }
    }

    static class FakeVendorPort implements PaymentScreeningPort {

        @Override
        public ScreeningResult screen(PaymentScreeningSubject subject) {
            boolean listed = LISTED_NAME.equalsIgnoreCase(subject.name());
            return new ScreeningResult(
                    listed ? ScreeningResult.Status.HIT : ScreeningResult.Status.CLEAR,
                    listed ? List.of(new ScreeningResult.Hit("TEST_FIXTURE_LIST", subject.name(), 1.0))
                           : List.of(),
                    Instant.parse("2026-07-28T09:00:00Z"),
                    "fixture-ref-1",
                    ScreeningProvenance.vendor("test-fixture-vendor"));
        }

        @Override
        public String providerId() {
            return "test-fixture-vendor";
        }

        @Override
        public boolean authoritative() {
            return true;
        }
    }

    @Autowired
    private TransactionScreeningService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("an available provider's clean verdict IS a completed screening, recorded with provenance")
    void availableProviderRecordsACompletedScreening() {
        TransactionScreeningOutcome outcome = service.screen("TXN-V-1", "SENDMN",
                List.of(PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "c-1", "ACME")));

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.fullyScreened()).isTrue();

        TransactionScreeningEvidence e = service.evidenceFor("TXN-V-1").get(0);
        assertThat(e.providerId()).isEqualTo("test-fixture-vendor");
        assertThat(e.providerAuthoritative()).isTrue();
        assertThat(e.status()).isEqualTo(ScreeningResult.Status.CLEAR);
        assertThat(e.completedScreening()).isTrue();
        assertThat(e.unscreenedReason()).isNull();
        assertThat(e.caveat()).as("an authoritative screening has nothing to caveat").isNull();
        assertThat(e.screenedAt()).isEqualTo(Instant.parse("2026-07-28T09:00:00Z"));
        assertThat(e.posture())
                .isEqualTo(TransactionScreeningPolicy.Posture.PROCEED_SCREENED_CLEAR);
    }

    @Test
    @DisplayName("the completed outcome survives the database round-trip intact")
    void completedScreeningRoundTrips() {
        service.screen("TXN-V-2", "SENDMN",
                List.of(PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "c-1", "ACME")));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM transaction_screening WHERE txn_ref = ?", String.class, "TXN-V-2"))
                .isEqualTo("CLEAR");
        assertThat(jdbc.queryForObject(
                "SELECT provider_authoritative FROM transaction_screening WHERE txn_ref = ?",
                Boolean.class, "TXN-V-2")).isTrue();
        assertThat(service.evidenceFor("TXN-V-2").get(0).completedScreening()).isTrue();
    }

    @Test
    @DisplayName("a list match refuses the payment and audits under the refusal verb")
    void hitRefuses() {
        TransactionScreeningOutcome outcome = service.screen("TXN-V-3", "SENDMN",
                List.of(PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "c-1", LISTED_NAME)));

        assertThat(outcome.allowed()).isFalse();
        TransactionScreeningEvidence e = outcome.evidence().get(0);
        assertThat(e.status()).isEqualTo(ScreeningResult.Status.HIT);
        assertThat(e.completedScreening()).as("a HIT is a check that happened").isTrue();
        assertThat(e.posture()).isEqualTo(TransactionScreeningPolicy.Posture.REFUSE_SCREENING_HIT);

        assertThat(jdbc.queryForObject(
                "SELECT event_type FROM audit_log WHERE aggregate_id = ?", String.class, "TXN-V-3"))
                .isEqualTo(TransactionScreeningAuditor.TRANSACTION_SCREENING_REFUSED);
    }

    @Test
    @DisplayName("a party with no name is still NO_SUBJECT_IDENTITY — the vendor is never asked")
    void aVendorDoesNotFixTheMissingIdentity() {
        // The half of the gap a vendor purchase does not close: the payment contracts carry no
        // originator name, so even a live provider screens nobody on that party.
        TransactionScreeningOutcome outcome = service.screen("TXN-V-4", "SENDMN",
                List.of(PaymentScreeningSubject.byReferenceOnly(PaymentParty.BENEFICIARY, "c-1")));

        assertThat(outcome.allowed()).isFalse();
        TransactionScreeningEvidence e = outcome.evidence().get(0);
        assertThat(e.unscreenedReason()).isEqualTo(UnscreenedReason.NO_SUBJECT_IDENTITY);
        assertThat(e.completedScreening()).isFalse();
        assertThat(e.providerAuthoritative())
                .as("the provider was never asked, so it cannot have attested anything")
                .isFalse();
    }
}
