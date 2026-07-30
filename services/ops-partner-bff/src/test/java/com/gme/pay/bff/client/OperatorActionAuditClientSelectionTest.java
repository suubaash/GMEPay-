package com.gme.pay.bff.client;

import com.gme.pay.bff.client.db.DbOperatorActionAuditClient;
import com.gme.pay.bff.client.rest.RestOperatorActionAuditClient;
import com.gme.pay.bff.client.stub.StubOperatorActionAuditClient;
import com.gme.pay.bff.persistence.OperatorActionAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The {@code gmepay.operator-action-audit.client} decision table — the T1-1 defect class, closed.
 *
 * <p>Before this, {@link StubOperatorActionAuditClient} carried {@code matchIfMissing = true} and
 * {@code GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT} was set in <b>no</b> values file, no compose service and
 * no properties file — so an in-memory list minting per-JVM {@code OA-n} ids was the live audit trail
 * in every environment, and {@code recordDurable} (the fail-closed write that must BLOCK an unaudited
 * money-affecting action) could not fail.
 *
 * <p>The four rows that matter:
 * <ol>
 *   <li>no property → the <b>durable</b> client (the inversion);</li>
 *   <li>{@code stub} → the stub, and only when asked for by name;</li>
 *   <li>{@code rest} → the remote client (kept selectable, though its endpoint does not exist yet —
 *       which is why the default is {@code db} and not {@code rest});</li>
 *   <li>anything else → <b>no bean</b>, so a consumer that requires one fails context refresh and the
 *       service refuses to boot rather than silently degrading.</li>
 * </ol>
 */
class OperatorActionAuditClientSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class)
            .withUserConfiguration(DbOperatorActionAuditClient.class,
                    RestOperatorActionAuditClient.class,
                    StubOperatorActionAuditClient.class);

    @Test
    @DisplayName("NO property set → the durable DB client wins (the T1-1 inversion)")
    void defaultIsTheDurableClient() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OperatorActionAuditClient.class);
            assertThat(ctx.getBean(OperatorActionAuditClient.class))
                    .isInstanceOf(DbOperatorActionAuditClient.class);
            assertThat(ctx).doesNotHaveBean(StubOperatorActionAuditClient.class);
        });
    }

    @Test
    @DisplayName("db (any casing) → the durable DB client")
    void dbSelectsTheDurableClient() {
        for (String v : new String[]{"db", "DB", "Db"}) {
            runner.withPropertyValues("gmepay.operator-action-audit.client=" + v).run(ctx ->
                    assertThat(ctx.getBean(OperatorActionAuditClient.class))
                            .isInstanceOf(DbOperatorActionAuditClient.class));
        }
    }

    @Test
    @DisplayName("stub → the stub, and it is now the ONLY way to get it")
    void stubIsOptInOnly() {
        runner.withPropertyValues("gmepay.operator-action-audit.client=stub").run(ctx -> {
            assertThat(ctx).hasSingleBean(OperatorActionAuditClient.class);
            assertThat(ctx.getBean(OperatorActionAuditClient.class))
                    .isInstanceOf(StubOperatorActionAuditClient.class);
            assertThat(ctx).doesNotHaveBean(DbOperatorActionAuditClient.class);
        });
    }

    @Test
    @DisplayName("rest → the remote client (still selectable for the day its endpoint ships)")
    void restIsStillSelectable() {
        runner.withPropertyValues("gmepay.operator-action-audit.client=rest").run(ctx ->
                assertThat(ctx.getBean(OperatorActionAuditClient.class))
                        .isInstanceOf(RestOperatorActionAuditClient.class));
    }

    @Test
    @DisplayName("an unrecognised value leaves NO bean, so a consumer refuses to start")
    void unknownValueRefusesToBoot() {
        for (String v : new String[]{"mock", "http", "jdbc", "memory", "none", ""}) {
            runner.withPropertyValues("gmepay.operator-action-audit.client=" + v).run(ctx -> {
                assertThat(ctx).as("value '%s'", v).doesNotHaveBean(OperatorActionAuditClient.class);
            });
            // And what that costs a real consumer: OpsAlertAckController takes the client as a
            // required constructor argument, so the context cannot refresh.
            runner.withPropertyValues("gmepay.operator-action-audit.client=" + v)
                    .withUserConfiguration(NeedsAudit.class)
                    .run(ctx -> assertThat(ctx).as("value '%s'", v).hasFailed());
        }
    }

    @Test
    @DisplayName("exactly one implementation is ever registered — no @Primary tie-break decides audit")
    void implementationsAreMutuallyExclusive() {
        for (String v : new String[]{"db", "stub", "rest"}) {
            runner.withPropertyValues("gmepay.operator-action-audit.client=" + v)
                    .run(ctx -> assertThat(ctx.getBeansOfType(OperatorActionAuditClient.class))
                            .as("value '%s'", v).hasSize(1));
        }
    }

    @Test
    @DisplayName("the shipped application.properties selects db, not stub")
    void shippedConfigSelectsTheDurableClient() {
        String shipped = read("/application.properties");
        assertThat(shipped)
                .contains("gmepay.operator-action-audit.client="
                        + "${GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT:db}");
        assertThat(shipped)
                .as("the old default is the defect; it must not come back")
                .doesNotContain("GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT:stub");
    }

    @Configuration
    static class Collaborators {
        @Bean
        OperatorActionAuditRepository operatorActionAuditRepository() {
            return mock(OperatorActionAuditRepository.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }

    /** Stands in for OpsAlertAckController: a bean that cannot be built without an audit client. */
    @Configuration
    static class NeedsAudit {
        @Bean
        String auditConsumer(OperatorActionAuditClient audit) {
            return audit.toString();
        }
    }

    private static String read(String resource) {
        try (var in = OperatorActionAuditClientSelectionTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
