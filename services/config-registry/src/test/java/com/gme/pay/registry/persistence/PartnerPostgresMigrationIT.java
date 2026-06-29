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
 * Ticket 17.2-G01 (+ Slice 1 / V004): real-PostgreSQL integration tests for the partner
 * tables. Boots the full service against a postgres:16 Testcontainer so Flyway V001..V004
 * runs on the production database engine (the H2-PostgreSQL-mode slice in
 * {@link PartnerRepositoryIT} stays for fast local unit runs).
 *
 * <p>Docker-tagged: excluded from the normal {@code test} task and executed by the
 * {@code integrationTest} task on CI ubuntu runners. The dev workstation has no Docker —
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
        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);
        assertThat(failed)
                .as("no Flyway migration may have failed during boot on PG16")
                .isZero();

        // Version-targeted: assert every required Slice 1..7 migration is present + successful
        // (count-based assertions rot every slice — see Slice 7 verifier note).
        Integer requiredApplied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history"
                        + " where success = true"
                        + " and version in ('001','002','003','005','006','007','008','009','010','011','012','013','014',"
                        + " '015','016','017','018','019','020','021','022','023','024')",
                Integer.class);
        assertThat(requiredApplied)
                .as("V001..V003 + V005..V024 (V004 was dropped in Slice 1) — 23 migrations — must all apply on PG16")
                .isEqualTo(23);
    }

    @Test
    void v003AddsSurrogateIdAndPartnerCodeColumns() {
        // Slice 1 schism resolution acceptance check: SELECT id, partner_code FROM partners
        // returns a populated row for every seeded partner. V004 promoted id to PK so the
        // legacy partner_id column is no longer the PK; it stays populated (Expand) and
        // carries the same value as partner_code.
        Integer seededWithSurrogate = jdbc.queryForObject(
                "select count(*) from partners where partner_code in ('GMEREMIT', 'SENDMN')"
                        + " and id is not null and partner_code is not null",
                Integer.class);
        assertThat(seededWithSurrogate)
                .as("V003 backfill must give every existing seed row a BIGINT surrogate + partner_code")
                .isEqualTo(2);

        String partnerCode = jdbc.queryForObject(
                "select partner_code from partners where partner_code = 'GMEREMIT'"
                        + " and superseded_at is null", String.class);
        assertThat(partnerCode)
                .as("partner_code must be queryable post-V004 (current row, superseded_at IS NULL)")
                .isEqualTo("GMEREMIT");
    }

    @Test
    void settlementRoundingModeColumnDefaultsToHalfUp() {
        // Insert a raw row WITHOUT the settlement_rounding_mode column: the DB-level
        // default must kick in (MONEY_CONVENTION.md default policy). V004 made `id`
        // the PK so we have to feed one; pull from the same sequence the app does.
        jdbc.update("insert into partners (id, partner_code, partner_id, type)"
                + " values (nextval('partners_id_seq'), 'RAWROW', 'RAWROW', 'LOCAL')");

        String mode = jdbc.queryForObject(
                "select settlement_rounding_mode from partners where partner_code = 'RAWROW'",
                String.class);
        assertThat(mode).isEqualTo("HALF_UP");

        // V002 default carried through V004's rename: effective since the epoch,
        // open-ended (compared server-side to avoid any client time-zone conversion).
        Integer epochDefaulted = jdbc.queryForObject(
                "select count(*) from partners where partner_code = 'RAWROW'"
                        + " and valid_from = TIMESTAMP '1970-01-01 00:00:00' and valid_to is null",
                Integer.class);
        assertThat(epochDefaulted).isEqualTo(1);

        // V004: every row has a non-NULL recorded_at (defaulted to now()) and a NULL
        // superseded_at (current row).
        Integer bitemporalCurrent = jdbc.queryForObject(
                "select count(*) from partners where partner_code = 'RAWROW'"
                        + " and recorded_at is not null and superseded_at is null",
                Integer.class);
        assertThat(bitemporalCurrent).isEqualTo(1);
    }

    @Test
    void partnerRoundTripsThroughStoreOnPostgres() {
        // Seeded rows (PartnerSeeder runs as a CommandLineRunner in the full context).
        Partner gmeremit = store.get("GMEREMIT");
        assertThat(gmeremit.settlementRoundingMode()).isEqualTo(RoundingMode.HALF_UP);
        assertThat(store.get("SENDMN").settlementRoundingMode()).isEqualTo(RoundingMode.DOWN);

        // Fresh round-trip with a non-default mode: must not fall back to HALF_UP.
        store.save(Partner.of("TBANK", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN));
        Partner reloaded = store.get("TBANK");
        assertThat(reloaded.partnerCode()).isEqualTo("TBANK");
        assertThat(reloaded.partnerId())
                .as("a freshly-saved partner must come back with its BIGINT surrogate populated")
                .isNotNull();
        assertThat(reloaded.type()).isEqualTo(PartnerType.OVERSEAS);
        assertThat(reloaded.settlementCurrency()).isEqualTo("USD");
        assertThat(reloaded.settlementRoundingMode()).isEqualTo(RoundingMode.DOWN);
    }

    @Test
    void bitemporalAsOfQueriesAreCorrectAtBoundaryInstants() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");

        PartnerEntity windowed = new PartnerEntity("PGWINDOW", PartnerType.OVERSEAS, "MNT", RoundingMode.HALF_UP);
        windowed.setValidFrom(from);
        windowed.setValidTo(to);
        windowed.setId(((Number) jdbc.queryForObject(
                "select nextval('partners_id_seq')", Number.class)).longValue());
        repository.saveAndFlush(windowed);

        Instant nowT = Instant.now();
        // Half-open [from, to): lower bound inclusive, upper bound exclusive.
        assertThat(repository.findAsOf("PGWINDOW", from, nowT))
                .as("valid AT exactly valid_from (inclusive lower bound)").isPresent();
        assertThat(repository.findAsOf("PGWINDOW", to.minusSeconds(1), nowT))
                .as("valid just before valid_to").isPresent();
        assertThat(repository.findAsOf("PGWINDOW", to, nowT))
                .as("NOT valid at exactly valid_to (exclusive upper bound)").isEmpty();
        assertThat(repository.findAsOf("PGWINDOW", from.minusSeconds(1), nowT))
                .as("NOT valid before the window opens").isEmpty();

        // Open-ended seed rows are valid from the epoch forever.
        assertThat(repository.findAsOf("GMEREMIT", Instant.EPOCH, nowT)).isPresent();
        assertThat(repository.findAsOf("GMEREMIT", Instant.parse("2030-01-01T00:00:00Z"), nowT)).isPresent();
    }
}
