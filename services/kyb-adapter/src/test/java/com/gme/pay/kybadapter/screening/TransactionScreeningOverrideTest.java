package com.gme.pay.kybadapter.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.ScreeningRequirement;
import com.gme.pay.kyb.TransactionScreeningPolicy;
import com.gme.pay.kybadapter.audit.TransactionScreeningAuditor;
import com.gme.pay.kybadapter.testsupport.TestInternalAuth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The non-production fail-open override (gap T5-3): a required screening that cannot be performed is
 * tolerated, but never quietly.
 *
 * <p>The override exists so a sandbox can exercise the required-parties code path end to end without a
 * vendor contract. Its cost is that money moves without a check the configuration demands, so the
 * design makes that cost impossible to lose: a distinct posture, a distinct audit verb, and a
 * constructor that refuses to build this policy in production at all.
 */
@SpringBootTest(properties = {
        TestInternalAuth.SECRET_PROPERTY,
        "gmepay.screening.transaction.required-parties=BENEFICIARY,MERCHANT",
        "gmepay.screening.transaction.allow-unavailable-override=true",
        "gmepay.environment=sandbox"
})
class TransactionScreeningOverrideTest {

    @Autowired
    private TransactionScreeningService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the payment proceeds — that is what the override is for")
    void overriddenPaymentProceeds() {
        TransactionScreeningOutcome outcome = service.screen("TXN-OV-1", "SENDMN",
                List.of(PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "c-1", "ACME")));

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.screeningInert()).isFalse();
        assertThat(outcome.evidence().get(0).posture())
                .isEqualTo(TransactionScreeningPolicy.Posture.PROCEED_UNAVAILABLE_OVERRIDDEN);
    }

    @Test
    @DisplayName("...but it is never reportable as a completed or clean screening")
    void overriddenIsNotClean() {
        TransactionScreeningOutcome outcome = service.screen("TXN-OV-2", "SENDMN",
                List.of(PaymentScreeningSubject.named(PaymentParty.MERCHANT, "m-1", "ACME")));

        assertThat(outcome.fullyScreened()).isFalse();
        assertThat(outcome.evidence().get(0).completedScreening()).isFalse();
        assertThat(outcome.evidence().get(0).caveat()).isNotBlank();
    }

    @Test
    @DisplayName("...and every use of it lands under its own audit verb, findable in one query")
    void overrideIsAudited() {
        service.screen("TXN-OV-3", "SENDMN",
                List.of(PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "c-1", "ACME")));

        List<String> verbs = jdbc.queryForList(
                "SELECT event_type FROM audit_log WHERE aggregate_id = ?", String.class, "TXN-OV-3");

        assertThat(verbs)
                .containsExactly(TransactionScreeningAuditor.TRANSACTION_SCREENING_OVERRIDDEN);
    }

    @Test
    @DisplayName("the same configuration in production would refuse to start")
    void sameConfigurationIsUnbootableInProduction() {
        // The context under test only exists because the environment is 'sandbox'. Constructing the
        // identical policy for a production (or unlabelled) environment throws, so a service deployed
        // this way never serves a request — which is the difference between a guarded escape hatch and
        // a flag someone can flip on the wrong cluster.
        assertThat(service.policy().overrideArmed()).isTrue();

        for (String production : List.of("prod", "production", "", "eu-west-1")) {
            assertThatThrownBy(() -> new TransactionScreeningPolicy(
                    ScreeningRequirement.of(PaymentParty.BENEFICIARY), true, production))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("treated as production");
        }
    }
}
