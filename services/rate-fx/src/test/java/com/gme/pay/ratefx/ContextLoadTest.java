package com.gme.pay.ratefx;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.ratefx.partnerb.PartnerBQuotePort;
import com.gme.pay.ratefx.partnerb.SnapshotPartnerBQuotePort;
import com.gme.pay.ratefx.quote.JpaQuoteTtlStore;
import com.gme.pay.ratefx.quote.QuoteTtlStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the application context boots on the default profile (H2 + Flyway, no Redis) and wires
 * the new WBS-4.6 / durable-TTL beans. Confirms that without {@code spring.data.redis.host} the
 * restart-safe DB-backed {@link JpaQuoteTtlStore} is selected (not the process-local fallback), and
 * the in-process {@link SnapshotPartnerBQuotePort} backs the PARTNER source.
 */
@SpringBootTest
class ContextLoadTest {

    @Autowired
    private QuoteTtlStore quoteTtlStore;
    @Autowired
    private PartnerBQuotePort partnerBQuotePort;

    @Test
    void wiresDurableTtlStoreAndPartnerBPort() {
        assertThat(quoteTtlStore).isInstanceOf(JpaQuoteTtlStore.class);
        assertThat(partnerBQuotePort).isInstanceOf(SnapshotPartnerBQuotePort.class);
    }
}
