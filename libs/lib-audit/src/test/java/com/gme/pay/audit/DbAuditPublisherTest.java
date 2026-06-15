package com.gme.pay.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice tests for {@link DbAuditPublisher}.  Uses an in-memory H2 database (no Docker
 * needed).  Flyway runs the lib-audit migration before each test so the schema is fresh.
 */
class DbAuditPublisherTest {

    private static final Instant T0 = Instant.parse("2026-06-15T00:00:00Z");

    private JdbcDataSource dataSource;
    private DbAuditPublisher publisher;

    @BeforeEach
    void setUp() {
        // Fresh in-memory H2 database per test — each test gets a clean slate.
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:audit_test_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        // Run the lib-audit Flyway migration to create audit_log.
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/audit")
                .load();
        flyway.migrate();

        publisher = new DbAuditPublisher(dataSource);
    }

    // -------------------------------------------------------------------------
    // Basic persistence
    // -------------------------------------------------------------------------

    @Test
    void publishPersistsRowToAuditLog() throws Exception {
        AuditEvent event = AuditEvent.newEvent(
                "partner", "42", "alice", "10.0.0.1", "PARTNER_SAVED",
                bytes("{\"v\":0}"), bytes("{\"v\":1}"), HashChain.GENESIS, T0);
        publisher.publish(event);

        List<HashChain.AuditEvent> chain = publisher.loadChain("partner", "42");
        assertEquals(1, chain.size());
        HashChain.AuditEvent row = chain.get(0);
        assertEquals("PARTNER_SAVED", row.eventType());
        assertEquals("alice", row.actorId());
        assertArrayEquals(event.rowHash(), row.rowHash());
        assertArrayEquals(HashChain.GENESIS, row.prevHash());
    }

    @Test
    void publishPersistsMultipleRowsInOrder() {
        byte[] prev = HashChain.GENESIS;
        for (int i = 0; i < 5; i++) {
            AuditEvent e = AuditEvent.newEvent(
                    "partner", "10", "alice", null, "PARTNER_SAVED",
                    null, bytes("{\"i\":" + i + "}"), prev, T0.plusSeconds(i));
            publisher.publish(e);
            prev = e.rowHash();
        }

        List<HashChain.AuditEvent> chain = publisher.loadChain("partner", "10");
        assertEquals(5, chain.size());
    }

    // -------------------------------------------------------------------------
    // Hash chain integrity
    // -------------------------------------------------------------------------

    @Test
    void chainVerifiesAfterMultiplePublishes() {
        byte[] prev = HashChain.GENESIS;
        for (int i = 0; i < 4; i++) {
            AuditEvent e = AuditEvent.newEvent(
                    "partner", "99", "bob", null, "PARTNER_SAVED",
                    null, bytes("{\"n\":" + i + "}"), prev, T0.plusSeconds(i * 10));
            publisher.publish(e);
            prev = e.rowHash();
        }

        List<HashChain.AuditEvent> chain = publisher.loadChain("partner", "99");
        assertEquals(-1, HashChain.verify(chain),
                "Chain loaded from DB must verify clean (no tampering)");
    }

    @Test
    void eachRowPrevHashEqualsPredessorRowHash() {
        byte[] prev = HashChain.GENESIS;
        for (int i = 0; i < 3; i++) {
            AuditEvent e = AuditEvent.newEvent(
                    "partner", "7", "carol", null, "PARTNER_SAVED",
                    null, bytes("{\"x\":" + i + "}"), prev, T0.plusSeconds(i));
            publisher.publish(e);
            prev = e.rowHash();
        }

        List<HashChain.AuditEvent> chain = publisher.loadChain("partner", "7");
        assertArrayEquals(HashChain.GENESIS, chain.get(0).prevHash(),
                "First row must have GENESIS prevHash");
        assertArrayEquals(chain.get(0).rowHash(), chain.get(1).prevHash(),
                "Row 1 prevHash must equal row 0 rowHash");
        assertArrayEquals(chain.get(1).rowHash(), chain.get(2).prevHash(),
                "Row 2 prevHash must equal row 1 rowHash");
    }

    // -------------------------------------------------------------------------
    // Tamper detection
    // -------------------------------------------------------------------------

    @Test
    void tamperedRowDetectedByVerify() throws Exception {
        byte[] prev = HashChain.GENESIS;
        for (int i = 0; i < 3; i++) {
            AuditEvent e = AuditEvent.newEvent(
                    "partner", "55", "dave", null, "PARTNER_SAVED",
                    null, bytes("{\"v\":" + i + "}"), prev, T0.plusSeconds(i));
            publisher.publish(e);
            prev = e.rowHash();
        }

        // Load the chain and simulate a tamper: replace row 1's afterJsonb while
        // keeping its stored hashes intact.
        List<HashChain.AuditEvent> original = publisher.loadChain("partner", "55");
        var tampered = new java.util.ArrayList<>(original);
        DbAuditPublisher.ChainRow row1 = (DbAuditPublisher.ChainRow) original.get(1);
        tampered.set(1, new DbAuditPublisher.ChainRow(
                row1.eventType(),
                row1.actorId(),
                row1.recordedAt(),
                row1.beforeJsonb(),
                bytes("{\"silently\":\"rewritten\"}"), // tampered afterJsonb
                row1.prevHash(),
                row1.rowHash()));                       // stale stored hash

        int firstBad = HashChain.verify(tampered);
        assertEquals(1, firstBad,
                "Tamper of row 1's afterJsonb must be detected at index 1");
    }

    // -------------------------------------------------------------------------
    // latestRowHash
    // -------------------------------------------------------------------------

    @Test
    void latestRowHashReturnsGenesisWhenNoRows() {
        byte[] hash = publisher.latestRowHash("partner", "unknown-aggregate");
        assertArrayEquals(HashChain.GENESIS, hash);
    }

    @Test
    void latestRowHashReturnsLastPublishedRowHash() {
        byte[] prev = HashChain.GENESIS;
        AuditEvent e1 = AuditEvent.newEvent(
                "partner", "88", "eve", null, "PARTNER_SAVED",
                null, bytes("{}"), prev, T0);
        publisher.publish(e1);
        prev = e1.rowHash();

        AuditEvent e2 = AuditEvent.newEvent(
                "partner", "88", "eve", null, "PARTNER_SAVED",
                null, bytes("{\"v\":2}"), prev, T0.plusSeconds(1));
        publisher.publish(e2);

        byte[] latest = publisher.latestRowHash("partner", "88");
        assertArrayEquals(e2.rowHash(), latest,
                "latestRowHash must return the rowHash of the last published event");
    }

    // -------------------------------------------------------------------------
    // Isolation: different aggregates have independent chains
    // -------------------------------------------------------------------------

    @Test
    void separateAggregatesHaveIndependentChains() {
        AuditEvent a1 = AuditEvent.newEvent(
                "partner", "AGG-A", "alice", null, "PARTNER_SAVED",
                null, bytes("{\"a\":1}"), HashChain.GENESIS, T0);
        AuditEvent b1 = AuditEvent.newEvent(
                "partner", "AGG-B", "bob", null, "PARTNER_SAVED",
                null, bytes("{\"b\":1}"), HashChain.GENESIS, T0.plusSeconds(1));
        publisher.publish(a1);
        publisher.publish(b1);

        List<HashChain.AuditEvent> chainA = publisher.loadChain("partner", "AGG-A");
        List<HashChain.AuditEvent> chainB = publisher.loadChain("partner", "AGG-B");
        assertEquals(1, chainA.size());
        assertEquals(1, chainB.size());
        assertEquals(-1, HashChain.verify(chainA));
        assertEquals(-1, HashChain.verify(chainB));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
