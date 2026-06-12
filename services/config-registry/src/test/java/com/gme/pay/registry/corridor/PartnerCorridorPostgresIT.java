package com.gme.pay.registry.corridor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Slice 7: real-PostgreSQL integration tests for {@code partner_corridor}
 * (V023). Boots the full service against a postgres:16 Testcontainer so the
 * POSTGRESQL VENDOR VARIANT of V023 — the one carrying the {@code STORED}
 * keyword PG requires on its generated {@code is_current} column — runs on
 * the production database engine (the H2 variant is what every
 * {@code @DataJpaTest} slice exercises; THIS file is the only coverage the
 * PG spelling gets, the exact gap that bit V004).
 *
 * <p>Docker-tagged: excluded from the normal {@code test} task and executed
 * by the {@code integrationTest} task on CI ubuntu runners — same contract as
 * {@code PartnerPostgresMigrationIT}.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PartnerCorridorPostgresIT {

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
    private JdbcTemplate jdbc;

    /** The GMEREMIT seed row's surrogate id (PartnerSeeder runs in the full context). */
    private Long seededPartnerId() {
        return jdbc.queryForObject(
                "select id from partners where partner_code = 'GMEREMIT'"
                        + " and superseded_at is null", Long.class);
    }

    private void insertCorridor(Long partnerId, String srcCountry, String srcCcy,
                                String dstCountry, String dstCcy) {
        jdbc.update("insert into partner_corridor"
                        + " (partner_id, src_country, src_ccy, dst_country, dst_ccy)"
                        + " values (?, ?, ?, ?, ?)",
                partnerId, srcCountry, srcCcy, dstCountry, dstCcy);
    }

    @Test
    void v023AppliesOnPostgres16_withTheStoredGeneratedCurrentColumn() {
        // The vendor-pair file must have been picked up and applied
        // successfully — V004 taught us a PG syntax error here aborts Flyway.
        Integer v023 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history"
                        + " where version = '23' and success = true", Integer.class);
        assertThat(v023).as("V023__partner_corridor must apply on PG16").isEqualTo(1);

        // The generated column is genuinely STORED and computes TRUE on a
        // freshly-inserted (current) row; defaults fire for is_active /
        // valid_from / recorded_at.
        insertCorridor(seededPartnerId(), "KR", "KRW", "MN", "MNT");
        Boolean isCurrent = jdbc.queryForObject(
                "select is_current from partner_corridor"
                        + " where src_country = 'KR' and dst_country = 'MN'", Boolean.class);
        assertThat(isCurrent).isTrue();
        Integer defaulted = jdbc.queryForObject(
                "select count(*) from partner_corridor"
                        + " where src_country = 'KR' and dst_country = 'MN'"
                        + " and is_active = true and recorded_at is not null"
                        + " and valid_from is not null and superseded_at is null",
                Integer.class);
        assertThat(defaulted).isEqualTo(1);
    }

    @Test
    void partialUniqueIndex_oneCurrentRowPerLane_enforcedByPostgres() {
        Long partnerId = seededPartnerId();
        insertCorridor(partnerId, "KR", "KRW", "VN", "VND");

        // A second CURRENT row for the same lane collides on
        // (partner, lane, is_current=TRUE).
        assertThatThrownBy(() -> insertCorridor(partnerId, "KR", "KRW", "VN", "VND"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("partner_corridor_current");

        // A different lane sails through.
        insertCorridor(partnerId, "KR", "KRW", "VN", "USD");
    }

    @Test
    void closeAndReinsert_supersedeRecomputesIsCurrentAndVacatesTheSlot() {
        Long partnerId = seededPartnerId();
        insertCorridor(partnerId, "KR", "KRW", "KH", "KHR");

        // SCD-6 close: PG recomputes the STORED column on UPDATE → NULL,
        // vacating the unique slot without any application bookkeeping.
        jdbc.update("update partner_corridor set superseded_at = now()"
                + " where dst_country = 'KH' and superseded_at is null");
        Boolean closedIsCurrent = jdbc.queryForObject(
                "select is_current from partner_corridor where dst_country = 'KH'",
                Boolean.class);
        assertThat(closedIsCurrent).isNull();

        // Reinsert the same lane — historical + current coexist.
        insertCorridor(partnerId, "KR", "KRW", "KH", "KHR");
        Integer total = jdbc.queryForObject(
                "select count(*) from partner_corridor where dst_country = 'KH'",
                Integer.class);
        Integer current = jdbc.queryForObject(
                "select count(*) from partner_corridor where dst_country = 'KH'"
                        + " and superseded_at is null", Integer.class);
        assertThat(total).isEqualTo(2);
        assertThat(current).isEqualTo(1);
    }
}
