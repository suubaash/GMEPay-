package com.gme.pay.registry.regulatory;

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
 * Slice 8 Lane C: real-PostgreSQL integration tests for
 * {@code partner_regulatory_config} (V029) and the {@code partner_corridor}
 * STR flag (V029.1 — the plan doc's "V029b"). Boots the full service against
 * a postgres:16 Testcontainer so both migrations run on the production
 * database engine (every {@code @DataJpaTest} slice exercises only the H2
 * parse; THIS file is the PG coverage — the exact gap that bit V004).
 *
 * <p>Docker-tagged: excluded from the normal {@code test} task and executed
 * by the {@code integrationTest} task on CI ubuntu runners — same contract as
 * {@code PartnerCorridorPostgresIT}.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PartnerRegulatoryPostgresIT {

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

    /** Minimal regulatory insert mirroring what the entity writes (key app-stamped). */
    private void insertRegulatory(Long partnerId, Long currentPartnerKey, String kofiu) {
        jdbc.update("insert into partner_regulatory_config"
                        + " (partner_id, kofiu_entity_id, current_partner_key, changed_by)"
                        + " values (?, ?, ?, ?)",
                partnerId, kofiu, currentPartnerKey, "it_test");
    }

    @Test
    void v029AndV029_1ApplyOnPostgres16() {
        // Both Lane C migrations must have been picked up and applied — a PG
        // syntax error here aborts Flyway (the V004 lesson).
        Integer v029 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history"
                        + " where version = '29' and success = true", Integer.class);
        assertThat(v029).as("V029__partner_regulatory must apply on PG16").isEqualTo(1);

        Integer v029_1 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history"
                        + " where version = '29.1' and success = true", Integer.class);
        assertThat(v029_1).as("V029_1__partner_corridor_str_flag must apply on PG16")
                .isEqualTo(1);
    }

    @Test
    void v029Defaults_andCheckConstraints_holdOnPostgres() {
        Long partnerId = seededPartnerId();
        insertRegulatory(partnerId, partnerId, "KOFIU-IT-1");

        // Column DEFAULTs fire: statutory CTR 10,000,000 / Travel Rule 1,000,000.
        java.math.BigDecimal ctr = jdbc.queryForObject(
                "select ctr_threshold_krw from partner_regulatory_config"
                        + " where kofiu_entity_id = 'KOFIU-IT-1'", java.math.BigDecimal.class);
        assertThat(ctr).isEqualByComparingTo(new java.math.BigDecimal("10000000"));
        java.math.BigDecimal tr = jdbc.queryForObject(
                "select travel_rule_threshold_krw from partner_regulatory_config"
                        + " where kofiu_entity_id = 'KOFIU-IT-1'", java.math.BigDecimal.class);
        assertThat(tr).isEqualByComparingTo(new java.math.BigDecimal("1000000"));

        // The roster CHECKs reject off-roster values at the engine.
        assertThatThrownBy(() -> jdbc.update(
                "insert into partner_regulatory_config (partner_id, vat_treatment)"
                        + " values (?, 'REDUCED_RATE')", partnerId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_partner_regulatory_vat");
        assertThatThrownBy(() -> jdbc.update(
                "insert into partner_regulatory_config (partner_id, travel_rule_protocol)"
                        + " values (?, 'OPENVASP')", partnerId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_partner_regulatory_travel_protocol");
        assertThatThrownBy(() -> jdbc.update(
                "insert into partner_regulatory_config (partner_id, ctr_threshold_krw)"
                        + " values (?, 0)", partnerId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_partner_regulatory_ctr_threshold");
    }

    @Test
    void v029PartialUnique_oneCurrentRowPerPartner_viaAppMaintainedKey() {
        Long partnerId = seededPartnerId();
        insertRegulatory(partnerId, partnerId, "KOFIU-IT-2");

        // A second CURRENT row (same current_partner_key) collides on the
        // V017-pattern UNIQUE emulation.
        assertThatThrownBy(() -> insertRegulatory(partnerId, partnerId, "KOFIU-IT-2B"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("partner_regulatory_config_current");

        // SCD-6 close vacates the slot (application NULLs the key on supersede)
        // and historical + current rows coexist.
        jdbc.update("update partner_regulatory_config set superseded_at = now(),"
                + " current_partner_key = null where kofiu_entity_id = 'KOFIU-IT-2'");
        insertRegulatory(partnerId, partnerId, "KOFIU-IT-2C");
        Integer current = jdbc.queryForObject(
                "select count(*) from partner_regulatory_config"
                        + " where partner_id = ? and superseded_at is null",
                Integer.class, partnerId);
        assertThat(current).isEqualTo(1);
    }

    @Test
    void v029_1StrEnabled_defaultsFalse_onCorridorInsert() {
        Long partnerId = seededPartnerId();
        jdbc.update("insert into partner_corridor"
                        + " (partner_id, src_country, src_ccy, dst_country, dst_ccy)"
                        + " values (?, 'KR', 'KRW', 'NP', 'NPR')", partnerId);

        // The V029.1 column exists, is NOT NULL, and backfills/defaults FALSE.
        Boolean strEnabled = jdbc.queryForObject(
                "select str_enabled from partner_corridor"
                        + " where dst_country = 'NP' and superseded_at is null",
                Boolean.class);
        assertThat(strEnabled).isFalse();

        // And it is writable to TRUE (the per-lane KoFIU STR switch).
        jdbc.update("insert into partner_corridor"
                + " (partner_id, src_country, src_ccy, dst_country, dst_ccy, str_enabled)"
                + " values (?, 'KR', 'KRW', 'LK', 'LKR', true)", partnerId);
        Boolean lkStr = jdbc.queryForObject(
                "select str_enabled from partner_corridor"
                        + " where dst_country = 'LK' and superseded_at is null",
                Boolean.class);
        assertThat(lkStr).isTrue();
    }
}
