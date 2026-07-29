package com.gme.pay.registry.kyb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Migration test for {@code V042__partner_kyb_screening_provenance.sql} (gap T1-4).
 *
 * <p>Migrates the real chain up to V041, seeds the row shape the in-process stub
 * used to write — {@code screening_status = 'CLEAR'} with a {@code stub-} provider
 * ref, which the Slice 8 activation gate accepted as a passed sanctions check —
 * then runs V042 and asserts the row is <b>reclassified, not deleted</b>, that the
 * prior claim is preserved, and that the new CHECK makes an unattributed CLEAR
 * unstorable from that point on.
 *
 * <p>Mirrors reporting-compliance's {@code V003FilingStatusReclassificationTest}
 * (T5-2). Flyway locations match the service's own
 * ({@code db/migration} + {@code db/vendor/{vendor}}) so the H2 variants of V004 /
 * V023 apply exactly as they do in the JPA slices.
 */
class V042ScreeningProvenanceMigrationTest {

    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private static String freshUrl() {
        return "jdbc:h2:mem:cfgv042_" + UUID.randomUUID().toString().replace("-", "")
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

    private static Boolean bool(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            boolean v = rs.getBoolean(1);
            return rs.wasNull() ? null : v;
        }
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Three historical rows: (1) the stub CLEAR + APPROVED that activation
     * accepted; (2) an honest stub HIT; (3) a row that never screened at all.
     * Parent {@code partners} rows are seeded first — a later migration added the
     * {@code fk_partner_kyb_partner} FK.
     */
    private static void seedLegacyRows(Connection c) throws SQLException {
        exec(c, """
                INSERT INTO partners (id, partner_id, type, settlement_currency)
                VALUES (901, 'P_LOOKED_DONE', 'OVERSEAS', 'USD'),
                       (902, 'P_BLOCKED',     'OVERSEAS', 'USD'),
                       (903, 'P_NO_SCREEN',   'LOCAL',    'KRW')
                """);
        exec(c, """
                INSERT INTO partner_kyb
                    (id, partner_id, risk_rating, risk_rationale, screening_status,
                     screening_provider_ref, screened_at, verification_decision,
                     verification_decision_reason, valid_from, recorded_at)
                VALUES
                    (9001, 901, 'MEDIUM', NULL, 'CLEAR', 'stub-aaaabbbbcccc',
                     CURRENT_TIMESTAMP, 'APPROVED', 'sanctions screening clear (stub)',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (9002, 902, 'HIGH', 'EDD on file', 'HIT', 'stub-ddddeeeeffff',
                     CURRENT_TIMESTAMP, 'MANUAL_REVIEW', 'sanctions screening HIT',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (9003, 903, 'LOW', NULL, NULL, NULL,
                     NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
    }

    @Test
    @DisplayName("V042 reclassifies the stub CLEAR/APPROVED row, keeps the audit trail, spares honest rows")
    void reclassifiesStubDerivedRows() throws Exception {
        String url = freshUrl();
        migrateTo(url, "41");
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            seedLegacyRows(c);
        }
        migrateTo(url, "42");

        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            assertEquals(3, count(c, "SELECT COUNT(*) FROM partner_kyb WHERE id >= 9001"));

            // (1) The row the activation gate used to accept as a passed screening.
            assertEquals("NOT_SCREENED_NO_PROVIDER",
                    str(c, "SELECT screening_status FROM partner_kyb WHERE id = 9001"));
            assertEquals("stub",
                    str(c, "SELECT screening_provider_id FROM partner_kyb WHERE id = 9001"));
            assertFalse(bool(c, "SELECT screening_authoritative FROM partner_kyb WHERE id = 9001"));
            assertTrue(str(c, "SELECT screening_caveat FROM partner_kyb WHERE id = 9001")
                    .contains("NOT A SANCTIONS SCREENING"));
            assertEquals("MANUAL_REVIEW",
                    str(c, "SELECT verification_decision FROM partner_kyb WHERE id = 9001"));
            // The prior claim survives as evidence.
            assertEquals("CLEAR",
                    str(c, "SELECT reclassified_from FROM partner_kyb WHERE id = 9001"));
            String note = str(c, "SELECT reclassification_note FROM partner_kyb WHERE id = 9001");
            assertNotNull(note);
            assertTrue(note.contains("no screening"), note);
            assertTrue(note.contains("APPROVED"), note);
            assertTrue(str(c, "SELECT verification_decision_reason FROM partner_kyb WHERE id = 9001")
                    .contains("no authoritative sanctions screening"));

            // (2) An honest HIT keeps its verdict; only provenance is added.
            assertEquals("HIT", str(c, "SELECT screening_status FROM partner_kyb WHERE id = 9002"));
            assertEquals("MANUAL_REVIEW",
                    str(c, "SELECT verification_decision FROM partner_kyb WHERE id = 9002"));
            assertEquals("stub",
                    str(c, "SELECT screening_provider_id FROM partner_kyb WHERE id = 9002"));
            assertNull(str(c, "SELECT reclassified_from FROM partner_kyb WHERE id = 9002"));

            // (3) A row that never screened gets no invented provenance.
            assertNull(str(c, "SELECT screening_status FROM partner_kyb WHERE id = 9003"));
            assertNull(str(c, "SELECT screening_provider_id FROM partner_kyb WHERE id = 9003"));
            assertNull(bool(c, "SELECT screening_authoritative FROM partner_kyb WHERE id = 9003"));

            // No stored CLEAR anywhere lacks an authority.
            assertEquals(0, count(c, """
                    SELECT COUNT(*) FROM partner_kyb
                     WHERE screening_status = 'CLEAR'
                       AND COALESCE(screening_authoritative, FALSE) <> TRUE
                    """));

            // …and from now on the database refuses to accept one.
            assertThrows(SQLException.class, () -> exec(c, """
                    INSERT INTO partner_kyb (id, partner_id, screening_status, valid_from, recorded_at)
                    VALUES (9004, 901, 'CLEAR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """), "ck_partner_kyb_clear_requires_authority must reject an unattributed CLEAR");

            // The widened column + new roster value are storable.
            exec(c, """
                    INSERT INTO partner_kyb (id, partner_id, screening_status,
                                             screening_provider_id, screening_authoritative,
                                             valid_from, recorded_at)
                    VALUES (9005, 901, 'NOT_SCREENED_NO_PROVIDER', 'stub', FALSE,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            assertEquals(1, count(c,
                    "SELECT COUNT(*) FROM partner_kyb WHERE id = 9005"));
        }
    }
}
