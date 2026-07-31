package com.gme.pay.kybadapter.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.TransactionScreeningEvidence;
import com.gme.pay.kyb.TransactionScreeningPolicy;
import com.gme.pay.kybadapter.audit.TransactionScreeningAuditor;
import com.gme.pay.kybadapter.testsupport.TestInternalAuth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Screening REQUIRED, no provider wired, production environment — the fail-closed default (gap T5-3).
 *
 * <p>This is the posture an owner reaches by naming a party in
 * {@code gmepay.screening.transaction.required-parties} without first buying a vendor, and the point of
 * these tests is that the consequence is stark and immediate rather than subtle: every payment
 * involving that party is refused. That is correct — a required check that did not happen is a refusal
 * — and it is exactly why the property ships empty and the documentation calls populating it a
 * compliance decision with a revenue consequence.
 */
@SpringBootTest(properties = {
        TestInternalAuth.SECRET_PROPERTY,
        "gmepay.screening.transaction.required-parties=BENEFICIARY",
        "gmepay.environment=prod"
})
@AutoConfigureMockMvc
class TransactionScreeningFailClosedTest {

    @Autowired
    private TransactionScreeningService service;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a required party that cannot be screened REFUSES the payment")
    void requiredButUnavailableRefuses() {
        TransactionScreeningOutcome outcome = service.screen("TXN-FC-1", "SENDMN",
                List.of(PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "c-1", "ACME")));

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.screeningInert()).isFalse();
        assertThat(outcome.refusalReason())
                .contains("REFUSE_SCREENING_UNAVAILABLE")
                .contains("BENEFICIARY");

        TransactionScreeningEvidence e = outcome.evidence().get(0);
        assertThat(e.posture())
                .isEqualTo(TransactionScreeningPolicy.Posture.REFUSE_SCREENING_UNAVAILABLE);
        assertThat(e.refused()).isTrue();
        assertThat(e.completedScreening()).isFalse();
    }

    @Test
    @DisplayName("a party NOT named in the requirement is untouched by the fail-closed posture")
    void unrequiredPartiesStillProceed() {
        TransactionScreeningOutcome outcome = service.screen("TXN-FC-2", "SENDMN",
                List.of(PaymentScreeningSubject.named(PaymentParty.MERCHANT, "m-1", "ACME")));

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.evidence().get(0).posture())
                .isEqualTo(TransactionScreeningPolicy.Posture.PROCEED_NOT_REQUIRED);
    }

    @Test
    @DisplayName("one refusing party refuses the payment even when the others pass")
    void oneRefusalIsEnough() {
        TransactionScreeningOutcome outcome = service.screen("TXN-FC-3", "SENDMN", List.of(
                PaymentScreeningSubject.named(PaymentParty.MERCHANT, "m-1", "ACME"),
                PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "c-1", "ACME")));

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.evidence()).hasSize(2);
        assertThat(outcome.refusalReason()).contains("BENEFICIARY");
    }

    @Test
    @DisplayName("the refusal is recorded AND audited under its own verb")
    void refusalIsAudited() throws Exception {
        mvc.perform(TestInternalAuth.internal(post("/v1/screening/transaction"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txnRef":"TXN-FC-4","partnerId":"SENDMN",
                                 "parties":[{"party":"BENEFICIARY","reference":"c-1","name":"ACME"}]}
                                """))
                // A refusal is a verdict, not a transport error — see the controller javadoc.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.evidence[0].posture").value("REFUSE_SCREENING_UNAVAILABLE"));

        String eventType = jdbc.queryForObject(
                "SELECT event_type FROM audit_log WHERE aggregate_id = ?", String.class, "TXN-FC-4");
        assertThat(eventType).isEqualTo(TransactionScreeningAuditor.TRANSACTION_SCREENING_REFUSED);
    }

    @Test
    @DisplayName("the override cannot be armed in this environment — the policy refuses to exist")
    void overrideIsNotAvailableHere() {
        // The service-level proof that the constructor guard is what stands between a production
        // deployment and a silent fail-open. The context under test booted precisely because the
        // override is off; arming it here would have failed startup (see TransactionScreeningPolicy).
        assertThat(service.policy().overrideArmed()).isFalse();
        assertThat(TransactionScreeningPolicy.isProduction(service.policy().environment())).isTrue();
    }
}
