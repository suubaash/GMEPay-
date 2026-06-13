package com.gme.pay.registry.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * Slice 8: real-PostgreSQL integration tests for V025 (partner lifecycle
 * columns + the widened ten-value status CHECK + the suspension-reason CHECK +
 * {@code partner_contract.signed_at}). Boots the full service against a
 * postgres:16 Testcontainer so the DROP CONSTRAINT / ADD CONSTRAINT sequence
 * runs on the production engine (the H2 spelling is exercised by every
 * {@code @DataJpaTest} slice).
 *
 * <p>Docker-tagged: excluded from the normal {@code test} task and executed by
 * the {@code integrationTest} task on CI ubuntu runners — same contract as
 * {@code PartnerPostgresMigrationIT} / {@code PartnerCorridorPostgresIT}.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PartnerLifecyclePostgresIT {

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

    private static final List<String> ALL_TEN_STATUSES = List.of(
            "DRAFT", "ONBOARDING", "KYB_PENDING", "KYB_APPROVED", "CONTRACT_SIGNED",
            "SANDBOX", "UAT", "LIVE", "SUSPENDED", "TERMINATED");

    /** The GMEREMIT seed row's current generation (PartnerSeeder runs in the full context). */
    private static final String CURRENT_GMEREMIT =
            "partner_code = 'GMEREMIT' and superseded_at is null";

    @Test
    void v025AppliesOnPostgres16() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history"
                        + " where version = '25' and success = true", Integer.class);
        assertThat(applied).as("V025__partner_lifecycle must apply on PG16").isEqualTo(1);
    }

    @Test
    void statusCheckAcceptsAllTenLifecycleValues() {
        for (String status : ALL_TEN_STATUSES) {
            int updated = jdbc.update(
                    "update partners set status = ? where " + CURRENT_GMEREMIT, status);
            assertThat(updated)
                    .as("ck_partners_status must accept '" + status + "'")
                    .isEqualTo(1);
        }
        // leave the seed row the way the seeder created it
        jdbc.update("update partners set status = 'ONBOARDING' where " + CURRENT_GMEREMIT);
    }

    @Test
    void statusCheckRejectsUnknownValue() {
        assertThatThrownBy(() -> jdbc.update(
                "update partners set status = 'PAUSED' where " + CURRENT_GMEREMIT))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void lifecycleColumnsPersistAndReadBack() {
        jdbc.update("update partners set"
                        + " go_live_at = ?, activated_by = ?,"
                        + " suspension_reason = ?, suspension_notes = ?, suspended_at = ?,"
                        + " terminated_at = ?, termination_reason = ?"
                        + " where " + CURRENT_GMEREMIT,
                OffsetDateTime.parse("2026-06-10T01:02:03.000004Z"),
                "checker_lee",
                "SANCTIONS_HIT",
                "OFAC list match pending review",
                OffsetDateTime.parse("2026-06-11T08:00:00Z"),
                OffsetDateTime.parse("2026-06-12T09:30:00Z"),
                "partner declined renewal");

        Map<String, Object> row = jdbc.queryForMap(
                "select go_live_at, activated_by, suspension_reason, suspension_notes,"
                        + " suspended_at, terminated_at, termination_reason"
                        + " from partners where " + CURRENT_GMEREMIT);
        assertThat(row.get("go_live_at")).isNotNull();
        assertThat(row.get("activated_by")).isEqualTo("checker_lee");
        assertThat(row.get("suspension_reason")).isEqualTo("SANCTIONS_HIT");
        assertThat(row.get("suspension_notes")).isEqualTo("OFAC list match pending review");
        assertThat(row.get("suspended_at")).isNotNull();
        assertThat(row.get("terminated_at")).isNotNull();
        assertThat(row.get("termination_reason")).isEqualTo("partner declined renewal");

        // reset for sibling tests
        jdbc.update("update partners set go_live_at = null, activated_by = null,"
                + " suspension_reason = null, suspension_notes = null, suspended_at = null,"
                + " terminated_at = null, termination_reason = null"
                + " where " + CURRENT_GMEREMIT);
    }

    @Test
    void suspensionReasonCheckEnforcesTheRoster() {
        // Every roster value passes…
        for (String reason : List.of("LIMIT_BREACH", "SANCTIONS_HIT", "CREDENTIAL_COMPROMISE",
                "KYB_LAPSED", "CONTRACT_EXPIRED", "OPERATOR_INITIATED")) {
            int updated = jdbc.update(
                    "update partners set suspension_reason = ? where " + CURRENT_GMEREMIT,
                    reason);
            assertThat(updated).isEqualTo(1);
        }
        // …an off-roster value is rejected by ck_partners_suspension_reason…
        assertThatThrownBy(() -> jdbc.update(
                "update partners set suspension_reason = 'FELT_LIKE_IT' where "
                        + CURRENT_GMEREMIT))
                .isInstanceOf(DataAccessException.class);
        // …and NULL passes (reactivation clears the column).
        assertThat(jdbc.update(
                "update partners set suspension_reason = null where " + CURRENT_GMEREMIT))
                .isEqualTo(1);
    }

    @Test
    void contractSignedAtColumnExists() {
        // V025 added partner_contract.signed_at — selecting it must not throw,
        // and it is nullable (every pre-Slice-8 row carries NULL).
        List<Map<String, Object>> rows =
                jdbc.queryForList("select signed_at from partner_contract");
        assertThat(rows).isNotNull();
    }
}
