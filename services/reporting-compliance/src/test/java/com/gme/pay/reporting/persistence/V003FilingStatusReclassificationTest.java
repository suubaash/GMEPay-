package com.gme.pay.reporting.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration test for {@code V003__filing_status_honesty.sql} (GAP T5-2).
 *
 * <p>Runs V001+V002, seeds the exact rows the fabricating stub clients used to produce
 * (status {@code SUBMITTED}/{@code CONFIRMED} with a {@code STUB-...} receipt id), then runs
 * V003 and asserts the rows are <b>reclassified, not deleted</b>: honest status, reason
 * recorded, prior value and discarded fabricated values preserved in the audit columns, and
 * the new CHECK constraint refusing the old vocabulary.
 */
class V003FilingStatusReclassificationTest {

    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private static String freshUrl() {
        return "jdbc:h2:mem:v003_" + UUID.randomUUID().toString().replace("-", "")
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

    private static int count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Seeds the three historical shapes: fabricated SUBMITTED, fabricated CONFIRMED, honest GENERATED. */
    private static void seedLegacyRows(Connection c) throws SQLException {
        exec(c, """
                INSERT INTO report_filing
                    (id, lane, report_type, report_date, record_count, submission_status,
                     file_path, external_receipt_id, generated_at, submitted_at)
                VALUES
                    (1, 'HOMETAX', 'ETAX',   DATE '2026-05-31', 1, 'SUBMITTED',
                     '/out/etax.xml', 'STUB-INV-1000', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (2, 'KOFIU',   'CTR',    DATE '2026-05-30', 4, 'CONFIRMED',
                     '/out/kofiu.dat', 'STUB-3f0c9a1e', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (3, 'BOK',     'FX1015', DATE '2026-05-30', 7, 'GENERATED',
                     '/out/fx1015.csv', NULL, CURRENT_TIMESTAMP, NULL)
                """);
        exec(c, """
                INSERT INTO bok_report_record
                    (id, filing_id, txn_id, txn_ref, report_type, report_date, partner_id,
                     submission_status, submitted_at)
                VALUES
                    (1, 3, 9001, 'TXN-9001', 'FX1015', DATE '2026-05-30', 42,
                     'SUBMITTED', CURRENT_TIMESTAMP),
                    (2, 3, 9002, 'TXN-9002', 'FX1015', DATE '2026-05-30', 42,
                     'PENDING', NULL)
                """);
    }

    @Test
    @DisplayName("V003 reclassifies fabricated SUBMITTED/CONFIRMED to NOT_FILED_CHANNEL_UNAVAILABLE")
    void v003_reclassifiesFabricatedAcceptances() throws Exception {
        String url = freshUrl();
        migrateTo(url, "2");

        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            seedLegacyRows(c);
            assertEquals(3, count(c, "SELECT COUNT(*) FROM report_filing"));

            migrateTo(url, "3");

            // --- history preserved: still three rows, nothing deleted ---
            assertEquals(3, count(c, "SELECT COUNT(*) FROM report_filing"),
                    "reclassification must not delete history");

            // --- the two fabricated rows are now honest ---
            assertEquals(0, count(c,
                    "SELECT COUNT(*) FROM report_filing "
                            + "WHERE submission_status IN ('SUBMITTED','CONFIRMED','ACCEPTED')"),
                    "no fabricated acceptance may survive");
            assertEquals(2, count(c,
                    "SELECT COUNT(*) FROM report_filing "
                            + "WHERE submission_status = 'NOT_FILED_CHANNEL_UNAVAILABLE'"));

            // --- the Hometax row: fabricated values moved to the audit trail ---
            assertEquals("NOT_FILED_CHANNEL_UNAVAILABLE",
                    str(c, "SELECT submission_status FROM report_filing WHERE id = 1"));
            assertEquals("SUBMITTED",
                    str(c, "SELECT reclassified_from FROM report_filing WHERE id = 1"),
                    "the prior status must be preserved, not lost");
            String note = str(c, "SELECT reclassification_note FROM report_filing WHERE id = 1");
            assertNotNull(note);
            assertTrue(note.contains("STUB-INV-1000"),
                    "the discarded fabricated receipt id must be preserved in the note: " + note);
            assertTrue(note.contains("HOMETAX"), "the note must identify the lane: " + note);
            assertNull(str(c, "SELECT external_receipt_id FROM report_filing WHERE id = 1"),
                    "external_receipt_id may only hold an authority-issued receipt");
            assertNull(str(c, "SELECT CAST(submitted_at AS VARCHAR) FROM report_filing WHERE id = 1"),
                    "submitted_at must be cleared — nothing was ever submitted");
            assertNotNull(str(c, "SELECT channel_unavailable_reason FROM report_filing WHERE id = 1"),
                    "the row must state why it is not filed");

            // --- KoFIU row reclassified from CONFIRMED ---
            assertEquals("CONFIRMED",
                    str(c, "SELECT reclassified_from FROM report_filing WHERE id = 2"));

            // --- real capability untouched: counts + artifact paths survive ---
            assertEquals("1", str(c, "SELECT CAST(record_count AS VARCHAR) FROM report_filing WHERE id = 1"));
            assertEquals("/out/kofiu.dat", str(c, "SELECT file_path FROM report_filing WHERE id = 2"));

            // --- the honest GENERATED row is left completely alone ---
            assertEquals("GENERATED",
                    str(c, "SELECT submission_status FROM report_filing WHERE id = 3"));
            assertNull(str(c, "SELECT reclassified_from FROM report_filing WHERE id = 3"),
                    "a row that never claimed acceptance must not be touched");
            assertEquals("7", str(c, "SELECT CAST(record_count AS VARCHAR) FROM report_filing WHERE id = 3"));
        }
    }

    @Test
    @DisplayName("V003 reclassifies bok_report_record rows and leaves PENDING alone")
    void v003_reclassifiesBokRecordRows() throws Exception {
        String url = freshUrl();
        migrateTo(url, "2");

        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            seedLegacyRows(c);
            migrateTo(url, "3");

            assertEquals(2, count(c, "SELECT COUNT(*) FROM bok_report_record"),
                    "no per-transaction row may be deleted");
            assertEquals("NOT_FILED_CHANNEL_UNAVAILABLE",
                    str(c, "SELECT submission_status FROM bok_report_record WHERE id = 1"));
            assertNull(str(c, "SELECT CAST(submitted_at AS VARCHAR) FROM bok_report_record WHERE id = 1"));
            assertEquals("PENDING",
                    str(c, "SELECT submission_status FROM bok_report_record WHERE id = 2"),
                    "PENDING never claimed acceptance and must be left as-is");
        }
    }

    @Test
    @DisplayName("after V003 the database itself rejects the fabricated vocabulary")
    void v003_checkConstraintRejectsOldStatuses() throws Exception {
        String url = freshUrl();
        migrateTo(url, "3");

        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            for (String forbidden : new String[] {"SUBMITTED", "CONFIRMED", "ACCEPTED", "FILED"}) {
                assertThrows(SQLException.class, () -> exec(c, """
                        INSERT INTO report_filing
                            (lane, report_type, report_date, record_count, submission_status)
                        VALUES ('HOMETAX', 'ETAX', DATE '2026-06-30', 1, '%s')
                        """.formatted(forbidden)),
                        "the CHECK constraint must reject status '" + forbidden + "'");
            }

            // The honest vocabulary is accepted.
            exec(c, """
                    INSERT INTO report_filing
                        (lane, report_type, report_date, record_count, submission_status)
                    VALUES ('HOMETAX', 'ETAX', DATE '2026-07-31', 1, 'NOT_FILED_CHANNEL_UNAVAILABLE')
                    """);
            assertEquals(1, count(c,
                    "SELECT COUNT(*) FROM report_filing "
                            + "WHERE submission_status = 'NOT_FILED_CHANNEL_UNAVAILABLE'"));
        }
    }
}
