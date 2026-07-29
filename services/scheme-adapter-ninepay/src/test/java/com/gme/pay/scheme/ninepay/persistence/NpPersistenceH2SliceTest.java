package com.gme.pay.scheme.ninepay.persistence;

import com.gme.pay.scheme.ninepay.status.PayoutStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence contract for {@code np_payouts} + {@code np_ipn_events} (V001): JPA mapping,
 * the UNIQUE {@code request_id} idempotency guard, and the amount/status CHECK constraints.
 * H2 in PostgreSQL mode, no Docker — same approach as the ZeroPay adapter slices.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NpPersistenceH2SliceTest {

    @Autowired
    private NpPayoutRepository payouts;

    @Autowired
    private NpIpnEventRepository ipnEvents;

    @Autowired
    private TestEntityManager entityManager;

    private static NpPayoutEntity payout(String requestId) {
        return new NpPayoutEntity(requestId, "970418", "1023020330000", 0,
                "NGUYEN VAN A", 50_000L, "GME PAYOUT 1", "GMEPAYSENDER1");
    }

    @Test
    @DisplayName("payout round-trips fields, defaults to SUBMITTED, stamps created/updated")
    void payout_roundTrip() {
        Long id = payouts.saveAndFlush(payout("GMEPAY9P202607270000000001")).getId();
        entityManager.clear();

        NpPayoutEntity loaded = payouts.findById(id).orElseThrow();
        assertEquals("GMEPAY9P202607270000000001", loaded.getRequestId());
        assertEquals(PayoutStatus.SUBMITTED, loaded.getStatus());
        assertEquals(0, loaded.getAmountVnd().compareTo(new BigDecimal("50000")));
        assertEquals("970418", loaded.getBankNo());
        assertEquals("GMEPAYSENDER1", loaded.getSenderUid());
        assertNull(loaded.getTransactionId());
        assertNull(loaded.getReversedAt());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
    }

    @Test
    @DisplayName("UNIQUE request_id: a duplicate insert is rejected by the schema (idempotency spine)")
    void payout_duplicateRequestIdRejected() {
        payouts.saveAndFlush(payout("GMEPAY9P202607270000000002"));

        assertThrows(DataIntegrityViolationException.class,
                () -> payouts.saveAndFlush(payout("GMEPAY9P202607270000000002")));
    }

    @Test
    @DisplayName("CHECK amount_vnd >= 2000: the 9Pay minimum is enforced at the schema too")
    void payout_minAmountCheckEnforced() {
        NpPayoutEntity tooSmall = new NpPayoutEntity("GMEPAY9P202607270000000003", "970418",
                "1023020330000", 0, "NGUYEN VAN A", 1_999L, "GME PAYOUT", null);

        assertThrows(DataIntegrityViolationException.class, () -> payouts.saveAndFlush(tooSmall));
    }

    @Test
    @DisplayName("IPN lifecycle: 000 SUCCESS then 009 reversal persists REVERSED + reversed_at")
    void payout_reversalLifecyclePersists() {
        NpPayoutEntity entity = payouts.saveAndFlush(payout("GMEPAY9P202607270000000004"));
        entity.recordSchemeAccept("9P123456", PayoutStatus.PENDING, 500L, 50_500L,
                "2026-07-27 10:00:00", null);
        entity.recordIpn("000", PayoutStatus.SUCCESS, "9P123456", 500L, 50_500L, "APPROVED");
        payouts.saveAndFlush(entity);
        entity.recordIpn("009", PayoutStatus.REVERSED, "9P123456", null, null, "REVERSED BY BANK");
        Long id = payouts.saveAndFlush(entity).getId();
        entityManager.clear();

        NpPayoutEntity loaded = payouts.findById(id).orElseThrow();
        assertEquals(PayoutStatus.REVERSED, loaded.getStatus());
        assertEquals("009", loaded.getLastIpnCode());
        assertNotNull(loaded.getReversedAt());
        assertEquals("9P123456", loaded.getTransactionId());
    }

    @Test
    @DisplayName("ipn events round-trip raw payload + signature flag and read back in receipt order")
    void ipnEvents_roundTrip() {
        ipnEvents.saveAndFlush(new NpIpnEventEntity("REQ-A", "9P1", "000", "SUCCESS",
                "{\"request_id\":\"REQ-A\",\"code\":\"000\"}", true));
        ipnEvents.saveAndFlush(new NpIpnEventEntity("REQ-A", "9P1", "009", "SUCCESS",
                "{\"request_id\":\"REQ-A\",\"code\":\"009\"}", false));
        entityManager.clear();

        List<NpIpnEventEntity> events = ipnEvents.findByRequestIdOrderByReceivedAtAsc("REQ-A");
        assertEquals(2, events.size());
        assertEquals("000", events.get(0).getCode());
        assertTrue(events.get(0).isSignatureValid());
        assertEquals("009", events.get(1).getCode());
        assertFalse(events.get(1).isSignatureValid());
        assertTrue(events.get(1).getRawPayload().contains("\"code\":\"009\""));
        assertNotNull(events.get(0).getReceivedAt());
    }

    // ------------------------------------------------------------------ T5-4 replay guard

    @Test
    @DisplayName("T5-4 (V002): event_key is UNIQUE, so the DB itself refuses a second applied event")
    void ipnEvents_eventKeyIsUnique() {
        NpIpnEventEntity first = new NpIpnEventEntity("REQ-R", "9P1", "009", "SUCCESS", "{}", true);
        first.markApplied("key-009-abc");
        first.setSchemeCreatedAt("2026-07-27 10:00:00");
        ipnEvents.saveAndFlush(first);

        // The replayed delivery of the SAME 9Pay event identity: the constraint is the backstop
        // behind the adapter's own check, so a concurrent redelivery cannot double-apply.
        NpIpnEventEntity replay = new NpIpnEventEntity("REQ-R", "9P1", "009", "SUCCESS", "{}", true);
        replay.markApplied("key-009-abc");
        assertThrows(DataIntegrityViolationException.class, () -> ipnEvents.saveAndFlush(replay));
    }

    @Test
    @DisplayName("T5-4 (V002): rejected/audited events keep a NULL event_key, so many can coexist")
    void ipnEvents_nullEventKeysAreNotConstrained() {
        NpIpnEventEntity badSig = new NpIpnEventEntity("REQ-N", "9P1", "000", "SUCCESS", "{}", false);
        badSig.markRejected(IpnRejectReason.SIGNATURE_INVALID);
        NpIpnEventEntity duplicate = new NpIpnEventEntity("REQ-N", "9P1", "000", "SUCCESS", "{}", true);
        duplicate.markRejected(IpnRejectReason.DUPLICATE);

        // Repeated NULLs are permitted under a UNIQUE constraint on both PostgreSQL and H2 —
        // that is the "unique only when applied" semantics the guard relies on, and it means a
        // rejected delivery never blocks the genuine one that follows.
        ipnEvents.saveAndFlush(badSig);
        ipnEvents.saveAndFlush(duplicate);
        entityManager.clear();

        List<NpIpnEventEntity> events = ipnEvents.findByRequestIdOrderByReceivedAtAsc("REQ-N");
        assertEquals(2, events.size());
        assertTrue(events.stream().noneMatch(NpIpnEventEntity::isApplied));
        assertTrue(events.stream().allMatch(e -> e.getEventKey() == null));
        assertEquals(IpnRejectReason.SIGNATURE_INVALID.name(), events.get(0).getRejectReason());
        assertEquals(IpnRejectReason.DUPLICATE.name(), events.get(1).getRejectReason());
    }

    @Test
    @DisplayName("T5-4 (V002): the applied-events lookup feeding the staleness rule")
    void ipnEvents_appliedLookup() {
        NpIpnEventEntity applied = new NpIpnEventEntity("REQ-S", "9P1", "000", "SUCCESS", "{}", true);
        applied.markApplied("key-000-s");
        applied.setSchemeCreatedAt("2026-07-27 10:00:00");
        ipnEvents.saveAndFlush(applied);
        NpIpnEventEntity ignored = new NpIpnEventEntity("REQ-S", "9P1", "004", "PENDING", "{}", true);
        ignored.markRejected(IpnRejectReason.STATUS_REGRESSION);
        ipnEvents.saveAndFlush(ignored);
        entityManager.clear();

        assertTrue(ipnEvents.findByEventKey("key-000-s").isPresent());
        assertTrue(ipnEvents.findByEventKey("key-does-not-exist").isEmpty());
        List<NpIpnEventEntity> appliedOnly =
                ipnEvents.findByRequestIdAndAppliedTrueOrderByReceivedAtDesc("REQ-S");
        assertEquals(1, appliedOnly.size());
        assertEquals("000", appliedOnly.get(0).getCode());
        assertEquals("2026-07-27 10:00:00", appliedOnly.get(0).getSchemeCreatedAt());
    }

    @Test
    @DisplayName("findByStatusIn surfaces the non-final rows a reconciliation sweep would poll")
    void payout_findByStatusIn() {
        NpPayoutEntity unknown = payout("GMEPAY9P202607270000000005");
        unknown.recordStatus(PayoutStatus.UNKNOWN);
        payouts.saveAndFlush(unknown);
        NpPayoutEntity success = payout("GMEPAY9P202607270000000006");
        success.recordSchemeAccept("9P2", PayoutStatus.SUCCESS, null, null, null, null);
        payouts.saveAndFlush(success);

        List<NpPayoutEntity> polling = payouts.findByStatusIn(
                List.of(PayoutStatus.SUBMITTED, PayoutStatus.PENDING, PayoutStatus.PROCESSING,
                        PayoutStatus.UNKNOWN));
        assertTrue(polling.stream().anyMatch(p -> p.getRequestId().endsWith("0005")));
        assertTrue(polling.stream().noneMatch(p -> p.getRequestId().endsWith("0006")));
    }
}
