package com.gme.pay.bff;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sanity check that the Spring context boots with every {@code Stub*Client} bean
 * wired as the {@link com.gme.pay.bff.client} implementation. This is the
 * minimum smoke test that proves the package layout, configuration and bean
 * graph hang together without booting any backend services.
 *
 * <p>Uses {@code webEnvironment = NONE} so this test does not bind to
 * {@code server.port=8095}, avoiding collisions when other test JVMs in the
 * monorepo run in parallel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BffApplicationTest {

    @Autowired
    ConfigRegistryClient configRegistry;

    @Autowired
    TransactionMgmtClient transactions;

    @Autowired
    PrefundingClient prefunding;

    @Autowired
    RevenueLedgerClient revenue;

    @Autowired
    SettlementClient settlement;

    @Test
    @DisplayName("Spring context loads with the five stub clients wired by interface")
    void contextLoads() {
        assertNotNull(configRegistry, "ConfigRegistryClient bean missing");
        assertNotNull(transactions, "TransactionMgmtClient bean missing");
        assertNotNull(prefunding, "PrefundingClient bean missing");
        assertNotNull(revenue, "RevenueLedgerClient bean missing");
        assertNotNull(settlement, "SettlementClient bean missing");
    }
}
