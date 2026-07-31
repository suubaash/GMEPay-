package com.gme.pay.ratefx.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.ratefx.issue.RateSnapshotAdminService;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import com.gme.pay.ratefx.testsupport.TestInternalAuth;
import com.gme.pay.ratefx.xe.XeMultiRateResponse;
import com.gme.pay.ratefx.xe.XeRateClient;
import com.gme.pay.ratefx.xe.XeRateFetchScheduler;
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
 * Gap T5-1 / CISO §9 — "FX rate change — <b>NO</b>: {@code rate_snapshots} permits
 * {@code source='MANUAL'} with no actor column".
 *
 * <p>A manual treasury rate is the sharpest version of this gap in the platform: resolution reads the
 * most recent effective snapshot, so one hand-entered row re-prices every subsequent quote and payment
 * in that currency, and the table could tell you the rate but not the human. The tests here pin what
 * the new audit trail must guarantee:
 *
 * <ul>
 *   <li>a manual override writes <b>exactly one</b> row naming the operator, the rate it displaced and
 *       the rate it installed;</li>
 *   <li>an unproven {@code X-Actor} can never be recorded as an attested operator, and a claim shaped
 *       like the scheduler's own principal cannot be laundered into one;</li>
 *   <li>the automated provider poll is attributed to a <b>named</b> system principal, specifically not
 *       the bare {@code "system"} literal, so a fetched rate and a manual one are distinguishable at a
 *       glance in the same chain.</li>
 * </ul>
 *
 * <p>Rows are read back with raw SQL because {@code audit_log} has no JPA entity in this service —
 * lib-audit's {@link com.gme.pay.audit.DbAuditPublisher} writes it with plain JDBC — and asserting the
 * stored bytes is the only way to prove what actually went into the hash.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateAuditTrailTest {

    private static final String CCY = "MNT";
    private static final String OPERATOR = "alice@gme.com";
    private static final Instant EFFECTIVE_1 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant EFFECTIVE_2 = Instant.parse("2026-07-02T00:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RateSnapshotRepository snapshots;
    @Autowired private RateSnapshotAdminService adminService;
    @Autowired private RateAuditor auditor;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        snapshots.deleteAll();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // -------------------------------------------------------------------------
    // 1. the rate change itself
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a MANUAL override writes exactly ONE audit row naming the operator and the rate it displaced")
    void manualOverride_writesOneRowWithBeforeAndAfter() throws Exception {
        // First override: nothing was priced before, so `before` is the all-null state.
        override(OPERATOR, "3450.00", EFFECTIVE_1).andExpect(status().isCreated());

        List<Map<String, Object>> first = rows(CCY);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).get("event_type"))
                .isEqualTo(RateAuditor.RATE_SNAPSHOT_MANUAL_OVERRIDE);
        assertThat(first.get(0).get("actor_id")).isEqualTo(OPERATOR);
        assertThat(AuditActors.isAttributable((String) first.get(0).get("actor_id"))).isTrue();
        assertThat((String) first.get(0).get("actor_ip")).isNotBlank();
        // Fixed key order, explicit nulls — these bytes ARE the hash input, so they are asserted as an
        // exact string rather than parsed. "Never priced" must not encode like "priced at zero".
        assertThat(json(first.get(0), "before_jsonb")).isEqualTo(
                "{\"usdRate\":null,\"source\":null,\"snapshotId\":null,\"effectiveAt\":null}");
        assertThat(json(first.get(0), "after_jsonb"))
                .startsWith("{\"usdRate\":\"3450.00\",\"source\":\"MANUAL\",\"snapshotId\":\"manual-MNT-")
                .endsWith("\",\"effectiveAt\":\"2026-07-01T00:00:00Z\","
                        + "\"reason\":\"operator treasury-rate override via POST /v1/rates/snapshots\"}");

        // Second override: the audit row must show the MOVE, not just the new number.
        override(OPERATOR, "3900.00", EFFECTIVE_2).andExpect(status().isCreated());

        List<Map<String, Object>> both = rows(CCY);
        assertThat(both).hasSize(2);
        assertThat(json(both.get(1), "before_jsonb"))
                .startsWith("{\"usdRate\":\"3450.00000000\",\"source\":\"MANUAL\","
                        + "\"snapshotId\":\"manual-MNT-")
                .endsWith("\",\"effectiveAt\":\"2026-07-01T00:00:00Z\"}");
        assertThat(json(both.get(1), "after_jsonb")).contains("\"usdRate\":\"3900.00\"");
    }

    @Test
    @DisplayName("a PARTNER seed is a distinct verb — a partner-fed rate is not a treasury decision")
    void partnerSeed_hasItsOwnEventType() throws Exception {
        mvc.perform(authed(post("/v1/rates/snapshots"))
                        .header(AuditActorResolver.ACTOR_HEADER, OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"MNT\",\"usdRate\":\"3500.00\","
                                + "\"source\":\"PARTNER\",\"effectiveAt\":\"2026-07-01T00:00:00Z\"}"))
                .andExpect(status().isCreated());

        assertThat(rows(CCY)).singleElement()
                .extracting(r -> r.get("event_type"))
                .isEqualTo(RateAuditor.RATE_SNAPSHOT_PARTNER_RECORDED);
    }

    @Test
    @DisplayName("a rejected write (LIVE source) persists nothing and audits nothing — nothing changed")
    void rejectedWrite_isNotAudited() throws Exception {
        mvc.perform(authed(post("/v1/rates/snapshots"))
                        .header(AuditActorResolver.ACTOR_HEADER, OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"MNT\",\"usdRate\":\"1.00\",\"source\":\"LIVE\"}"))
                .andExpect(status().is4xxClientError());

        assertThat(rows(CCY)).isEmpty();
        assertThat(snapshots.count()).isZero();
    }

    // -------------------------------------------------------------------------
    // 2. an unproven claim can never be recorded as attested
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a forged X-Actor with no valid credential lands as unverified:* — never as an operator id")
    void forgedActorClaim_isRecordedUnverified() {
        // An operator name presented with a WRONG internal token. In a real deployment the
        // internal-auth gate 401s this before the controller runs (RateSnapshotAdminGateTest proves
        // that); the resolver must nonetheless be honest on its own, because its correctness cannot
        // depend on another component staying configured.
        onRequest("198.51.100.4", "not-the-internal-secret", OPERATOR);
        adminService.record(CCY, new BigDecimal("9999.00"), "MANUAL", EFFECTIVE_1);

        Map<String, Object> row = onlyRow(CCY);
        assertThat(row.get("actor_id")).isEqualTo(AuditActors.UNVERIFIED_PREFIX + OPERATOR);
        assertThat(AuditActors.isAttributable((String) row.get("actor_id")))
                .as("an unproven claim must not read as an attested principal")
                .isFalse();
        assertThat((String) row.get("actor_id")).isNotEqualTo(OPERATOR);
    }

    @Test
    @DisplayName("no credential and no claim lands as 'unattributed' — the honest spelling of the old silent \"system\"")
    void absentActorAndCredential_isUnattributed() {
        onRequest("198.51.100.6", null, null);
        adminService.record(CCY, new BigDecimal("3000.00"), "MANUAL", EFFECTIVE_1);

        Map<String, Object> row = onlyRow(CCY);
        assertThat(row.get("actor_id")).isEqualTo(AuditActors.UNATTRIBUTED);
        assertThat(AuditActors.isAttributable((String) row.get("actor_id"))).isFalse();
    }

    @Test
    @DisplayName("an attested channel cannot mint the scheduler's principal: X-Actor 'system:xe-rate-fetch-scheduler' is downgraded")
    void attestedChannelCannotForgeTheSchedulerPrincipal() {
        onRequest("198.51.100.8", TestInternalAuth.SECRET, "system:xe-rate-fetch-scheduler");
        adminService.record(CCY, new BigDecimal("3100.00"), "MANUAL", EFFECTIVE_1);

        Map<String, Object> row = onlyRow(CCY);
        assertThat(row.get("actor_id")).isEqualTo("unverified:system:xe-rate-fetch-scheduler");
        assertThat(AuditActors.isSystem((String) row.get("actor_id")))
                .as("a hand-typed rate must not be able to disguise itself as a provider poll")
                .isFalse();
        // …and the verb still says MANUAL, so the disguise fails on two independent columns.
        assertThat(row.get("event_type")).isEqualTo(RateAuditor.RATE_SNAPSHOT_MANUAL_OVERRIDE);
    }

    @Test
    @DisplayName("a trusted service that forwards no human is svc:* — honest, and countable")
    void serviceCallerWithoutHumanPrincipal_isServiceActor() throws Exception {
        mvc.perform(authed(post("/v1/rates/snapshots"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"MNT\",\"usdRate\":\"3200.00\","
                                + "\"source\":\"MANUAL\"}"))
                .andExpect(status().isCreated());

        Map<String, Object> row = onlyRow(CCY);
        assertThat(row.get("actor_id")).isEqualTo(AuditActors.SERVICE_PREFIX + "internal-caller");
        assertThat(AuditActors.isAttributable((String) row.get("actor_id"))).isTrue();
        assertThat((String) row.get("actor_id")).isNotEqualTo(OPERATOR);
    }

    // -------------------------------------------------------------------------
    // 3. the automated poll names its component
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the provider poll is attributed to system:xe-rate-fetch-scheduler, not \"system\"")
    void providerPoll_isNamedSystemPrincipal() {
        // The scheduler bean is @ConditionalOnProperty(gmepay.rate-fx.xe.enabled=true) and off in the
        // test profile, so it is constructed here against the REAL repository and the REAL auditor —
        // the audit behaviour under test is the production wiring, not a stand-in.
        XeRateClient client = mock(XeRateClient.class);
        when(client.fetchUsdRates()).thenReturn(new XeMultiRateResponse(
                "USD", "2026-07-01T10:00:00+09:00", "SIM_XE", Map.of(CCY, "3450.000000")));
        RequestContextHolder.resetRequestAttributes();   // off-request, exactly like the scheduler

        new XeRateFetchScheduler(client, snapshots, auditor).fetchAndUpsert();

        Map<String, Object> row = onlyRow(CCY);
        String actor = (String) row.get("actor_id");
        assertThat(row.get("event_type")).isEqualTo(RateAuditor.RATE_SNAPSHOT_LIVE_FETCHED);
        assertThat(actor).isEqualTo("system:xe-rate-fetch-scheduler");
        assertThat(actor)
                .as("the bare literal is unmintable — it meant both 'unknown' and 'no 4-eyes needed'")
                .isNotEqualToIgnoringCase(AuditActors.LEGACY_SYSTEM);
        assertThat(AuditActors.isSystem(actor)).isTrue();
        // Off-request, so there is no IP to claim and none is invented.
        assertThat(row.get("actor_ip")).isNull();
        assertThat(json(row, "after_jsonb"))
                .contains("\"source\":\"LIVE\"")
                .contains("\"reason\":\"scheduled provider poll (SIM_XE)\"");
    }

    @Test
    @DisplayName("a manual override and a provider poll for the same currency share one chain but stay distinguishable")
    void manualAndFetchedRatesAreDistinguishableInOneChain() throws Exception {
        XeRateClient client = mock(XeRateClient.class);
        when(client.fetchUsdRates()).thenReturn(new XeMultiRateResponse(
                "USD", "2026-07-01T10:00:00+09:00", "SIM_XE", Map.of(CCY, "3450.000000")));
        new XeRateFetchScheduler(client, snapshots, auditor).fetchAndUpsert();

        override(OPERATOR, "3900.00", EFFECTIVE_2).andExpect(status().isCreated());

        List<Map<String, Object>> rows = rows(CCY);
        assertThat(rows).hasSize(2);
        // The two questions an auditor asks — "was this a human?" and "which human?" — are answerable
        // from two columns, with no payload parsing.
        assertThat(rows).extracting(r -> r.get("event_type")).containsExactly(
                RateAuditor.RATE_SNAPSHOT_LIVE_FETCHED,
                RateAuditor.RATE_SNAPSHOT_MANUAL_OVERRIDE);
        assertThat(rows).extracting(r -> r.get("actor_id")).containsExactly(
                "system:xe-rate-fetch-scheduler", OPERATOR);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private ResultActions override(String actor, String rate, Instant effectiveAt) throws Exception {
        return mvc.perform(authed(post("/v1/rates/snapshots"))
                .header(AuditActorResolver.ACTOR_HEADER, actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currencyCode\":\"" + CCY + "\",\"usdRate\":\"" + rate + "\","
                        + "\"source\":\"MANUAL\",\"effectiveAt\":\"" + effectiveAt + "\"}"));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder rb) {
        return rb.header(InternalAuthHeaders.INTERNAL_TOKEN, TestInternalAuth.SECRET);
    }

    /**
     * Install a fake request on the thread so a direct service call resolves its actor exactly as it
     * would while serving that request.
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

    private List<Map<String, Object>> rows(String currencyCode) {
        return jdbc.queryForList(
                "SELECT id, actor_id, actor_ip, event_type, before_jsonb, after_jsonb, chain_version "
                        + "FROM audit_log WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY id ASC",
                RateAuditor.AGG_RATE_SNAPSHOT, currencyCode);
    }

    private Map<String, Object> onlyRow(String currencyCode) {
        List<Map<String, Object>> rows = rows(currencyCode);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static String json(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : new String((byte[]) value, StandardCharsets.UTF_8);
    }
}
