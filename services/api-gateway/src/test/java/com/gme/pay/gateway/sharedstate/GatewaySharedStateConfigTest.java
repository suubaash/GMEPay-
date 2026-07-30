package com.gme.pay.gateway.sharedstate;

import com.gme.pay.gateway.ratelimit.InMemoryRateLimitStore;
import com.gme.pay.gateway.ratelimit.RateLimitStore;
import com.gme.pay.gateway.ratelimit.RedisRateLimitStore;
import com.gme.pay.gateway.replay.InMemoryNonceStore;
import com.gme.pay.gateway.replay.NonceStore;
import com.gme.pay.gateway.replay.RedisNonceStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The store-selection decision table, and the two configurations that must <b>refuse to start</b>
 * rather than quietly resolve to a per-JVM store.
 *
 * <p>The refuse-to-start cases are the point of the class. A gateway that silently falls back to
 * {@code ConcurrentHashMap} when its Redis host is missing looks healthy, serves traffic, and
 * enforces N x the configured rate limit while accepting one replay per replica — a failure with
 * no symptom until someone replays a captured request. Same rule and same
 * {@code refuses to start} idiom T0-7 applied to {@code gateway.partner-credentials.source}.
 */
class GatewaySharedStateConfigTest {

    private final GatewaySharedStateConfig config = new GatewaySharedStateConfig();

    // ---------------------------------------------------------------- decision table

    @Test
    @DisplayName("auto + a Redis host ⇒ Redis (every deployed environment: compose and all four "
            + "Helm values export SPRING_DATA_REDIS_HOST)")
    void autoWithHostSelectsRedis() {
        assertThat(GatewaySharedStateConfig.useRedis(env("auto", "redis"))).isTrue();
        assertThat(GatewaySharedStateConfig.useRedis(env(null, "redis")))
                .as("auto is also the behaviour when the key is absent entirely").isTrue();
    }

    @Test
    @DisplayName("auto without a Redis host ⇒ memory (the laptop / unit-test posture)")
    void autoWithoutHostSelectsMemory() {
        assertThat(GatewaySharedStateConfig.useRedis(env("auto", null))).isFalse();
        assertThat(GatewaySharedStateConfig.useRedis(env("auto", "   ")))
                .as("a blank host is an unset host, not a hostname").isFalse();
    }

    @Test
    @DisplayName("memory is legal and explicit — a single-replica deployment is allowed to say so")
    void memoryIsAllowed() {
        assertThat(GatewaySharedStateConfig.useRedis(env("memory", "redis")))
                .as("an explicit choice outranks auto-detection, even with a Redis available")
                .isFalse();
    }

    @Test
    @DisplayName("case and whitespace do not change the selection")
    void toleratesCasing() {
        assertThat(GatewaySharedStateConfig.useRedis(env("  REDIS ", "redis"))).isTrue();
        assertThat(GatewaySharedStateConfig.useRedis(env("Memory", "redis"))).isFalse();
    }

    // ---------------------------------------------------------------- refuse to start

    @Test
    @DisplayName("store=redis with no host REFUSES TO START — it must not degrade to per-JVM")
    void redisWithoutHostRefusesToStart() {
        assertThatThrownBy(() -> GatewaySharedStateConfig.useRedis(env("redis", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-gateway refuses to start")
                .hasMessageContaining("SPRING_DATA_REDIS_HOST");
    }

    @Test
    @DisplayName("an unrecognised value REFUSES TO START — a typo must not pick a different store")
    void unknownValueRefusesToStart() {
        for (String typo : new String[] {"redis ", "rediss", "shared", "true", ""}) {
            String value = typo.trim();
            if (value.equals("redis")) {
                continue; // trimmed to the real value; covered above
            }
            assertThatThrownBy(() -> GatewaySharedStateConfig.useRedis(env(typo, "redis")))
                    .as("'%s' must not resolve to anything", typo)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("api-gateway refuses to start");
        }
    }

    // ---------------------------------------------------------------- wiring

    @Test
    @DisplayName("both stores follow the ONE switch — the half-shared configuration is unreachable")
    void oneSwitchGovernsBothStores() {
        ObjectProvider<ReactiveStringRedisTemplate> template =
                provider(mock(ReactiveStringRedisTemplate.class));
        InMemoryRateLimitStore localRl = new InMemoryRateLimitStore();
        InMemoryNonceStore localNonce = new InMemoryNonceStore();

        MockEnvironment withRedis = env("auto", "redis");
        assertThat(config.rateLimitStore(withRedis, template, localRl))
                .isInstanceOf(RedisRateLimitStore.class);
        assertThat(config.nonceStore(withRedis, template, localNonce))
                .isInstanceOf(RedisNonceStore.class);

        MockEnvironment withoutRedis = env("auto", null);
        assertThat(config.rateLimitStore(withoutRedis, template, localRl)).isSameAs(localRl);
        assertThat(config.nonceStore(withoutRedis, template, localNonce)).isSameAs(localNonce);
    }

    @Test
    @DisplayName("Redis selected but no template on the classpath REFUSES TO START")
    void missingTemplateRefusesToStart() {
        MockEnvironment withRedis = env("redis", "redis");
        assertThatThrownBy(() -> config.rateLimitStore(withRedis, provider(null),
                new InMemoryRateLimitStore()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-gateway refuses to start");
    }

    @Test
    @DisplayName("the local fallbacks are always registered — 'degrade' needs something to "
            + "degrade to")
    void localFallbacksAlwaysExist() {
        assertThat(config.localRateLimitStore()).isNotNull();
        assertThat(config.localNonceStore()).isNotNull();
    }

    // ---------------------------------------------------------------- shipped config

    @Test
    @DisplayName("the SHIPPED application.yml selects auto and declares the fail-closed postures")
    void shippedConfigIsTheSafeOne() throws IOException {
        String yml = shippedApplicationYml();

        assertThat(yml).as("the switch must be present, not implied by a default")
                .contains("shared-state:").contains("store: auto");
        assertThat(yml).as("T0-7's fail-closed rate-limit posture must survive the Redis move")
                .contains("fail-open: false").contains("on-store-error: deny");
        assertThat(yml).as("replay must ship REJECT")
                .contains("on-store-error: reject");
        assertThat(yml).as("no 'allow' posture may be shipped for replay protection")
                .doesNotContain("on-store-error: allow");
        assertThat(yml).contains("max-nonce-length:");
    }

    @Test
    @DisplayName("the shipped rate-limit caps are unchanged by this work")
    void shippedCapsUnchanged() throws IOException {
        String yml = shippedApplicationYml();
        assertThat(yml)
                .contains("global-per-second: 100")
                .contains("rates-per-second: 20")
                .contains("payments-per-second: 50")
                .contains("enabled: true");
    }

    // ---------------------------------------------------------------- helpers

    private static String shippedApplicationYml() throws IOException {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MockEnvironment env(String store, String redisHost) {
        MockEnvironment environment = new MockEnvironment();
        Stream.of(
                        store == null ? null
                                : Map.entry(GatewaySharedStateConfig.STORE_PROPERTY, store),
                        redisHost == null ? null
                                : Map.entry(GatewaySharedStateConfig.REDIS_HOST_PROPERTY, redisHost))
                .filter(Objects::nonNull)
                .forEach(e -> environment.setProperty(e.getKey(), e.getValue()));
        return environment;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveStringRedisTemplate> provider(
            ReactiveStringRedisTemplate value) {
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
