package com.gme.pay.registry.webhook;

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
 * Slice 8 Lane D: real-PostgreSQL integration tests for
 * {@code partner_webhook_subscription} (V030). Boots the full service against
 * a postgres:16 Testcontainer so the migration runs on the production engine
 * — V030 is engine-neutral (plain TIMESTAMP / TEXT CSV / plain UNIQUE, no
 * vendor pair needed), so what this IT pins is that the chain applies and the
 * declarative constraints genuinely bite on PG.
 *
 * <p>Docker-tagged: excluded from the normal {@code test} task and executed
 * by the {@code integrationTest} task on CI ubuntu runners — same contract as
 * {@code PartnerCorridorPostgresIT}.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PartnerWebhookSubscriptionPostgresIT {

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

    private void insertSubscription(Long partnerId, String environment, String url,
                                    String status) {
        jdbc.update("insert into partner_webhook_subscription"
                        + " (partner_id, environment, url, status)"
                        + " values (?, ?, ?, ?)",
                partnerId, environment, url, status);
    }

    @Test
    void v030AppliesOnPostgres16_withDefaultsFiring() {
        Integer v030 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history"
                        + " where version = '030' and success = true", Integer.class);
        assertThat(v030).as("V030__partner_webhook_subscription must apply on PG16")
                .isEqualTo(1);

        Long partnerId = seededPartnerId();
        jdbc.update("insert into partner_webhook_subscription (partner_id, environment, url)"
                + " values (?, 'SANDBOX', 'https://p.example.com/h')", partnerId);
        Integer defaulted = jdbc.queryForObject(
                "select count(*) from partner_webhook_subscription"
                        + " where partner_id = ? and environment = 'SANDBOX'"
                        + " and status = 'DRAFT'"
                        + " and created_at is not null and updated_at is not null"
                        + " and endpoint_id is null and signing_secret_hash is null",
                Integer.class, partnerId);
        assertThat(defaulted)
                .as("status/created_at/updated_at DEFAULTs fire; secret columns start NULL")
                .isEqualTo(1);
    }

    @Test
    void uniquePartnerEnvironment_enforcedByPostgres() {
        Long partnerId = seededPartnerId();
        insertSubscription(partnerId, "LIVE", "https://p.example.com/live", "DRAFT");

        // A second row for the same (partner, environment) collides.
        assertThatThrownBy(() -> insertSubscription(
                partnerId, "LIVE", "https://p.example.com/other", "DRAFT"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_partner_webhook_subscription_env");

        // A different environment sails through (SANDBOX row may exist from
        // the other test — order-independent: use a count check instead).
        Integer liveRows = jdbc.queryForObject(
                "select count(*) from partner_webhook_subscription"
                        + " where partner_id = ? and environment = 'LIVE'",
                Integer.class, partnerId);
        assertThat(liveRows).isEqualTo(1);
    }

    @Test
    void checkRosters_environmentAndStatus_enforcedByPostgres() {
        Long partnerId = seededPartnerId();

        assertThatThrownBy(() -> insertSubscription(
                partnerId, "PROD", "https://p.example.com/h", "DRAFT"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_partner_webhook_subscription_env");

        assertThatThrownBy(() -> insertSubscription(
                partnerId, "SANDBOX", "https://p.example.com/h", "PENDING"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_partner_webhook_subscription_status");

        // FK: an unknown partner surrogate is rejected.
        assertThatThrownBy(() -> insertSubscription(
                999999L, "SANDBOX", "https://p.example.com/h", "DRAFT"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_partner_webhook_subscription_partner");
    }
}
