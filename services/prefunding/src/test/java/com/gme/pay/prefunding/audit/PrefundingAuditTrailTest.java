package com.gme.pay.prefunding.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerRepository;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import com.gme.pay.prefunding.service.PrefundingService;
import com.gme.pay.prefunding.testsupport.TestInternalAuth;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Gap T5-1 / CISO §9 — "Prefunding balance movement — <b>NO</b>: {@code ledger_entry} (V002) has no
 * actor, no reason, no IP column".
 *
 * <p>This class pins the properties that make the new {@code audit_log} trail worth having. Each test
 * corresponds to a way the old state of affairs was untrustworthy:
 *
 * <ul>
 *   <li>a float movement is recorded <b>exactly once</b>, with the before and after position and the
 *       operator who caused it — the thing {@code ledger_entry} structurally could not hold;</li>
 *   <li>a credit-limit push is recorded at all — that mutation wrote {@code partner_balance} with no
 *       ledger row, so it previously left no trace anywhere;</li>
 *   <li>an <b>unproven</b> actor claim can never be recorded as an attested principal, and a claim
 *       shaped like a system principal cannot be laundered into one through an attested channel;</li>
 *   <li>a genuinely system-driven movement carries a <b>named</b> system principal and specifically
 *       not the bare {@code "system"} literal, which used to mean both "nobody told me" and "the
 *       platform did this, and 4-eyes does not apply".</li>
 * </ul>
 *
 * <p>Rows are read back with raw SQL rather than through a repository on purpose: {@code audit_log}
 * has no JPA entity in this service (lib-audit's {@link com.gme.pay.audit.DbAuditPublisher} writes it
 * with plain JDBC), and asserting on the stored bytes is the only way to prove what actually went
 * into the hash.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrefundingAuditTrailTest {

    private static final String PARTNER = "AUD_P1";
    private static final String OPERATOR = "alice@gme.com";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrefundingService service;
    @Autowired private PrefundingAuditor auditor;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private BalanceAlertRepository alerts;
    @Autowired private CumulativeUsageLedgerRepository cumulative;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM audit_log");
        alerts.deleteAll();
        ledger.deleteAll();
        cumulative.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("1000.00000000"), new BigDecimal("100.00000000"), Instant.now()));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // -------------------------------------------------------------------------
    // 1. the movement itself
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a deduct writes exactly ONE partner_balance audit row carrying the before/after position and the operator")
    void deduct_writesOneRowWithBeforeAndAfter() throws Exception {
        callAs(OPERATOR, post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"txn-1\",\"amountUsd\":\"250.00000000\"}"))
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = rows(PrefundingAuditor.AGG_BALANCE, PARTNER);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);

        assertThat(row.get("event_type")).isEqualTo(PrefundingAuditor.BALANCE_DEBITED);
        // Attested: the caller presented the internal token AND forwarded a human subject, so the
        // actor is recorded bare — no namespace prefix — per the AuditActors vocabulary.
        assertThat(row.get("actor_id")).isEqualTo(OPERATOR);
        assertThat(AuditActors.isAttributable((String) row.get("actor_id"))).isTrue();
        // The IP column the CISO audit called out as absent is populated.
        assertThat((String) row.get("actor_ip")).isNotBlank();

        // Fixed key order, plain decimals, nulls emitted — see CanonicalJson for why this is asserted
        // as an exact byte string rather than parsed: these bytes ARE the hash input.
        assertThat(json(row, "before_jsonb")).isEqualTo(
                "{\"balance\":\"1000.00000000\",\"reserved\":\"0.00000000\","
                        + "\"creditLimit\":\"0.00000000\",\"currency\":\"USD\"}");
        assertThat(json(row, "after_jsonb"))
                .startsWith("{\"balance\":\"750.00000000\",\"reserved\":\"0.00000000\","
                        + "\"creditLimit\":\"0.00000000\",\"currency\":\"USD\","
                        + "\"entryType\":\"DEBIT\",\"amountUsd\":\"250.00000000\","
                        + "\"txnRef\":\"txn-1\",\"ledgerEntryId\":")
                .endsWith(",\"reason\":null}");
    }

    @Test
    @DisplayName("an idempotent replay writes NO second audit row — a redelivery is not a movement")
    void replay_writesNoSecondRow() throws Exception {
        MockHttpServletRequestBuilder deduct = post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"txn-dup\",\"amountUsd\":\"10.00000000\"}");
        callAs(OPERATOR, deduct).andExpect(status().isOk());
        callAs(OPERATOR, post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"txn-dup\",\"amountUsd\":\"10.00000000\"}"))
                .andExpect(status().isOk());

        assertThat(rows(PrefundingAuditor.AGG_BALANCE, PARTNER)).hasSize(1);
    }

    @Test
    @DisplayName("a credit-limit push is audited on its own chain — the mutation that previously left no trace at all")
    void creditLimitPush_isAuditedOnTheLimitChain() throws Exception {
        callAs(OPERATOR, put("/internal/v1/prefunding/{p}/credit-limit", PARTNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"creditLimitUsd\":\"500.00\",\"amlDailyCapUsd\":\"1000.00\","
                        + "\"amlDailyTxnCountCap\":25}"))
                .andExpect(status().isOk());

        // Nothing on the balance chain: no float moved.
        assertThat(rows(PrefundingAuditor.AGG_BALANCE, PARTNER)).isEmpty();

        List<Map<String, Object>> rows = rows(PrefundingAuditor.AGG_LIMIT, PARTNER);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("event_type")).isEqualTo(PrefundingAuditor.PARTNER_LIMITS_PUSHED);
        assertThat(rows.get(0).get("actor_id")).isEqualTo(OPERATOR);
        assertThat(json(rows.get(0), "before_jsonb")).isEqualTo(
                "{\"creditLimitUsd\":\"0.00000000\",\"amlDailyCapUsd\":null,"
                        + "\"amlMonthlyCapUsd\":null,\"amlAnnualCapUsd\":null,"
                        + "\"amlDailyTxnCountCap\":null}");
        assertThat(json(rows.get(0), "after_jsonb")).isEqualTo(
                "{\"creditLimitUsd\":\"500.00\",\"amlDailyCapUsd\":\"1000.00\","
                        + "\"amlMonthlyCapUsd\":null,\"amlAnnualCapUsd\":null,"
                        + "\"amlDailyTxnCountCap\":25,\"reason\":\"config-registry limit push\"}");
    }

    // -------------------------------------------------------------------------
    // 2. an unproven claim can never be recorded as attested
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a forged X-Actor with no valid credential lands as unverified:* — never as an operator id")
    void forgedActorClaim_isRecordedUnverified() {
        // A request carrying an operator name and a WRONG internal token. In a real deployment the
        // internal-auth gate 401s this before any controller runs; the resolver is nevertheless
        // required to be honest on its own, because its correctness must not depend on another
        // component's configuration.
        onRequest("203.0.113.7", "not-the-internal-secret", OPERATOR);
        service.credit(PARTNER, new BigDecimal("40000.00"));

        Map<String, Object> row = onlyRow(PrefundingAuditor.AGG_BALANCE, PARTNER);
        assertThat(row.get("actor_id")).isEqualTo(AuditActors.UNVERIFIED_PREFIX + OPERATOR);
        assertThat(AuditActors.isAttributable((String) row.get("actor_id")))
                .as("an unproven claim must not read as an attested principal")
                .isFalse();
        // The claim is kept (forensic value) but is structurally impossible to join against a real
        // operator id — that is the whole point of the namespace.
        assertThat((String) row.get("actor_id")).isNotEqualTo(OPERATOR);
    }

    @Test
    @DisplayName("no credential and no claim lands as 'unattributed' — the honest spelling of the old silent \"system\"")
    void absentActorAndCredential_isUnattributed() {
        onRequest("203.0.113.9", null, null);
        service.credit(PARTNER, new BigDecimal("1.00"));

        Map<String, Object> row = onlyRow(PrefundingAuditor.AGG_BALANCE, PARTNER);
        assertThat(row.get("actor_id")).isEqualTo(AuditActors.UNATTRIBUTED);
        assertThat(AuditActors.isAttributable((String) row.get("actor_id"))).isFalse();
    }

    @Test
    @DisplayName("an attested channel cannot mint a system principal: X-Actor 'system:auto-suspend' is downgraded")
    void attestedChannelCannotForgeSystemPrincipal() {
        onRequest("203.0.113.11", TestInternalAuth.SECRET, "system:auto-suspend");
        service.credit(PARTNER, new BigDecimal("5.00"));

        Map<String, Object> row = onlyRow(PrefundingAuditor.AGG_BALANCE, PARTNER);
        assertThat(row.get("actor_id")).isEqualTo("unverified:system:auto-suspend");
        assertThat(AuditActors.isSystem((String) row.get("actor_id")))
                .as("a forged claim must not be readable as a platform principal")
                .isFalse();
    }

    @Test
    @DisplayName("a trusted service that forwards no human is svc:* — the service is attested, the human is not claimed")
    void serviceCallerWithoutHumanPrincipal_isServiceActor() throws Exception {
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .header(InternalAuthHeaders.INTERNAL_TOKEN, TestInternalAuth.SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"txn-svc\",\"amountUsd\":\"1.00\"}"))
                .andExpect(status().isOk());

        Map<String, Object> row = onlyRow(PrefundingAuditor.AGG_BALANCE, PARTNER);
        assertThat(row.get("actor_id")).isEqualTo(AuditActors.SERVICE_PREFIX + "internal-caller");
        // Attributable (the caller proved itself) but not a human — the row says which of the two is known.
        assertThat(AuditActors.isAttributable((String) row.get("actor_id"))).isTrue();
        assertThat((String) row.get("actor_id")).isNotEqualTo(OPERATOR);
    }

    // -------------------------------------------------------------------------
    // 3. system-driven movements name their component
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the payment.reversed float release is attributed to system:payment-reversed-consumer, not \"system\"")
    void eventDrivenRelease_isNamedSystemPrincipal() {
        // No request in scope at all — exactly the Kafka consumer's situation.
        RequestContextHolder.resetRequestAttributes();
        service.releaseReversedFloat(PARTNER, "txn-rev", new BigDecimal("120.00"));

        Map<String, Object> row = onlyRow(PrefundingAuditor.AGG_BALANCE, PARTNER);
        String actor = (String) row.get("actor_id");
        assertThat(row.get("event_type")).isEqualTo(PrefundingAuditor.REVERSED_FLOAT_RELEASED);
        assertThat(actor).isEqualTo("system:payment-reversed-consumer");
        assertThat(actor)
                .as("the bare literal is unmintable — it meant both 'unknown' and 'no 4-eyes needed'")
                .isNotEqualToIgnoringCase(AuditActors.LEGACY_SYSTEM);
        assertThat(AuditActors.isSystem(actor)).isTrue();
        // Off-request, so there is no IP to claim, and none is invented.
        assertThat(row.get("actor_ip")).isNull();
        assertThat(json(row, "after_jsonb"))
                .contains("\"reason\":\"payment.reversed event — releasing held prefund float\"");
    }

    @Test
    @DisplayName("the breach auto-suspend proposal is attributed to system:prefunding-breach-auto-suspend")
    void breachAutoSuspend_isNamedSystemPrincipal() {
        // Give the partner credit headroom so a deduct can push the balance negative (the breach
        // condition is balance < 0 having previously been >= 0).
        service.setCreditLimit(PARTNER, new BigDecimal("5000.00"));
        service.deduct(PARTNER, "txn-breach", new BigDecimal("1500.00000000"));

        List<Map<String, Object>> rows = rows(PrefundingAuditor.AGG_BALANCE, PARTNER);
        Map<String, Object> breach = rows.stream()
                .filter(r -> PrefundingAuditor.BREACH_SUSPENSION_PROPOSED.equals(r.get("event_type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no BREACH_SUSPENSION_PROPOSED row: " + rows));

        String actor = (String) breach.get("actor_id");
        assertThat(actor).isEqualTo("system:prefunding-breach-auto-suspend");
        assertThat(actor).isNotEqualToIgnoringCase(AuditActors.LEGACY_SYSTEM);
        assertThat(AuditActors.isSystem(actor)).isTrue();
        assertThat(json(breach, "after_jsonb"))
                .contains("\"balanceUsd\":\"-500.00000000\"")
                .contains("prefunding balance breached");
    }

    @Test
    @DisplayName("the local demo seed is attributed to system:demo-seed-runner — USD 50k from nowhere is traceable")
    void demoSeed_isNamedSystemPrincipal() {
        // The runner is @Profile("!test") so it does not fire in this context; it is driven directly
        // here against the real repository and auditor, which is the production wiring.
        balances.deleteAll();
        jdbc.update("DELETE FROM audit_log");
        RequestContextHolder.resetRequestAttributes();

        new com.gme.pay.prefunding.PrefundingSeedRunner(balances, auditor).run();

        Map<String, Object> row = onlyRow(PrefundingAuditor.AGG_BALANCE, "SENDMN");
        String actor = (String) row.get("actor_id");
        assertThat(row.get("event_type")).isEqualTo(PrefundingAuditor.BALANCE_PROVISIONED);
        assertThat(actor).isEqualTo("system:demo-seed-runner");
        assertThat(actor).isNotEqualToIgnoringCase(AuditActors.LEGACY_SYSTEM);
        assertThat(json(row, "after_jsonb")).contains("\"balance\":\"50000.00000000\"");
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    /** Perform a request as a trusted caller forwarding {@code actor} as the verified human. */
    private ResultActions callAs(String actor, MockHttpServletRequestBuilder rb) throws Exception {
        return mvc.perform(TestInternalAuth.authed(rb)
                .header(AuditActorResolver.ACTOR_HEADER, actor));
    }

    /**
     * Install a fake request on the thread so service calls made directly (no MockMvc) resolve their
     * actor exactly as they would while serving that request.
     */
    private void onRequest(String remoteAddr, String internalToken, String claimedActor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (internalToken != null) {
            request.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalToken);
        }
        if (claimedActor != null) {
            request.addHeader(AuditActorResolver.ACTOR_HEADER, claimedActor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private List<Map<String, Object>> rows(String aggregateType, String aggregateId) {
        return jdbc.queryForList(
                "SELECT id, aggregate_type, aggregate_id, actor_id, actor_ip, event_type, "
                        + "before_jsonb, after_jsonb, chain_version FROM audit_log "
                        + "WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY id ASC",
                aggregateType, aggregateId);
    }

    private Map<String, Object> onlyRow(String aggregateType, String aggregateId) {
        List<Map<String, Object>> rows = rows(aggregateType, aggregateId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static String json(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : new String((byte[]) value, StandardCharsets.UTF_8);
    }
}
