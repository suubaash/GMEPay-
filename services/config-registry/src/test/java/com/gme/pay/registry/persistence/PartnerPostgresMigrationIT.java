package com.gme.pay.registry.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.partner.PartnerStore;
import java.math.RoundingMode;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Ticket 17.2-G01: real-PostgreSQL integration tests for the partner tables.
 * Boots the full service against a postgres:16 Testcontainer so Flyway V001+
 * runs on the production database engine (the H2-PostgreSQL-mode slice in
 * {@link PartnerRepositoryIT} stays for fast local unit runs).
 *
 * <p>Docker-tagged: excluded from the normal {@code test} task and executed by the
 * {@code integrationTest} task on CI ubuntu runners. This machine has no Docker —
 * never run this locally.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PartnerPostgresMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private PartnerRepository repository;

    @Autowired
    private PartnerStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayMigrationsApplyCleanlyOnPostgres16() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(applied)
                .as("V001 (partners) and V002 (effective dating) must both apply on PG16")
                .isEqualTo(2);
    }

    @Test
    void settlementRoundingModeColumnDefaultsToHalfUp() {
        // Insert a raw row WITHOUT the settlement_rounding_mode column: the DB-level
        // default must kick in (MONEY_CONVENTION.md default policy).
        jdbc.update("insert into partners (partner_id, type) values ('RAWROW', 'LOCAL')");

        String mode = jdbc.queryForObject(
                "select settlement_rounding_mode from partners where partner_id = 'RAWROW'", String.class);
        assertThat(mode).isEqualTo("HALF_UP");

        // V002 default: effective since the epoch, open-ended (compared server-side
        // to avoid any client time-zone conversion).
        Integer epochDefaulted = jdbc.queryForObject(
                "select count(*) from partners where partner_id = 'RAWROW'"
                        + " and effective_from = TIMESTAMP '1970-01-01 00:00:00' and effective_to is null",
                Integer.class);
        assertThat(epochDefaulted).isEqualTo(1);
    }

    @Test
    void partnerRoundTripsThroughStoreOnPostgres() {
        // Seeded rows (PartnerSeeder runs as a CommandLineRunner in the full context).
        Partner gmeremit = store.get("GMEREMIT");
        assertThat(gmeremit.settlementRoundingMode()).isEqualTo(RoundingMode.HALF_UP);
        assertThat(store.get("SENDMN").settlementRoundingMode()).isEqualTo(RoundingMode.DOWN);

        // Fresh round-trip with a non-default mode: must not fall back to HALF_UP.
        store.save(new Partner("TBANK", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN));
        Partner reloaded = store.get("TBANK");
        assertThat(reloaded.type()).isEqualTo(PartnerType.OVERSEAS);
        assertThat(reloaded.settlementCurrency()).isEqualTo("USD");
        assertThat(reloaded.settlementRoundingMode()).isEqualTo(RoundingMode.DOWN);
    }

    @Test
    void effectiveDatingQueriesAreCorrectAtBoundaryInstants() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");

        PartnerEntity windowed = new PartnerEntity("PGWINDOW", PartnerType.OVERSEAS, "MNT", RoundingMode.HALF_UP);
        windowed.setEffectiveFrom(from);
        windowed.setEffectiveTo(to);
        repository.saveAndFlush(windowed);

        // Half-open [from, to): lower bound inclusive, upper bound exclusive.
        assertThat(repository.findEffectiveAt("PGWINDOW", from))
                .as("effective AT exactly effective_from (inclusive lower bound)").isPresent();
        assertThat(repository.findEffectiveAt("PGWINDOW", to.minusSeconds(1)))
                .as("effective just before effective_to").isPresent();
        assertThat(repository.findEffectiveAt("PGWINDOW", to))
                .as("NOT effective at exactly effective_to (exclusive upper bound)").isEmpty();
        assertThat(repository.findEffectiveAt("PGWINDOW", from.minusSeconds(1)))
                .as("NOT effective before the window opens").isEmpty();

        // Open-ended seed rows are effective from the epoch forever.
        assertThat(repository.findEffectiveAt("GMEREMIT", Instant.EPOCH)).isPresent();
        assertThat(repository.findEffectiveAt("GMEREMIT", Instant.parse("2030-01-01T00:00:00Z"))).isPresent();
    }
}
