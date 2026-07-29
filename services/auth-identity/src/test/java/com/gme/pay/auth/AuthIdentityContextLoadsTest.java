package com.gme.pay.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full auth-identity application context (H2 + Flyway, no web server). Guards the
 * service's bean graph against wiring regressions that slice/standalone tests can't catch —
 * e.g. an ambiguous multi-constructor {@code @Service} that only fails when Spring autowires it
 * in a real context (which is how {@link com.gme.pay.auth.rbac.RbacResolutionService}'s missing
 * {@code @Autowired} surfaced).
 *
 * <p>The {@code test} profile supplies the internal-auth fixture secret: since T0-2 the service
 * refuses to start without one (see
 * {@link com.gme.pay.auth.config.InternalAuthEnforcedConfig}), so booting at all is itself part of
 * what this test now proves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AuthIdentityContextLoadsTest {

    @Test
    void contextLoads() {
        // Success = every bean (incl. the RBAC resolution/admin services) instantiates and wires.
    }
}
