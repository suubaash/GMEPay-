package com.gme.pay.kybadapter.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kyb.TransactionScreeningEvidence;
import com.gme.pay.kyb.UnscreenedReason;
import com.gme.pay.kybadapter.audit.TransactionScreeningAuditor;
import com.gme.pay.kybadapter.testsupport.TestInternalAuth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The SHIPPED posture: no screening provider is wired and no party is required (gap T5-3).
 *
 * <p>Two claims are defended here and they pull in opposite directions, which is why they need each
 * other. First, <b>nothing changes</b> — no payment is refused, so merging this seam did not quietly
 * become a business decision. Second, <b>the absence is on the record</b> — every party produces a
 * dated, sealed, attributed row saying nothing was screened and why, so "we had no screening" is a
 * queryable fact rather than an unexamined assumption.
 */
@SpringBootTest(properties = TestInternalAuth.SECRET_PROPERTY)
@AutoConfigureMockMvc
class TransactionScreeningDefaultPostureTest {

    @Autowired
    private TransactionScreeningService service;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static PaymentScreeningSubject beneficiary(String name) {
        return PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "cust-1", name);
    }

    // ------------------------------------------------------------------
    // Nothing is refused
    // ------------------------------------------------------------------

    @Test
    @DisplayName("with nothing required, an unscreened payment is ALLOWED — behaviour is unchanged")
    void defaultPostureAllows() {
        TransactionScreeningOutcome outcome = service.screen(
                "TXN-DEF-1", "SENDMN", List.of(beneficiary("ACME TRADING LLC")));

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.refusalReason()).isNull();
        assertThat(outcome.screeningInert()).isTrue();
        // ... and the payment being allowed says nothing about the counterparty.
        assertThat(outcome.fullyScreened()).isFalse();
    }

    @Test
    @DisplayName("a transaction with no parties is allowed but is NOT reported as screened")
    void noPartiesIsNotVacuouslyScreened() {
        TransactionScreeningOutcome outcome = service.screen("TXN-DEF-EMPTY", "SENDMN", List.of());

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.evidence()).isEmpty();
        assertThat(outcome.fullyScreened())
                .as("vacuous truth is the wrong answer to a compliance question")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // The absence is recorded
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the outcome is recorded with provenance, and is not a completed screening")
    void outcomeIsRecordedWithProvenance() {
        service.screen("TXN-DEF-2", "SENDMN", List.of(beneficiary("ACME TRADING LLC")));

        List<TransactionScreeningEvidence> stored = service.evidenceFor("TXN-DEF-2");
        assertThat(stored).hasSize(1);
        TransactionScreeningEvidence e = stored.get(0);

        assertThat(e.providerId()).isEqualTo(ScreeningProvenance.NO_PROVIDER_ID);
        assertThat(e.providerAuthoritative()).isFalse();
        assertThat(e.status()).isEqualTo(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER);
        assertThat(e.completedScreening()).isFalse();
        assertThat(e.unscreenedReason()).isEqualTo(UnscreenedReason.NO_PROVIDER);
        assertThat(e.caveat()).contains("no sanctions/PEP screening provider is configured");
        assertThat(e.screenedAt()).isNotNull();
    }

    @Test
    @DisplayName("the subject's NAME never reaches the evidence row — only which attributes were present")
    void noSubjectPiiIsPersisted() {
        service.screen("TXN-DEF-3", "SENDMN",
                List.of(new PaymentScreeningSubject(PaymentParty.BENEFICIARY, "cust-1",
                        "ACME TRADING LLC", "KR", "1980-01-01")));

        String attributes = jdbc.queryForObject(
                "SELECT subject_attributes FROM transaction_screening WHERE txn_ref = ?",
                String.class, "TXN-DEF-3");

        assertThat(attributes).doesNotContain("ACME").doesNotContain("1980-01-01");
        assertThat(attributes).contains("name=present").contains("dob=present").contains("country=KR");

        Integer piiColumns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE UPPER(table_name) ="
                        + " 'TRANSACTION_SCREENING' AND UPPER(column_name) IN"
                        + " ('NAME', 'SUBJECT_NAME', 'DATE_OF_BIRTH', 'DOB', 'ADDRESS')",
                Integer.class);
        assertThat(piiColumns).as("the evidence table must not become a new PII store (T5-5)")
                .isZero();
    }

    @Test
    @DisplayName("a party we hold no name for is NO_SUBJECT_IDENTITY — a contract gap, not a vendor gap")
    void referenceOnlyPartyIsAContractGap() {
        service.screen("TXN-DEF-4", null,
                List.of(PaymentScreeningSubject.byReferenceOnly(PaymentParty.PAYER, "user-uuid")));

        TransactionScreeningEvidence e = service.evidenceFor("TXN-DEF-4").get(0);
        assertThat(e.unscreenedReason()).isEqualTo(UnscreenedReason.NO_SUBJECT_IDENTITY);
        assertThat(e.caveat()).contains("no screenable identity");
        assertThat(e.partnerId()).isNull();
    }

    @Test
    @DisplayName("re-screening a party overwrites the current answer and appends a second audit row")
    void reScreenIsSingleValuedButHistoryIsAppendOnly() {
        service.screen("TXN-DEF-5", "SENDMN", List.of(beneficiary("ACME")));
        service.screen("TXN-DEF-5", "SENDMN", List.of(beneficiary("ACME")));

        assertThat(service.evidenceFor("TXN-DEF-5"))
                .as("one row per (txn_ref, party) — one current answer")
                .hasSize(1);
        assertThat(auditRows("TXN-DEF-5"))
                .as("the history of how the answer changed is append-only")
                .hasSize(2);
    }

    // ------------------------------------------------------------------
    // Audit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the audit row names an attested actor and is hash-chained")
    void auditRowIsAttestedAndSealed() throws Exception {
        mvc.perform(TestInternalAuth.internal(post("/v1/screening/transaction"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txnRef":"TXN-AUD-1","partnerId":"SENDMN",
                                 "parties":[{"party":"BENEFICIARY","reference":"c-1","name":"ACME"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.screeningInert").value(true))
                .andExpect(jsonPath("$.evidence[0].completedScreening").value(false))
                .andExpect(jsonPath("$.evidence[0].providerId").value("none"));

        List<Map<String, Object>> rows = auditRows("TXN-AUD-1");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);

        assertThat(row.get("aggregate_type")).isEqualTo("transaction_screening");
        assertThat(row.get("event_type")).isEqualTo(TransactionScreeningAuditor.TRANSACTION_SCREENED);
        // The caller presented the internal token but no X-Actor: attested service, unknown human.
        assertThat((String) row.get("actor_id")).isEqualTo("svc:internal-caller");
        assertThat((byte[]) row.get("row_hash")).hasSize(32);
        // CHAIN_V2 — the digest that also seals aggregate_type, aggregate_id and actor_ip (T5-1).
        assertThat(((Number) row.get("chain_version")).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("a forwarded operator subject is recorded as attested, not as a bare claim")
    void forwardedActorIsAttested() throws Exception {
        mvc.perform(TestInternalAuth.internal(post("/v1/screening/transaction"))
                        .header("X-Actor", "alice@gme.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txnRef":"TXN-AUD-2","partnerId":"SENDMN",
                                 "parties":[{"party":"MERCHANT","reference":"m-1","name":"ACME"}]}
                                """))
                .andExpect(status().isOk());

        assertThat(auditRows("TXN-AUD-2").get(0).get("actor_id")).isEqualTo("alice@gme.com");
    }

    @Test
    @DisplayName("the bare literal 'system' can never appear as an actor")
    void noBareSystemActor() {
        service.screen("TXN-AUD-3", "SENDMN", List.of(beneficiary("ACME")));

        Integer bare = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE actor_id = 'system'", Integer.class);
        assertThat(bare).isZero();
    }

    // ------------------------------------------------------------------
    // The database refuses the dishonest row too
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the DB refuses to store a CLEAR from a non-authoritative provider")
    void databaseRejectsNonAuthoritativeClear() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transaction_screening (txn_ref, party, provider_id,"
                        + " provider_authoritative, status, caveat, posture, screened_at, recorded_at)"
                        + " VALUES (?, ?, ?, FALSE, 'CLEAR', 'forged', 'PROCEED_SCREENED_CLEAR',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "TXN-FORGE-1", "BENEFICIARY", "octa"))
                .hasMessageContaining("chk_txn_screening_clear_is_authoritative");
    }

    @Test
    @DisplayName("the DB refuses a non-authoritative row with no caveat")
    void databaseRequiresACaveat() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transaction_screening (txn_ref, party, provider_id,"
                        + " provider_authoritative, status, posture, screened_at, recorded_at)"
                        + " VALUES (?, ?, ?, FALSE, 'NOT_SCREENED_NO_PROVIDER',"
                        + " 'PROCEED_NOT_REQUIRED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "TXN-FORGE-2", "BENEFICIARY", "octa"))
                .hasMessageContaining("chk_txn_screening_caveat_required");
    }

    @Test
    @DisplayName("the DB refuses to let the reserved provider ids claim authority")
    void databaseRejectsReservedProviderAuthority() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transaction_screening (txn_ref, party, provider_id,"
                        + " provider_authoritative, status, posture, screened_at, recorded_at)"
                        + " VALUES (?, ?, 'stub', TRUE, 'CLEAR', 'PROCEED_SCREENED_CLEAR',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "TXN-FORGE-3", "BENEFICIARY"))
                .hasMessageContaining("chk_txn_screening_reserved_provider");
    }

    // ------------------------------------------------------------------
    // Read surfaces
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the posture endpoint states plainly that nothing is screened")
    void postureIsHonest() throws Exception {
        mvc.perform(TestInternalAuth.internal(get("/v1/screening/posture")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("none"))
                .andExpect(jsonPath("$.providerAuthoritative").value(false))
                .andExpect(jsonPath("$.screeningInert").value(true))
                .andExpect(jsonPath("$.overrideArmed").value(false))
                .andExpect(jsonPath("$.requiredParties").value(
                        org.hamcrest.Matchers.containsString("no party requires screening")));
    }

    @Test
    @DisplayName("a transaction nobody screened is 404, not an empty 200")
    void unknownTransactionIs404() throws Exception {
        mvc.perform(TestInternalAuth.internal(get("/v1/screening/transaction/TXN-NEVER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the internal-auth gate covers the new surface")
    void surfaceIsInternalOnly() throws Exception {
        mvc.perform(get("/v1/screening/posture")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/screening/transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txnRef\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    private List<Map<String, Object>> auditRows(String txnRef) {
        return jdbc.queryForList(
                "SELECT * FROM audit_log WHERE aggregate_type = 'transaction_screening'"
                        + " AND aggregate_id = ? ORDER BY id ASC", txnRef);
    }
}
