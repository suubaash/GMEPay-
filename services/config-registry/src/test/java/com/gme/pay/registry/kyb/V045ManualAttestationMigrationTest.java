package com.gme.pay.registry.kyb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Migration test for {@code V045__partner_kyb_manual_screening_attestation.sql} (gap T1-4, owner
 * decision 2026-07-28).
 *
 * <p>The migration adds an authority, so the load-bearing assertions are about what it must NOT
 * make possible:
 *
 * <ol>
 *   <li>V042's {@code ck_partner_kyb_clear_requires_authority} still rejects a clean row with no
 *       stated authority — the new value must not have been added by relaxing the old rule;</li>
 *   <li>a {@code CLEAR_MANUAL_ATTESTATION} row missing any part of its attestation is refused, so
 *       "a human screened this" cannot be claimed without naming the human, the instant, the SOP or
 *       the sources;</li>
 *   <li>attestation columns cannot decorate a non-manual (e.g. stub) run, which would make a
 *       machine result look human-verified;</li>
 *   <li>nothing historical is reclassified — every pre-existing row keeps the exact status V042
 *       left it with.</li>
 * </ol>
 *
 * <p>Same shape and Flyway locations as {@link V042ScreeningProvenanceMigrationTest}, so the H2
 * variants of V004 / V023 / V044 apply exactly as they do in the JPA slices.
 */
class V045ManualAttestationMigrationTest {

    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private static final String ATTESTER = "compliance.officer@gme.com";
    private static final String SOP_REF = "GME-COMP-SOP-014";
    private static final String SOP_VERSION = "v3";
    private static final String SOURCES = "UN consolidated list + the SOP §4 jurisdiction lists";

    private static String freshUrl() {
        return "jdbc:h2:mem:cfgv045_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }

    private static void migrateTo(String url, String target) {
        Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations("classpath:db/migration", "classpath:db/vendor/h2")
                .target(target)
                .load()
                .migrate();
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String str(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * The three row shapes V042 leaves behind: a reclassified stub run, an honest HIT, and a row
     * that never screened. Seeded BEFORE V045 so the "nothing historical changes" assertion has
     * something to be about.
     */
    private static void seedPostV042Rows(Connection c) throws SQLException {
        exec(c, """
                INSERT INTO partners (id, partner_id, type, settlement_currency)
                VALUES (911, 'P_RECLASSIFIED', 'OVERSEAS', 'USD'),
                       (912, 'P_HIT',          'OVERSEAS', 'USD'),
                       (913, 'P_NEVER',        'LOCAL',    'KRW')
                """);
        exec(c, """
                INSERT INTO partner_kyb
                    (id, partner_id, risk_rating, screening_status, screening_provider_id,
                     screening_authoritative, screening_caveat, screening_provider_ref,
                     screened_at, valid_from, recorded_at)
                VALUES
                    (9101, 911, 'MEDIUM', 'NOT_SCREENED_NO_PROVIDER', 'stub', FALSE,
                     'NOT A SANCTIONS SCREENING: stub', 'stub-aaaabbbbcccc',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (9102, 912, 'HIGH', 'HIT', 'stub', FALSE,
                     'NOT A SANCTIONS SCREENING: stub', 'stub-ddddeeeeffff',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (9103, 913, 'LOW', NULL, NULL, NULL, NULL, NULL,
                     NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
    }

    private static String insertManual(long id, long partnerId, String status, String providerId,
                                       String authoritative, String attester, String attestedAt,
                                       String sopRef, String sopVersion, String sources) {
        return """
                INSERT INTO partner_kyb
                    (id, partner_id, screening_status, screening_provider_id,
                     screening_authoritative, manual_attester_actor_id, manual_attested_at,
                     manual_sop_document_ref, manual_sop_version, manual_sources_consulted,
                     valid_from, recorded_at)
                VALUES (%d, %d, %s, %s, %s, %s, %s, %s, %s, %s,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(id, partnerId, sql(status), sql(providerId), authoritative,
                sql(attester), attestedAt, sql(sopRef), sql(sopVersion), sql(sources));
    }

    private static String sql(String s) {
        return s == null ? "NULL" : "'" + s.replace("'", "''") + "'";
    }

    @Test
    @DisplayName("V045 adds the manual authority without relaxing any V042 guarantee")
    void manualAuthorityIsEvidenceBound() throws Exception {
        String url = freshUrl();
        migrateTo(url, "44");
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            seedPostV042Rows(c);
        }
        migrateTo(url, "45");

        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            // ---- nothing historical was reclassified -------------------------------------
            assertEquals("NOT_SCREENED_NO_PROVIDER",
                    str(c, "SELECT screening_status FROM partner_kyb WHERE id = 9101"));
            assertEquals("HIT", str(c, "SELECT screening_status FROM partner_kyb WHERE id = 9102"));
            assertNull(str(c, "SELECT screening_status FROM partner_kyb WHERE id = 9103"));
            assertEquals(3, count(c, "SELECT COUNT(*) FROM partner_kyb WHERE id >= 9101"));
            assertEquals(0, count(c, """
                    SELECT COUNT(*) FROM partner_kyb
                     WHERE id >= 9101 AND manual_attester_actor_id IS NOT NULL
                    """));

            // ---- the V042 CHECK is untouched: a bare CLEAR with no authority is refused --
            assertThrows(SQLException.class, () -> exec(c, """
                    INSERT INTO partner_kyb (id, partner_id, screening_status, valid_from, recorded_at)
                    VALUES (9110, 911, 'CLEAR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """), "ck_partner_kyb_clear_requires_authority must still reject an"
                    + " unattributed CLEAR");

            // ---- a COMPLETE attestation is storable -------------------------------------
            exec(c, insertManual(9111, 911, "CLEAR_MANUAL_ATTESTATION", "manual-sop", "TRUE",
                    ATTESTER, "CURRENT_TIMESTAMP", SOP_REF, SOP_VERSION, SOURCES));
            assertEquals(ATTESTER,
                    str(c, "SELECT manual_attester_actor_id FROM partner_kyb WHERE id = 9111"));
            assertEquals(SOP_VERSION,
                    str(c, "SELECT manual_sop_version FROM partner_kyb WHERE id = 9111"));

            // ---- an INCOMPLETE one is not, whichever field is missing --------------------
            assertThrows(SQLException.class, () -> exec(c, insertManual(9112, 911,
                    "CLEAR_MANUAL_ATTESTATION", "manual-sop", "TRUE",
                    null, "CURRENT_TIMESTAMP", SOP_REF, SOP_VERSION, SOURCES)),
                    "a manual clearance naming no attester must be refused");
            assertThrows(SQLException.class, () -> exec(c, insertManual(9113, 911,
                    "CLEAR_MANUAL_ATTESTATION", "manual-sop", "TRUE",
                    ATTESTER, "NULL", SOP_REF, SOP_VERSION, SOURCES)),
                    "a manual clearance with no attestation instant must be refused");
            assertThrows(SQLException.class, () -> exec(c, insertManual(9114, 911,
                    "CLEAR_MANUAL_ATTESTATION", "manual-sop", "TRUE",
                    ATTESTER, "CURRENT_TIMESTAMP", null, SOP_VERSION, SOURCES)),
                    "a manual clearance with no SOP document reference must be refused");
            assertThrows(SQLException.class, () -> exec(c, insertManual(9115, 911,
                    "CLEAR_MANUAL_ATTESTATION", "manual-sop", "TRUE",
                    ATTESTER, "CURRENT_TIMESTAMP", SOP_REF, null, SOURCES)),
                    "a manual clearance with no SOP version must be refused");
            assertThrows(SQLException.class, () -> exec(c, insertManual(9116, 911,
                    "CLEAR_MANUAL_ATTESTATION", "manual-sop", "TRUE",
                    ATTESTER, "CURRENT_TIMESTAMP", SOP_REF, SOP_VERSION, null)),
                    "a manual clearance not stating what was consulted must be refused");

            // ---- and it cannot claim the manual status with no manual provenance ---------
            assertThrows(SQLException.class, () -> exec(c, insertManual(9117, 911,
                    "CLEAR_MANUAL_ATTESTATION", "stub", "FALSE",
                    ATTESTER, "CURRENT_TIMESTAMP", SOP_REF, SOP_VERSION, SOURCES)),
                    "the manual status must not be reachable with a non-manual provider");
            assertThrows(SQLException.class, () -> exec(c, insertManual(9118, 911,
                    "CLEAR_MANUAL_ATTESTATION", "manual-sop", "FALSE",
                    ATTESTER, "CURRENT_TIMESTAMP", SOP_REF, SOP_VERSION, SOURCES)),
                    "a manual clearance must be authoritative");

            // ---- attestation columns cannot decorate a machine run ----------------------
            assertThrows(SQLException.class, () -> exec(c, insertManual(9119, 911,
                    "NOT_SCREENED_NO_PROVIDER", "stub", "FALSE",
                    ATTESTER, "CURRENT_TIMESTAMP", SOP_REF, SOP_VERSION, SOURCES)),
                    "a stub run must not be able to carry a human's attestation");

            // ---- a manual HIT is legal, fully attested, and still not 'clear' ------------
            exec(c, insertManual(9120, 912, "HIT", "manual-sop", "TRUE",
                    ATTESTER, "CURRENT_TIMESTAMP", SOP_REF, SOP_VERSION, SOURCES));
            assertEquals("HIT", str(c, "SELECT screening_status FROM partner_kyb WHERE id = 9120"));

            // ---- a manual run may not be HALF attested either ---------------------------
            assertThrows(SQLException.class, () -> exec(c, insertManual(9121, 912,
                    "HIT", "manual-sop", "TRUE",
                    ATTESTER, "CURRENT_TIMESTAMP", SOP_REF, null, SOURCES)),
                    "a manual HIT must carry the whole attestation too");

            // ---- the pre-V045 roster still holds; nothing else was let in ---------------
            assertThrows(SQLException.class, () -> exec(c, insertManual(9122, 913,
                    "MANUALLY_CLEARED", "manual-sop", "TRUE",
                    ATTESTER, "CURRENT_TIMESTAMP", SOP_REF, SOP_VERSION, SOURCES)),
                    "an invented status value must be refused by the roster CHECK");
        }
    }
}
