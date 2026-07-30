package com.gme.pay.bff.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code gmepay.ops.alerts.store} decision table. The point of this test is the <b>default</b> and
 * the <b>refuse-to-start</b> row: a store selector on the operator alert surface must not resolve a
 * typo into the weaker option, which is exactly how {@code StubOperatorActionAuditClient} became live
 * in every environment (T1-1's defect class) and how the idempotency store ended up chosen by an
 * unrelated environment variable.
 */
class OpsAlertStoreConfigTest {

    @Test
    @DisplayName("no property set → the DURABLE table (this is the inversion)")
    void defaultIsTheDurableStore() {
        assertThat(OpsAlertStoreConfig.useDatabase(new MockEnvironment())).isTrue();
    }

    @Test
    @DisplayName("db / DB / ' db ' all select the table (case- and whitespace-insensitive)")
    void dbIsForgivingAboutSpelling() {
        for (String v : new String[]{"db", "DB", " db ", "Db"}) {
            assertThat(OpsAlertStoreConfig.useDatabase(env(v)))
                    .as("value '%s'", v).isTrue();
        }
    }

    @Test
    @DisplayName("memory is legal but must be asked for explicitly")
    void memoryIsExplicitOnly() {
        assertThat(OpsAlertStoreConfig.useDatabase(env("memory"))).isFalse();
        assertThat(OpsAlertStoreConfig.useDatabase(env("MEMORY"))).isFalse();
    }

    @Test
    @DisplayName("an unrecognised value REFUSES TO START — including plausible near-misses")
    void unknownValuesRefuseToStart() {
        for (String v : new String[]{"redis", "jpa", "database", "inmemory", "", "  ", "true"}) {
            assertThatThrownBy(() -> OpsAlertStoreConfig.useDatabase(env(v)))
                    .as("value '%s'", v)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refuses to start")
                    .hasMessageContaining("gmepay.ops.alerts.store");
        }
    }

    @Test
    @DisplayName("redis is specifically NOT a value — the shape argument is in the migration")
    void redisIsNotAnOption() {
        assertThatThrownBy(() -> OpsAlertStoreConfig.useDatabase(env("redis")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("db over an in-memory H2 is still selected, so the WARN is the only signal")
    void dbOverInMemoryH2IsStillDb() {
        MockEnvironment e = new MockEnvironment();
        e.setProperty("spring.datasource.url", "jdbc:h2:mem:bffdb;MODE=PostgreSQL");
        // Deliberately NOT a refusal: the shipped image default IS this H2 so a laptop boots with no
        // database. What must not happen is silence, and OpsAlertStoreConfig logs a WARN naming the
        // N=1 ceiling. scripts/check_helm_chart_wiring.py is what stops it reaching a cluster.
        assertThat(OpsAlertStoreConfig.useDatabase(e)).isTrue();
    }

    @Test
    @DisplayName("the shipped application.properties selects db and declares the retention window")
    void shippedConfigIsTheDurableOne() {
        String shipped = read("/application.properties");
        assertThat(shipped).contains("gmepay.ops.alerts.store=${GMEPAY_OPS_ALERTS_STORE:db}");
        assertThat(shipped).contains("gmepay.ops.alerts.retention-days=");
        assertThat(shipped).contains("spring.flyway.enabled=true");
        assertThat(shipped).contains("spring.jpa.hibernate.ddl-auto=none");
        assertThat(shipped)
                .as("the H2 fallback must stay overridable by env, which is what compose and all "
                        + "four Helm values files set")
                .contains("spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:h2:mem:bffdb");
    }

    private static MockEnvironment env(String value) {
        MockEnvironment e = new MockEnvironment();
        e.setProperty(OpsAlertStoreConfig.STORE_PROPERTY, value);
        return e;
    }

    private static String read(String resource) {
        try (var in = OpsAlertStoreConfigTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
