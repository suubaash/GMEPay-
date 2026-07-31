package com.gme.pay.kybadapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Migration test for {@code V002__screening_provenance.sql} (gap T1-4).
 *
 * <p>Runs V001 only, seeds the exact rows the stub used to produce — a
 * {@code CLEAR}/{@code PASS} run keyed by a {@code stub-} provider ref, i.e. a
 * completed-looking KYB check that consulted no screening source — then runs V002
 * and asserts the rows are <b>reclassified, not deleted</b>: honest status,
 * provenance recorded, and the prior claim preserved in the audit columns.
 *
 * <p>Mirrors reporting-compliance's {@code V003FilingStatusReclassificationTest}
 * (T5-2), the same "the not-really-done state cannot masquerade as done" shape.
 */
class V002ScreeningProvenanceMigrationTest {

    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private static String freshUrl() {
        return "jdbc:h2:mem:kybv002_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }

    private static void migrateTo(String url, String target) {
        Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations("classpath:db/migration")
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

    private static boolean bool(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * The three historical shapes: (1) the dangerous one — a stub CLEAR collapsed
     * to PASS; (2) a stub HIT, which was always honest and must be left alone;
     * (3) a run whose provider ref reveals nothing.
     */
    private static void seedLegacyRows(Connection c) throws SQLException {
        exec(c, """
                INSERT INTO kyb_screening
                    (id, provider_ref, partner_code, screening_status, biz_reg_status, biz_reg_ref,
                     documents_complete, decision, decision_reason, hit_count, screened_at, created_at)
                VALUES
                    (1, 'stub-aaaabbbbcccc', 'P_LOOKED_DONE', 'CLEAR', 'VERIFIED', 'stub-bizreg-1',
                     TRUE, 'PASS', 'screening clear, registration verified, documents complete', 0,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (2, 'stub-ddddeeeeffff', 'P_BLOCKED', 'HIT', 'VERIFIED', 'stub-bizreg-2',
                     TRUE, 'FAIL', 'sanctions/watchlist HIT on a screened name', 1,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (3, 'mystery-ref-0001', 'P_UNKNOWN_SRC', 'CLEAR', 'VERIFIED', NULL,
                     TRUE, 'PASS', 'screening clear, registration verified, documents complete', 0,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
    }

    @Test
    @DisplayName("V002 reclassifies a stub CLEAR/PASS row, keeps the audit trail, and leaves honest rows alone")
    void reclassifiesStubDerivedRows() throws Exception {
        String url = freshUrl();
        migrateTo(url, "1");
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            seedLegacyRows(c);
        }
        migrateTo(url, "2");

        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            // Nothing was deleted.
            assertEquals(3, count(c, "SELECT COUNT(*) FROM kyb_screening"));

            // (1) The row that looked like a completed check.
            assertEquals("NOT_SCREENED_NO_PROVIDER",
                    str(c, "SELECT screening_status FROM kyb_screening WHERE id = 1"));
            assertEquals("MANUAL_REVIEW",
                    str(c, "SELECT decision FROM kyb_screening WHERE id = 1"));
            assertEquals("stub",
                    str(c, "SELECT screening_provider_id FROM kyb_screening WHERE id = 1"));
            assertFalse(bool(c, "SELECT screening_authoritative FROM kyb_screening WHERE id = 1"));
            assertTrue(str(c, "SELECT screening_caveat FROM kyb_screening WHERE id = 1")
                    .contains("NOT A SANCTIONS SCREENING"));
            // The prior claim is preserved, not erased.
            assertEquals("CLEAR",
                    str(c, "SELECT reclassified_from FROM kyb_screening WHERE id = 1"));
            String note = str(c, "SELECT reclassification_note FROM kyb_screening WHERE id = 1");
            assertNotNull(note);
            assertTrue(note.contains("no screening happened"), note);
            assertTrue(note.contains("MANUAL_REVIEW"), note);
            assertTrue(str(c, "SELECT decision_reason FROM kyb_screening WHERE id = 1")
                    .contains("no authoritative sanctions screening"));

            // (2) The HIT row was already honest: verdict untouched, provenance added.
            assertEquals("HIT", str(c, "SELECT screening_status FROM kyb_screening WHERE id = 2"));
            assertEquals("FAIL", str(c, "SELECT decision FROM kyb_screening WHERE id = 2"));
            assertEquals("stub",
                    str(c, "SELECT screening_provider_id FROM kyb_screening WHERE id = 2"));
            assertFalse(bool(c, "SELECT screening_authoritative FROM kyb_screening WHERE id = 2"));
            assertNull(str(c, "SELECT reclassified_from FROM kyb_screening WHERE id = 2"));

            // (3) An undeclared producer is 'unknown' and is reclassified the same way —
            // absence of provenance is never treated as authority.
            assertEquals("unknown",
                    str(c, "SELECT screening_provider_id FROM kyb_screening WHERE id = 3"));
            assertEquals("NOT_SCREENED_NO_PROVIDER",
                    str(c, "SELECT screening_status FROM kyb_screening WHERE id = 3"));
            assertEquals("MANUAL_REVIEW",
                    str(c, "SELECT decision FROM kyb_screening WHERE id = 3"));
            assertTrue(str(c, "SELECT screening_caveat FROM kyb_screening WHERE id = 3")
                    .contains("PROVENANCE ABSENT"));

            // No row anywhere still claims a screening that never happened.
            assertEquals(0, count(c, """
                    SELECT COUNT(*) FROM kyb_screening
                     WHERE screening_authoritative = FALSE
                       AND (screening_status = 'CLEAR' OR decision = 'PASS')
                    """));

            // The widened column can hold the new value (VARCHAR(16) could not).
            assertEquals(1, count(c, """
                    SELECT COUNT(*) FROM kyb_screening
                     WHERE screening_status = 'NOT_SCREENED_NO_PROVIDER' AND id = 1
                    """));
        }
    }
}
