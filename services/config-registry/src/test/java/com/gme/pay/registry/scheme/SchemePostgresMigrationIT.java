package com.gme.pay.registry.scheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Slice 7: real-PostgreSQL integration tests for the V022 ({@code partner_scheme})
 * and V024 ({@code scheme_operating_hours}) migrations. Boots the full service
 * against a postgres:16 Testcontainer so the whole Flyway chain runs on the
 * production database engine (the H2-PostgreSQL-mode slices in
 * {@link PartnerSchemeRepositoryTest} / {@link SchemeOperatingHoursRepositoryTest}
 * stay for fast local unit runs).
 *
 * <p>Docker-tagged: excluded from the normal {@code test} task and executed by
 * the {@code integrationTest} task on CI ubuntu runners. The dev workstation
 * has no Docker — never run this locally.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SchemePostgresMigrationIT {

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

    /** The V003/V004 surrogate of a seeded partner (PartnerSeeder runs in the full context). */
    private Long seededPartnerId() {
        return jdbc.queryForObject(
                "select id from partners where partner_code = 'GMEREMIT'"
                        + " and superseded_at is null", Long.class);
    }

    @Test
    void v022AndV024ApplyCleanlyOnPostgres16() {
        // Version-targeted (not a brittle total count — sibling lanes add their
        // own migrations to the same chain).
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history"
                        + " where success = true and version in ('022', '024')", Integer.class);
        assertThat(applied)
                .as("V022 (partner_scheme) and V024 (scheme_operating_hours) must apply on PG16")
                .isEqualTo(2);
    }

    @Test
    void operatingHoursSeed_landsOnPostgres_withExpectedCutoffs() {
        Integer total = jdbc.queryForObject(
                "select count(*) from scheme_operating_hours", Integer.class);
        assertThat(total)
                .as("5 seeded schemes x 7 weekdays (QRIS/KHQR deliberately unseeded)")
                .isEqualTo(35);

        Integer zeropay = jdbc.queryForObject(
                "select count(*) from scheme_operating_hours where scheme_id = 'ZEROPAY'"
                        + " and open_time_local = TIME '00:00:00'"
                        + " and close_time_local = TIME '23:59:59'"
                        + " and cutoff_time_local = TIME '16:30:00'"
                        + " and timezone = 'Asia/Seoul'", Integer.class);
        assertThat(zeropay).as("ZEROPAY 24x7 with the KFTC 16:30 KST cutoff").isEqualTo(7);

        Integer noCutoff = jdbc.queryForObject(
                "select count(*) from scheme_operating_hours"
                        + " where scheme_id in ('NAPAS_247', 'PROMPT_PAY', 'FAST_SG')"
                        + " and cutoff_time_local is null", Integer.class);
        assertThat(noCutoff).as("the three 24x7 instant rails carry no cutoff").isEqualTo(21);
    }

    @Test
    void currentRowUniqueIndex_enforcedByPostgres() {
        Long partnerId = seededPartnerId();
        String key = partnerId + ":KHQR";

        jdbc.update("insert into partner_scheme"
                        + " (partner_id, scheme_id, direction, role, current_scheme_key)"
                        + " values (?, 'KHQR', 'OUTBOUND', 'ACQUIRER', ?)",
                partnerId, key);
        try {
            // Second CURRENT row for the same (partner, scheme): the
            // partner_scheme_current UNIQUE index must reject it on PG exactly
            // as the H2 slice pinned.
            assertThatThrownBy(() -> jdbc.update("insert into partner_scheme"
                            + " (partner_id, scheme_id, direction, role, current_scheme_key)"
                            + " values (?, 'KHQR', 'OUTBOUND', 'ACQUIRER', ?)",
                    partnerId, key))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // Historical rows (NULL key) never collide.
            jdbc.update("insert into partner_scheme"
                            + " (partner_id, scheme_id, direction, role,"
                            + "  superseded_at, current_scheme_key)"
                            + " values (?, 'KHQR', 'OUTBOUND', 'ACQUIRER',"
                            + "  CURRENT_TIMESTAMP, NULL)",
                    partnerId);
            jdbc.update("insert into partner_scheme"
                            + " (partner_id, scheme_id, direction, role,"
                            + "  superseded_at, current_scheme_key)"
                            + " values (?, 'KHQR', 'OUTBOUND', 'ACQUIRER',"
                            + "  CURRENT_TIMESTAMP, NULL)",
                    partnerId);
            Integer rows = jdbc.queryForObject(
                    "select count(*) from partner_scheme where partner_id = ?"
                            + " and scheme_id = 'KHQR'", Integer.class, partnerId);
            assertThat(rows).isEqualTo(3);
        } finally {
            jdbc.update("delete from partner_scheme where partner_id = ?"
                    + " and scheme_id = 'KHQR'", partnerId);
        }
    }

    @Test
    void v022CheckConstraints_enforcedByPostgres() {
        Long partnerId = seededPartnerId();

        // Off-roster scheme.
        assertThatThrownBy(() -> jdbc.update("insert into partner_scheme"
                        + " (partner_id, scheme_id, direction, role)"
                        + " values (?, 'ALIPAY', 'OUTBOUND', 'ACQUIRER')", partnerId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Off-roster direction.
        assertThatThrownBy(() -> jdbc.update("insert into partner_scheme"
                        + " (partner_id, scheme_id, direction, role)"
                        + " values (?, 'QRIS', 'SIDEWAYS', 'ACQUIRER')", partnerId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Off-roster partner_type_char.
        assertThatThrownBy(() -> jdbc.update("insert into partner_scheme"
                        + " (partner_id, scheme_id, direction, role, partner_type_char)"
                        + " values (?, 'QRIS', 'OUTBOUND', 'ACQUIRER', 'X')", partnerId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // GENERATED ALWAYS AS IDENTITY + DEFAULT TRUE enabled: a minimal row
        // lands with engine-minted id and the kill switch defaulted ON.
        jdbc.update("insert into partner_scheme (partner_id, scheme_id, direction, role)"
                + " values (?, 'QRIS', 'OUTBOUND', 'ACQUIRER')", partnerId);
        try {
            Boolean enabled = jdbc.queryForObject(
                    "select enabled from partner_scheme where partner_id = ?"
                            + " and scheme_id = 'QRIS'", Boolean.class, partnerId);
            assertThat(enabled).isTrue();
        } finally {
            jdbc.update("delete from partner_scheme where partner_id = ?"
                    + " and scheme_id = 'QRIS'", partnerId);
        }
    }

    @Test
    void v024WeekdayCheck_enforcedByPostgres() {
        assertThatThrownBy(() -> jdbc.update("insert into scheme_operating_hours"
                + " (scheme_id, weekday, open_time_local, close_time_local, timezone)"
                + " values ('QRIS', 7, TIME '00:00:00', TIME '23:59:59', 'Asia/Jakarta')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
