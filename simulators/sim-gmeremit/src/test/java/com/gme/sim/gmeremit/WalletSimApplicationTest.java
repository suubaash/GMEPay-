package com.gme.sim.gmeremit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the Spring context loads cleanly (no wiring errors).
 * Hub calls are never made; RestClient just gets built with a dummy URL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gmepay.sim.gmeremit.gmepay-base-url=http://localhost:9999"
})
class WalletSimApplicationTest {

    @Test
    void contextLoads() {
        // passes if Spring context boots without errors
    }
}
