package com.gme.pay.registry.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.registry.audit.AuditLogRepository;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service slice test for the generic platform-settings store. Runs as a
 * {@code @DataJpaTest} against H2 (PostgreSQL mode) so Flyway applies the full
 * V001..V039 chain, including the V039 {@code platform_settings} seed rows.
 *
 * <p>Asserts: the seeded rows (V039 + V040) are returned key-sorted; PUT upserts a value and
 * writes exactly one hash-chained audit row per key; a NUMBER value that does not parse
 * is rejected 400 with no write/audit; GET on an unknown key is 404.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PlatformSettingServiceTest.TestConfig.class, PlatformSettingService.class,
         AuditLogService.class, CacheConfig.class})
class PlatformSettingServiceTest {

    @Autowired
    private PlatformSettingService service;

    @Autowired
    private PlatformSettingRepository repository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        AuditPublisher auditPublisher(RecordingAuditPublisher r) {
            return r;
        }
    }

    private long auditCount(String key) {
        return auditLogRepository.findChainByAggregate("platform-setting", key).size();
    }

    @Test
    @DisplayName("list returns the seeded rows (V039 tunables + V040 flywheel metrics), key-sorted")
    void listReturnsSeededRowsKeySorted() {
        List<PlatformSettingView> all = service.list();
        assertThat(all).extracting(PlatformSettingView::key).containsExactly(
                "flywheel.acceptance_points",
                "flywheel.adapter_time_to_live_days",
                "flywheel.monthly_active_payers",
                "fx.quote.ttl.seconds",
                "prefunding.alert.tier1.pct",
                "prefunding.alert.tier2.pct",
                "prefunding.alert.tier3.pct",
                "wallet.fee.krw");
        assertThat(all).allSatisfy(v -> {
            assertThat(v.valueType()).isEqualTo("NUMBER");
            assertThat(v.updatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("get returns one row; unknown key is 404")
    void getOneAndUnknown() {
        assertThat(service.get("wallet.fee.krw").value()).isEqualTo("500");
        assertThatThrownBy(() -> service.get("does.not.exist"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("PUT upserts the value, validates NUMBER, and writes one audit row")
    void putUpsertsAndAudits() {
        assertThat(auditCount("prefunding.alert.tier1.pct")).isZero();

        PlatformSettingView updated = service.upsert(
                "prefunding.alert.tier1.pct", "90", "alice", "10.0.0.1");

        assertThat(updated.value()).isEqualTo("90");
        assertThat(updated.updatedBy()).isEqualTo("alice");
        assertThat(repository.findById("prefunding.alert.tier1.pct").orElseThrow().getValue())
                .isEqualTo("90");
        assertThat(auditCount("prefunding.alert.tier1.pct")).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT rejects a non-numeric NUMBER value 400 with no write or audit")
    void putRejectsNonNumeric() {
        assertThatThrownBy(() -> service.upsert("wallet.fee.krw", "not-a-number", "bob", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        // unchanged + no audit row
        assertThat(repository.findById("wallet.fee.krw").orElseThrow().getValue()).isEqualTo("500");
        assertThat(auditCount("wallet.fee.krw")).isZero();
    }

    @Test
    @DisplayName("PUT on an unknown key creates a STRING-typed row")
    void putCreatesUnknownKey() {
        PlatformSettingView created = service.upsert("feature.flag.newui", "on", "carol", null);
        assertThat(created.valueType()).isEqualTo("STRING");
        assertThat(created.value()).isEqualTo("on");
        assertThat(auditCount("feature.flag.newui")).isEqualTo(1);
    }
}
