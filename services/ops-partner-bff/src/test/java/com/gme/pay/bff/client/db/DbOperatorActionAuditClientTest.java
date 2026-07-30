package com.gme.pay.bff.client.db;

import com.gme.pay.bff.client.OperatorActionAuditClient;
import com.gme.pay.bff.client.stub.StubOperatorActionAuditClient;
import com.gme.pay.bff.persistence.OperatorActionAuditEntity;
import com.gme.pay.bff.persistence.OperatorActionAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * The durable operator-action audit trail, against a real H2 (PostgreSQL-mode) datasource with the full
 * Flyway set applied (so V002 is proved to apply and to produce the table).
 *
 * <p>Two properties matter, and the stub failed both:
 * <ol>
 *   <li><b>ids do not collide across replicas.</b> The stub's {@code AtomicLong} restarted at 1 in every
 *       JVM, so two replicas both minted {@code OA-1} for different actions. Here one database sequence
 *       does it.</li>
 *   <li><b>{@code recordDurable} can actually fail.</b> That is the whole point of the fail-closed
 *       contract "no money-affecting operator action without a durable audit record"; under the stub the
 *       method could not fail, so the contract was decorative.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DbOperatorActionAuditClientTest {

    @Autowired
    private OperatorActionAuditRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearTable() {
        jdbc.update("DELETE FROM operator_action_audit");
    }

    private DbOperatorActionAuditClient replica() {
        return new DbOperatorActionAuditClient(repository, txManager, Clock.systemUTC());
    }

    @Test
    @DisplayName("two replicas recording actions get DISTINCT ids (the collision, closed)")
    void idsDoNotCollideAcrossReplicas() {
        OperatorActionAuditClient a = replica();
        OperatorActionAuditClient b = replica();

        String idOnA = a.recordDurable("ops.pause", "system", "op-a", "incident").id();
        String idOnB = b.recordDurable("partner.suspend", "GMEREMIT", "op-b", "sanctions hit").id();

        assertThat(idOnA).isNotNull().startsWith("OA-");
        assertThat(idOnB).isNotNull().startsWith("OA-");
        assertThat(idOnA).as("one sequence, one id space").isNotEqualTo(idOnB);
    }

    @Test
    @DisplayName("the STUB mints the same OA-1 on both replicas (the defect this closes)")
    void stubIdsCollideAcrossReplicas() {
        OperatorActionAuditClient a = new StubOperatorActionAuditClient();
        OperatorActionAuditClient b = new StubOperatorActionAuditClient();

        String idOnA = a.recordDurable("ops.pause", "system", "op-a", null).id();
        String idOnB = b.recordDurable("partner.suspend", "GMEREMIT", "op-b", null).id();

        assertThat(idOnA)
                .as("two DIFFERENT operator actions, one audit id — this was live in every environment")
                .isEqualTo(idOnB)
                .isEqualTo("OA-1");
    }

    @Test
    @DisplayName("the record is durable: another replica reads it back, verbatim")
    void recordIsDurableAndReadableElsewhere() {
        replica().recordDurable("transaction.resolve", "TXN-7", "op-c", "customer called");

        List<OperatorActionAuditEntity> rows = repository.findRecent(PageRequest.of(0, 10));
        assertThat(rows).hasSize(1);
        OperatorActionAuditEntity row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("transaction.resolve");
        assertThat(row.getTarget()).isEqualTo("TXN-7");
        assertThat(row.getActor()).isEqualTo("op-c");
        assertThat(row.getReason()).isEqualTo("customer called");
        assertThat(row.getRecordedAt()).isNotNull();
    }

    @Test
    @DisplayName("nulls are recorded as 'unknown', never as a constraint violation")
    void nullsBecomeUnknownRatherThanAFailedWrite() {
        var rec = replica().recordDurable("ops.pause", null, null, null);

        assertThat(rec.id()).isNotNull();
        var row = repository.findRecent(PageRequest.of(0, 1)).get(0);
        assertThat(row.getTarget()).isEqualTo("unknown");
        assertThat(row.getActor()).isEqualTo("unknown");
        assertThat(row.getReason()).as("a reason is genuinely optional").isNull();
    }

    @Test
    @DisplayName("recordDurable FAILS CLOSED when the write fails — so the action is blocked")
    void recordDurableFailsClosed() {
        OperatorActionAuditRepository broken = mock(OperatorActionAuditRepository.class);
        when(broken.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));
        OperatorActionAuditClient client =
                new DbOperatorActionAuditClient(broken, txManager, Clock.systemUTC());

        assertThatThrownBy(() -> client.recordDurable("ops.pause", "system", "op-d", null))
                .as("no durable audit => no privileged action. The stub could NEVER reach here.")
                .isInstanceOf(OperatorActionAuditClient.AuditWriteException.class);
    }

    @Test
    @DisplayName("record() is best-effort: a failed write is logged, the action proceeds")
    void recordIsBestEffort() {
        OperatorActionAuditRepository broken = mock(OperatorActionAuditRepository.class);
        when(broken.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));
        OperatorActionAuditClient client =
                new DbOperatorActionAuditClient(broken, txManager, Clock.systemUTC());

        var rec = client.record("ops.status.read", "system", "op-e", null);

        assertThat(rec).isNotNull();
        assertThat(rec.id()).as("a null id is how the caller can tell it was not persisted").isNull();
        assertThat(rec.action()).isEqualTo("ops.status.read");
    }

    @Test
    @DisplayName("the operator_action_audit table really is created by Flyway")
    void migrationProducesTheExpectedColumns() {
        List<String> columns = jdbc.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns "
                        + "WHERE LOWER(table_name) = 'operator_action_audit'", String.class);
        assertThat(columns).contains("id", "action", "target", "actor", "reason", "recorded_at");
    }
}
