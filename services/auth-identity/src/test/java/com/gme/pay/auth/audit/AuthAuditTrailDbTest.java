package com.gme.pay.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.audit.DbAuditPublisher;
import com.gme.pay.audit.HashChain;
import com.gme.pay.auth.dto.IssueKeyRequest;
import com.gme.pay.auth.dto.IssueKeyResponse;
import com.gme.pay.auth.dto.IssueTokenRequest;
import com.gme.pay.auth.dto.VerifyRequest;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.rbac.RbacAdminDtos.CreateRoleRequest;
import com.gme.pay.auth.rbac.RbacAdminDtos.GrantPermissionRequest;
import com.gme.pay.auth.rbac.RbacAdminService;
import com.gme.pay.auth.service.ApiKeyIssuanceService;
import com.gme.pay.auth.service.AuthVerificationService;
import com.gme.pay.auth.service.JwtTokenService;
import com.gme.pay.auth.testsupport.TestInternalAuth;
import com.gme.pay.internalauth.InternalAuthHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The durable half of T5-1, against a real datasource: rows actually reach {@code audit_log}, seal
 * into the ADR-007 hash chain, survive the rollback of the request that produced them, contain no
 * credential material, and a tampered row is detected and named.
 *
 * <p>This boots the whole service rather than a slice, deliberately: {@code REQUIRES_NEW} needs a
 * real transaction manager and a real connection pool, and Flyway must actually apply
 * {@code V007__audit_log.sql}. A slice test with a fake publisher would prove none of that — and
 * "we call the audit API" passing while nothing lands in the table is exactly the failure mode a
 * gap like this hides behind.
 *
 * <p>Its own H2 instance ({@code authid_audit}): the service default URL is shared by every test
 * context in the JVM, and rows written here would otherwise leak into the other slices' counts.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:authid_audit;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        // Prove the success path too — the flag is off in production for volume reasons (see
        // AuthVerificationService), but this test is about durability, not about volume.
        "gmepay.audit.auth.record-verify-success=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAuditTrailDbTest {

    @Autowired private AuthAuditService audit;
    @Autowired private AuditChainVerifier verifier;
    @Autowired private DbAuditPublisher publisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private MockMvc mvc;

    @Autowired private JwtTokenService tokens;
    @Autowired private ApiKeyIssuanceService keys;
    @Autowired private RbacAdminService rbac;
    @Autowired private AuthVerificationService authVerification;
    @Autowired private PrincipalRepository principals;

    @BeforeEach
    void freshTable() {
        // audit_log is append-only in production; the test owns this database, and starting from
        // empty is what makes the chain assertions deterministic.
        jdbc.execute("DELETE FROM audit_log");
    }

    // ── the table exists, and rows land in it ────────────────────────────────────────────

    @Test
    @DisplayName("V007 created audit_log with the chain columns, and a write reaches it")
    void migrationRanAndWritesLand() {
        audit.record("auth_token", "sub:svc:x", AuthAuditEvents.TOKEN_ISSUED, null, "{\"a\":1}");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor_id, event_type, chain_version, prev_hash, row_hash FROM audit_log");
        assertThat(row.get("event_type")).isEqualTo(AuthAuditEvents.TOKEN_ISSUED);
        // Sealed under the CURRENT digest, not the weaker v1 one.
        assertThat(((Number) row.get("chain_version")).intValue())
                .isEqualTo(HashChain.CURRENT_CHAIN_VERSION);
        // Both hash columns hold a full 32-byte SHA-256. Asserted on the byte[] rather than via
        // SQL length(): H2's length() over a binary column does not reliably report the byte count
        // (it under-counts a digest ending in 0x00), which would make this test flaky roughly one
        // run in 256 for a reason that has nothing to do with the audit trail.
        assertThat((byte[]) row.get("prev_hash")).hasSize(HashChain.HASH_LEN)
                .isEqualTo(HashChain.GENESIS);
        assertThat((byte[]) row.get("row_hash")).hasSize(HashChain.HASH_LEN);
        // No HTTP request is bound here, so the row is honestly unattributed — never "system".
        assertThat(row.get("actor_id")).isEqualTo(AuditActors.UNATTRIBUTED);
    }

    // ── the requirement that shapes the whole design ─────────────────────────────────────

    @Test
    @DisplayName("a rejection row SURVIVES the rollback of the transaction it was written in")
    void rejectionRowSurvivesRollback() {
        String aggregateId = "apikey:rollback-probe";

        new TransactionTemplate(txManager).execute(status -> {
            audit.recordRejection(AuthAuditEvents.SESSION, aggregateId,
                    AuthAuditEvents.AUTH_VERIFY_FAILED, "{\"errorCode\":\"INVALID_API_KEY\"}");
            // The business path decides the attempt was bad and unwinds everything it did. Without
            // REQUIRES_NEW on the audit write, the row would unwind with it and the audit trail
            // would be systematically empty for exactly the events that matter most.
            status.setRollbackOnly();
            return null;
        });

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE aggregate_id = ?", Integer.class,
                aggregateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a success row shares the business transaction's fate and rolls back with it")
    void successRowRollsBackWithTheBusinessWrite() {
        String aggregateId = "sub:svc:rollback-probe";

        new TransactionTemplate(txManager).execute(status -> {
            audit.record(AuthAuditEvents.TOKEN, aggregateId,
                    AuthAuditEvents.TOKEN_ISSUED, null, "{}");
            status.setRollbackOnly();
            return null;
        });

        // The other half of the contract: an audit row asserting a state change must NOT commit
        // when the state change did not. (This is why record/recordRejection are two methods.)
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE aggregate_id = ?", Integer.class,
                aggregateId)).isZero();
    }

    @Test
    @DisplayName("a failed authentication is audited, and is NOT attributed to a real principal")
    void failedAuthenticationIsAuditedUnattributed() {
        VerifyRequest req = new VerifyRequest("pk_live_neverIssued00000000000000000",
                "POST", "/v1/payments", Instant.now().toString(), UUID.randomUUID().toString(),
                "0".repeat(64), "bodyhash");

        assertThat(authVerification.verify(req).valid()).isFalse();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor_id, aggregate_id, event_type FROM audit_log "
                        + "WHERE event_type = ?", AuthAuditEvents.AUTH_VERIFY_FAILED);
        String actor = (String) row.get("actor_id");
        // The row exists even though the attempt failed...
        assertThat(row.get("aggregate_id")).asString().startsWith("apikey:pk_live_never");
        // ...and it names nobody: a rejected credential has no verified subject, and attributing
        // the attempt to the principal being impersonated would be fabricating evidence.
        assertThat(AuditActors.isAttributable(actor)).isFalse();
        // Called directly (no bound HTTP request), so the honest answer is "unattributed". Over
        // HTTP with a claimed-but-unproven operator it would be "unverified:<claim>" — the
        // resolver's own matrix is pinned in AuthAuditActorResolverTest.
        assertThat(actor).isEqualTo(AuditActors.UNATTRIBUTED);
        assertThat(actor).isNotEqualToIgnoringCase(AuditActors.LEGACY_SYSTEM);
    }

    // ── credential material never reaches the table ──────────────────────────────────────

    @Test
    @DisplayName("no audit row contains a raw secret, api-key secret or token")
    void noRowContainsCredentialMaterial() {
        IssueKeyResponse issued = keys.issue(new IssueKeyRequest(
                42L, "AUDITPARTNER", "SANDBOX", "API", "pk_test_", "sk_test_", null));
        String token = tokens.issue(new IssueTokenRequest("svc:audited", null, null)).token();
        keys.revoke(issued.keyId());

        String everything = dumpPayloads();

        // Three distinct kinds of credential, all in scope at their call sites, none in the table.
        assertThat(everything).doesNotContain(issued.secretPlaintext().toLowerCase(Locale.ROOT));
        assertThat(everything).doesNotContain(token.toLowerCase(Locale.ROOT));
        // Not even the signature segment of the JWT on its own.
        assertThat(everything).doesNotContain(
                token.substring(token.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        // Sanity: the dump is not empty, so the assertions above are not vacuous.
        assertThat(everything).contains(issued.keyId().toLowerCase(Locale.ROOT));
        assertThat(everything).contains("sha256:");
    }

    // ── the privileged writes each produce a row ─────────────────────────────────────────

    @Test
    @DisplayName("an RBAC grant and a credential issue/revoke each produce a durable row")
    void privilegedWritesProduceRows() {
        var role = rbac.createRole(new CreateRoleRequest("T51_DB_ROLE", "db test", null));
        var perm = rbac.listPermissions().stream()
                .filter(p -> "rbac.manage".equals(p.code())).findFirst().orElseThrow();
        rbac.grantPermission(role.id(), new GrantPermissionRequest(perm.id(), null, null));

        Long principalId = principals.saveAndFlush(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "t51-db-user", "T51", null, Instant.now())).getId();
        rbac.assignRole(principalId, new com.gme.pay.auth.rbac.RbacAdminDtos.AssignRoleRequest(
                role.id(), null, null, null, null, null));

        IssueKeyResponse issued = keys.issue(new IssueKeyRequest(
                7L, "T51PARTNER", "PRODUCTION", "API", "pk_live_", "sk_live_", null));
        keys.revoke(issued.keyId());
        tokens.issue(new IssueTokenRequest("svc:t51", Map.of("permissions", "*"), null));

        assertThat(eventTypes()).contains(
                AuthAuditEvents.ROLE_CREATED,
                AuthAuditEvents.PERMISSION_GRANTED,
                AuthAuditEvents.ROLE_ASSIGNED,
                AuthAuditEvents.API_KEY_ISSUED,
                AuthAuditEvents.API_KEY_REVOKED,
                AuthAuditEvents.TOKEN_ISSUED);

        // The grant row is queryable the way a regulator asks for it.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, AuthAuditEvents.PERMISSION_GRANTED, "T51_DB_ROLE")).isEqualTo(1);
    }

    // ── the chain ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the chain verifies intact after several writes to the same aggregate")
    void chainVerifiesIntactAfterSeveralWrites() {
        for (int i = 0; i < 5; i++) {
            audit.recordRejection(AuthAuditEvents.TOKEN, AuthAuditEvents.UNKNOWN_SUBJECT,
                    AuthAuditEvents.TOKEN_VERIFY_FAILED, "{\"attempt\":" + i + "}");
        }

        var report = verifier.verify(AuthAuditEvents.TOKEN, AuthAuditEvents.UNKNOWN_SUBJECT);
        assertThat(report.intact()).isTrue();
        assertThat(report.rowsChecked()).isEqualTo(5);
        assertThat(report.firstBrokenRowId()).isNull();
        assertThat(report.breakKind()).isNull();
        // Nothing here writes the weaker v1 digest — reported rather than assumed, because a
        // non-zero count would itself be the finding.
        assertThat(report.legacyV1Rows()).isZero();

        // Each row's prev_hash really is its predecessor's row_hash (not merely self-consistent).
        List<DbAuditPublisher.ChainRow> rows =
                publisher.loadChainRows(AuthAuditEvents.TOKEN, AuthAuditEvents.UNKNOWN_SUBJECT);
        assertThat(rows.get(0).prevHash()).isEqualTo(HashChain.GENESIS);
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).prevHash()).isEqualTo(rows.get(i - 1).rowHash());
        }
    }

    @Test
    @DisplayName("a row edited in place is caught, and the verifier names its id")
    void tamperedRowIsDetectedAndNamed() {
        for (int i = 0; i < 4; i++) {
            audit.recordRejection(AuthAuditEvents.SESSION, "partner:99",
                    AuthAuditEvents.AUTH_VERIFY_FAILED, "{\"errorCode\":\"INVALID_SIGNATURE\"}");
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM audit_log WHERE aggregate_id = 'partner:99' ORDER BY id ASC",
                Long.class);
        Long victim = ids.get(2);

        // The attack the chain exists to detect: rewrite a row's payload in place and leave its
        // stored hashes alone. Nothing in the schema stops this — only re-deriving the digest does.
        jdbc.update("UPDATE audit_log SET after_jsonb = ? WHERE id = ?",
                "{\"errorCode\":\"OK\"}".getBytes(StandardCharsets.UTF_8), victim);

        var report = verifier.verify(AuthAuditEvents.SESSION, "partner:99");
        assertThat(report.intact()).isFalse();
        // Named by PRIMARY KEY, not by position: "row 3 of the chain" is useless in an incident.
        assertThat(report.firstBrokenRowId()).isEqualTo(victim);
        assertThat(report.breakKind()).isEqualTo(HashChain.BreakKind.ROW_HASH_MISMATCH.name());
        assertThat(report.detail()).contains("MODIFIED");

        // A full sweep surfaces it too, and counts it.
        var sweep = verifier.verifyAll();
        assertThat(sweep.intact()).isFalse();
        assertThat(sweep.chainsBroken()).isEqualTo(1);
        assertThat(sweep.chains().get(0).firstBrokenRowId()).isEqualTo(victim);
    }

    @Test
    @DisplayName("deleting a row breaks the LINK, reported as a different kind of break")
    void deletedRowIsDetectedAsALinkBreak() {
        for (int i = 0; i < 3; i++) {
            audit.recordRejection(AuthAuditEvents.SESSION, "partner:77",
                    AuthAuditEvents.AUTH_VERIFY_FAILED, "{\"i\":" + i + "}");
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM audit_log WHERE aggregate_id = 'partner:77' ORDER BY id ASC",
                Long.class);

        // Removing the MIDDLE row: the survivor's prev_hash now points at a row that is gone.
        jdbc.update("DELETE FROM audit_log WHERE id = ?", ids.get(1));

        var report = verifier.verify(AuthAuditEvents.SESSION, "partner:77");
        assertThat(report.intact()).isFalse();
        assertThat(report.breakKind()).isEqualTo(HashChain.BreakKind.PREV_HASH_MISMATCH.name());
        assertThat(report.firstBrokenRowId()).isEqualTo(ids.get(2));
    }

    @Test
    @DisplayName("an empty / unknown aggregate is intact-with-zero-rows, not an error")
    void unknownAggregateIsTriviallyIntact() {
        var report = verifier.verify(AuthAuditEvents.SESSION, "partner:does-not-exist");
        assertThat(report.intact()).isTrue();
        assertThat(report.rowsChecked()).isZero();
    }

    // ── the verification endpoint ────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /internal/auth/audit/chain reports the outcome to a trusted caller")
    void chainEndpointReportsToTrustedCallers() throws Exception {
        audit.record(AuthAuditEvents.ROLE, "T51_HTTP", AuthAuditEvents.ROLE_CREATED, null, "{}");

        mvc.perform(get("/internal/auth/audit/chain")
                        .header(InternalAuthHeaders.INTERNAL_TOKEN, TestInternalAuth.SECRET)
                        .param("aggregateType", AuthAuditEvents.ROLE)
                        .param("aggregateId", "T51_HTTP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.rowsChecked").value(1))
                .andExpect(jsonPath("$.firstBrokenRowId").doesNotExist());

        mvc.perform(get("/internal/auth/audit/chains")
                        .header(InternalAuthHeaders.INTERNAL_TOKEN, TestInternalAuth.SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainsBroken").value(0))
                .andExpect(jsonPath("$.chainsChecked").value(1));
    }

    @Test
    @DisplayName("the verification endpoint is behind the internal-auth gate")
    void chainEndpointIsGated() throws Exception {
        // The response is a map of the audit trail (row counts, row ids) — useful to an operator
        // confirming integrity and equally useful to an attacker choosing what to rewrite. And the
        // sweep re-hashes every row, so anonymous access is also unbounded free work.
        mvc.perform(get("/internal/auth/audit/chain")
                        .param("aggregateType", AuthAuditEvents.ROLE)
                        .param("aggregateId", "T51_HTTP"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/auth/audit/chains"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    /** Every before/after payload in the table, lower-cased, as one blob. */
    private String dumpPayloads() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT before_jsonb, after_jsonb FROM audit_log");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            sb.append(asText(row.get("before_jsonb"))).append('\n');
            sb.append(asText(row.get("after_jsonb"))).append('\n');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String asText(Object bytea) {
        if (bytea == null) {
            return "";
        }
        return new String((byte[]) bytea, StandardCharsets.UTF_8);
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM audit_log", String.class);
    }
}
