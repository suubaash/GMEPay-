package com.gme.pay.registry.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.partner.PartnerSeeder;
import com.gme.pay.registry.partner.PartnerStore;
import java.math.RoundingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Slice integration test for {@link PartnerRepository} and the {@link PartnerStore}
 * boundary conversion. Uses the in-memory H2 (PostgreSQL compatibility mode) declared
 * in {@code application.properties}, so Flyway runs V001 against the same schema
 * definition used at runtime — no Docker, no Testcontainers.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerStore.class, PartnerSeeder.class})
class PartnerRepositoryIT {

    @Autowired
    private PartnerRepository repository;

    @Autowired
    private PartnerStore store;

    @Autowired
    private PartnerSeeder seeder;

    /**
     * {@link PartnerSeeder} is a {@link org.springframework.boot.CommandLineRunner}, which
     * the {@code @DataJpaTest} slice does not invoke automatically. Run it explicitly so
     * the bootstrap rows mirror what production gets at startup.
     */
    @BeforeEach
    void seedIfEmpty() {
        seeder.run();
    }

    @Test
    void savingPartnerPreservesRoundingMode() {
        // The DOWN mode is the one used for SENDMN in production: this verifies the
        // JPA boundary round-trip does not silently fall back to HALF_UP.
        Partner saved = store.save(
                new Partner("TBANK", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN));
        assertThat(saved.settlementRoundingMode()).isEqualTo(RoundingMode.DOWN);

        Partner reloaded = store.get("TBANK");
        assertThat(reloaded.partnerId()).isEqualTo("TBANK");
        assertThat(reloaded.type()).isEqualTo(PartnerType.OVERSEAS);
        assertThat(reloaded.settlementCurrency()).isEqualTo("USD");
        assertThat(reloaded.settlementRoundingMode()).isEqualTo(RoundingMode.DOWN);
    }

    @Test
    void gmeremitSeededRowExistsAfterStartup() {
        assertThat(repository.existsById("GMEREMIT"))
                .as("GMEREMIT must be seeded by PartnerSeeder on startup").isTrue();

        Partner gmeremit = store.get("GMEREMIT");
        assertThat(gmeremit.type()).isEqualTo(PartnerType.LOCAL);
        assertThat(gmeremit.settlementCurrency()).isEqualTo("KRW");
        assertThat(gmeremit.settlementRoundingMode()).isEqualTo(RoundingMode.HALF_UP);
    }
}
