package com.gme.pay.txn.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.time.Clock;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The idempotency store-selection decision table, plus an assertion over the <b>shipped</b>
 * configuration.
 *
 * <p>The shipped-config assertion is the load-bearing one. The previous wiring selected Redis
 * whenever {@code spring.data.redis.host} was set, and the Helm ABI ConfigMap sets that on every
 * pod for api-gateway's benefit — so this service's duplicate-money-transaction control was chosen
 * by an environment variable belonging to a different service. A test that pins what ships is the
 * only thing that stops that class of accident recurring.
 */
class IdempotencyConfigTest {

    private final IdempotencyConfig config = new IdempotencyConfig();

    @Test
    @DisplayName("default (property absent) is the durable shared table")
    void defaultIsDatabase() {
        assertThat(IdempotencyConfig.useDatabase(new MockEnvironment()))
                .as("absent must mean the safe store, not the per-JVM one")
                .isTrue();
    }

    @Test
    @DisplayName("memory is legal and explicit — unit slices need it")
    void memoryIsSelectable() {
        assertThat(IdempotencyConfig.useDatabase(env("memory"))).isFalse();
        assertThat(IdempotencyConfig.useDatabase(env(" MEMORY "))).isFalse();
    }

    @Test
    @DisplayName("an unrecognised value REFUSES TO START")
    void unknownValueRefusesToStart() {
        for (String typo : new String[] {"redis", "database", "postgres", "true", ""}) {
            assertThatThrownBy(() -> IdempotencyConfig.useDatabase(env(typo)))
                    .as("'%s' must not resolve to a store", typo)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction-mgmt refuses to start")
                    .hasMessageContaining("money path");
        }
    }

    @Test
    @DisplayName("'redis' is specifically NOT a value any more — the cache is gone, not hidden")
    void redisIsNoLongerAnOption() {
        assertThatThrownBy(() -> IdempotencyConfig.useDatabase(env("redis")))
                .as("a Redis store with no AOF and no replication silently reopened the 24h "
                        + "window on every restart; leaving the name accepted would invite it back")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("exactly one IdempotencyStore is selected, and it is the one configured")
    void selectionProducesTheConfiguredStore() {
        JdbcIdempotencyStore dbStore =
                new JdbcIdempotencyStore(mock(JdbcTemplate.class),
                        IdempotencyConfig.IDEMPOTENCY_TTL, Clock.systemUTC());

        assertThat(config.idempotencyStore(new MockEnvironment(), dbStore)).isSameAs(dbStore);
        assertThat(config.idempotencyStore(env("memory"), dbStore))
                .isInstanceOf(InMemoryIdempotencyStore.class);
    }

    @Test
    @DisplayName("the shipped application.properties selects db and no longer mentions a Redis host")
    void shippedConfigSelectsTheDurableStore() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));

        assertThat(shipped.getProperty(IdempotencyConfig.STORE_PROPERTY))
                .as("stated explicitly rather than left to a code default, because the wrong "
                        + "value here is a duplicate money transaction")
                .isEqualTo(IdempotencyConfig.DB);
        assertThat(shipped.getProperty("spring.data.redis.host"))
                .as("this service must not read a Redis host at all any more")
                .isNull();
        assertThat(shipped.getProperty("gmepay.idempotency.retention-sweep-ms"))
                .as("a table has no TTL; the sweep interval must ship")
                .isNotNull();
    }

    @Test
    @DisplayName("the 24h TTL is unchanged")
    void ttlUnchanged() {
        assertThat(IdempotencyConfig.IDEMPOTENCY_TTL.toHours()).isEqualTo(24);
    }

    private static MockEnvironment env(String store) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(IdempotencyConfig.STORE_PROPERTY, store);
        return environment;
    }
}
