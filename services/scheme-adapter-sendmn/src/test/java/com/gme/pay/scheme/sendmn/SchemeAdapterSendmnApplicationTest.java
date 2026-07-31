package com.gme.pay.scheme.sendmn;

import com.gme.pay.scheme.sendmn.adapter.SendmnSchemeAdapter;
import com.gme.pay.scheme.sendmn.client.SendmnSchemeApiClient;
import com.gme.pay.scheme.sendmn.crypto.PlainJsonEnvelopeCodec;
import com.gme.pay.scheme.sendmn.crypto.SendmnEnvelopeCodec;
import com.gme.pay.scheme.sendmn.fx.FxRateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sanity check that the Spring context boots: bean graph (adapter → client → auth +
 * codec), Flyway V001 against the in-memory H2 datasource, and the default
 * {@code sendmn.envelope.mode=plain} codec selection.
 *
 * <p>Uses {@code webEnvironment = NONE} so this test does not bind to
 * {@code server.port=8093}, avoiding collisions when other test JVMs in the
 * monorepo run in parallel.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SchemeAdapterSendmnApplicationTest {

    @Autowired
    SendmnSchemeAdapter adapter;

    @Autowired
    SendmnSchemeApiClient client;

    @Autowired
    SendmnEnvelopeCodec codec;

    @Autowired
    FxRateService fxRates;

    @Test
    @DisplayName("Spring context loads; default envelope codec is the plain (dev) one")
    void contextLoads() {
        assertNotNull(adapter, "SendmnSchemeAdapter bean missing");
        assertNotNull(client, "SendmnSchemeApiClient bean missing");
        assertNotNull(fxRates, "FxRateService bean missing");
        assertInstanceOf(PlainJsonEnvelopeCodec.class, codec,
                "sendmn.envelope.mode should default to plain");
    }
}
