package com.gme.pay.registry.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerSeeder;
import com.gme.pay.registry.partner.PartnerStore;
import java.math.RoundingMode;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Slice integration test for {@link PartnerRepository} and the {@link PartnerStore}
 * boundary conversion. Uses the in-memory H2 (PostgreSQL compatibility mode) declared
 * in {@code application.properties}, so Flyway runs V001..V004 against the same schema
 * definition used at runtime — no Docker, no Testcontainers. The same behaviours are
 * re-verified against real PostgreSQL 16 in {@code PartnerPostgresMigrationIT}
 * (docker-tagged, CI-only).
 *
 * <p>{@link CacheConfig} is imported so {@link PartnerStore} gets its {@code ConfigCache}
 * collaborator; with no {@code spring.data.redis.host} set in tests this resolves to
 * the no-op pass-through, keeping this slice Redis-free.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerStore.class, PartnerSeeder.class, CacheConfig.class})
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
                Partner.of("TBANK", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN));
        assertThat(saved.settlementRoundingMode()).isEqualTo(RoundingMode.DOWN);
        assertThat(saved.partnerId())
                .as("V003 surrogate id must be populated by the application-layer sequence pull on insert")
                .isNotNull();

        Partner reloaded = store.get("TBANK");
        assertThat(reloaded.partnerCode()).isEqualTo("TBANK");
        assertThat(reloaded.partnerId())
                .as("the surrogate must round-trip through the persistence boundary")
                .isNotNull();
        assertThat(reloaded.type()).isEqualTo(PartnerType.OVERSEAS);
        assertThat(reloaded.settlementCurrency()).isEqualTo("USD");
        assertThat(reloaded.settlementRoundingMode()).isEqualTo(RoundingMode.DOWN);
    }

    @Test
    void gmeremitSeededRowExistsAfterStartup() {
        assertThat(repository.existsByPartnerCode("GMEREMIT"))
                .as("GMEREMIT must be seeded by PartnerSeeder on startup").isTrue();

        Partner gmeremit = store.get("GMEREMIT");
        assertThat(gmeremit.type()).isEqualTo(PartnerType.LOCAL);
        assertThat(gmeremit.settlementCurrency()).isEqualTo("KRW");
        assertThat(gmeremit.settlementRoundingMode()).isEqualTo(RoundingMode.HALF_UP);
    }

    @Test
    void seededPartnersAreValidSinceEpochAndOpenEnded() {
        // V002 backfilled valid_from to the epoch with a NULL (open-ended) valid_to,
        // so seeded partners must be visible at any instant from the epoch onwards.
        // V004 added the transaction-time axis; for seeded rows that means recorded_at
        // is whenever the seeder fired, and Instant.now() is always >= that, so the
        // as-of view at (epoch, now) returns the seeded row.
        Instant now = Instant.now();
        assertThat(repository.findAsOf("GMEREMIT", Instant.EPOCH, now)).isPresent();
        assertThat(repository.findAsOf("GMEREMIT", Instant.parse("2026-06-10T00:00:00Z"), now)).isPresent();
    }

    @Test
    void effectiveDatingWindowIsHalfOpenAtBoundaryInstants() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");

        PartnerEntity windowed = new PartnerEntity("WINDOWED", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP);
        windowed.setValidFrom(from);
        windowed.setValidTo(to);
        windowed.setId(9_000_001L); // surrogate must be set: V004 makes id the PK.
        // Manually-assigned id means Spring Data routes save() through em.merge(),
        // and @PrePersist fires on Hibernate's managed COPY — the local `windowed`
        // reference keeps recordedAt == null. Use the returned managed entity.
        PartnerEntity saved = repository.saveAndFlush(windowed);

        // Transaction-time pin: every assertion uses the same recordedAt, so we are
        // only exercising the business-time axis here. ADR-010 half-open semantics:
        // [from, to) — lower bound inclusive, upper bound exclusive.
        // Pin to the row's own persisted recorded_at (not Instant.now()): the entity
        // truncates to MICROS at persist so this matches the stored value exactly.
        Instant nowT = saved.getRecordedAt();
        assertThat(repository.findAsOf("WINDOWED", from, nowT))
                .as("partner IS valid at exactly valid_from").isPresent();
        assertThat(repository.findAsOf("WINDOWED", to.minusSeconds(1), nowT))
                .as("partner IS valid just before valid_to").isPresent();
        assertThat(repository.findAsOf("WINDOWED", to, nowT))
                .as("partner is NOT valid at exactly valid_to (upper bound exclusive)").isEmpty();
        assertThat(repository.findAsOf("WINDOWED", from.minusSeconds(1), nowT))
                .as("partner is NOT valid before valid_from").isEmpty();
        assertThat(repository.findAsOf("WINDOWED", to.plusSeconds(1), nowT))
                .as("partner is NOT valid after valid_to").isEmpty();
    }
}
