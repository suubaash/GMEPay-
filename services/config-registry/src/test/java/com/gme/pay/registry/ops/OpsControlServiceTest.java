package com.gme.pay.registry.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.registry.audit.AuditLogRepository;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Service slice test for the Ops kill-switch. Runs as a {@code @DataJpaTest}
 * against H2 (PostgreSQL mode) so Flyway applies the full V001..V038 chain,
 * including the V038 {@code ops_control} seed + {@code ops_suspension} table.
 *
 * <p>Asserts state transitions, status aggregation, idempotency, and that each
 * mutating action writes one hash-chained audit row.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OpsControlServiceTest.TestConfig.class, OpsControlService.class,
         AuditLogService.class, CacheConfig.class})
class OpsControlServiceTest {

    @Autowired
    private OpsControlService service;

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

    private long globalAuditCount() {
        return auditLogRepository.findChainByAggregate("ops-control", "global").size();
    }

    private long suspensionAuditCount(String type, String id) {
        return auditLogRepository.findChainByAggregate("ops-suspension", type + ":" + id).size();
    }

    @Test
    @DisplayName("fresh state is all-clear")
    void freshStateIsAllClear() {
        assertThat(service.status()).isEqualTo(OperationalStatusView.allClear());
    }

    @Test
    @DisplayName("pause sets systemPaused=true, resume clears it; each audited")
    void pauseThenResume() {
        OperationalStatusView paused = service.pause("scheme outage", "alice", "1.2.3.4");
        assertThat(paused.systemPaused()).isTrue();
        assertThat(paused.reason()).isEqualTo("scheme outage");
        assertThat(paused.since()).isNotNull();
        assertThat(globalAuditCount()).isEqualTo(1);

        OperationalStatusView resumed = service.resume("alice", "1.2.3.4");
        assertThat(resumed.systemPaused()).isFalse();
        assertThat(resumed).isEqualTo(OperationalStatusView.allClear());
        assertThat(globalAuditCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("pause is idempotent — no second audit row")
    void pauseIsIdempotent() {
        service.pause("x", "op", null);
        service.pause("x", "op", null);
        assertThat(globalAuditCount()).isEqualTo(1);
        assertThat(service.status().systemPaused()).isTrue();
    }

    @Test
    @DisplayName("maintenance on/off toggles the flag and audits each edge")
    void maintenanceToggle() {
        OperationalStatusView on = service.maintenance(true, "patch window", "op", null);
        assertThat(on.maintenanceMode()).isTrue();
        assertThat(on.systemPaused()).isFalse();
        assertThat(globalAuditCount()).isEqualTo(1);

        OperationalStatusView off = service.maintenance(false, null, "op", null);
        assertThat(off.maintenanceMode()).isFalse();
        assertThat(globalAuditCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("suspend PARTNER X puts X in suspendedPartners; unsuspend clears it")
    void suspendUnsuspendPartner() {
        OperationalStatusView afterSuspend =
                service.suspend("PARTNER", "GMEREMIT", "sanctions review", "op", null);
        assertThat(afterSuspend.suspendedPartners()).containsExactly("GMEREMIT");
        assertThat(afterSuspend.suspendedSchemes()).isEmpty();
        assertThat(afterSuspend.since()).isNotNull();
        assertThat(suspensionAuditCount("PARTNER", "GMEREMIT")).isEqualTo(1);

        OperationalStatusView afterUnsuspend =
                service.unsuspend("PARTNER", "GMEREMIT", "op", null);
        assertThat(afterUnsuspend.suspendedPartners()).isEmpty();
        assertThat(afterUnsuspend).isEqualTo(OperationalStatusView.allClear());
        assertThat(suspensionAuditCount("PARTNER", "GMEREMIT")).isEqualTo(2);
    }

    @Test
    @DisplayName("suspend is idempotent — second suspend of same entity writes no new audit")
    void suspendIsIdempotent() {
        service.suspend("SCHEME", "ZEROPAY", "r1", "op", null);
        service.suspend("SCHEME", "ZEROPAY", "r1", "op", null);
        assertThat(suspensionAuditCount("SCHEME", "ZEROPAY")).isEqualTo(1);
        assertThat(service.status().suspendedSchemes()).containsExactly("ZEROPAY");
    }

    @Test
    @DisplayName("status aggregates global flags + multiple suspensions across buckets")
    void statusAggregates() {
        service.pause("major incident", "op", null);
        service.suspend("PARTNER", "P1", "r", "op", null);
        service.suspend("SCHEME", "S1", "r", "op", null);
        service.suspend("ROUTE", "R1", "r", "op", null);

        OperationalStatusView s = service.status();
        assertThat(s.systemPaused()).isTrue();
        assertThat(s.suspendedPartners()).containsExactly("P1");
        assertThat(s.suspendedSchemes()).containsExactly("S1");
        assertThat(s.suspendedRoutes()).containsExactly("R1");
    }

    @Test
    @DisplayName("re-suspend after unsuspend reactivates and writes a fresh audit row")
    void reSuspendAfterUnsuspend() {
        service.suspend("ROUTE", "R9", "r1", "op", null);
        service.unsuspend("ROUTE", "R9", "op", null);
        service.suspend("ROUTE", "R9", "r2", "op", null);
        assertThat(service.status().suspendedRoutes()).containsExactly("R9");
        assertThat(suspensionAuditCount("ROUTE", "R9")).isEqualTo(3);
    }
}
