package com.gme.pay.txn.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.gme.pay.txn.domain.model.TransactionStatus;
import org.junit.jupiter.api.Test;

/** Unit test for the {@link PaymentApprovedEvent} contract (no Spring / broker). */
class PaymentApprovedEventTest {

    @Test
    void carriesTheWebhookContract() {
        PaymentApprovedEvent e = new PaymentApprovedEvent("TX-1", 700L, "PTX-1", TransactionStatus.APPROVED);

        assertEquals("payment.approved", e.eventType()); // drives topic gmepay.payment.approved
        assertEquals("TX-1", e.aggregateId());           // Kafka key + consumer aggregateId
        assertEquals(700L, e.partnerId());               // webhook endpoint resolution key
        assertEquals("PTX-1", e.partnerTxnRef());
        assertEquals(TransactionStatus.APPROVED, e.toStatus());
        assertNotNull(e.occurredAt());                   // defaulted by the convenience ctor
    }
}
