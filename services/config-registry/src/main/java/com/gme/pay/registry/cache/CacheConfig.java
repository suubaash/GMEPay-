package com.gme.pay.registry.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the {@link ConfigCache} implementation (ticket 17.3-G03):
 * <ul>
 *   <li>{@code spring.data.redis.host} set → {@link RedisConfigCache} with TTL from
 *       {@code registry.cache.ttl} (default 10m);</li>
 *   <li>otherwise → {@link NoOpConfigCache}, a pure pass-through, so the service
 *       runs unchanged without a Redis instance (local dev, unit slices).</li>
 * </ul>
 *
 * <p>Bean methods are declared redis-first so the {@link ConditionalOnMissingBean}
 * fallback is evaluated after the conditional Redis bean within this class.
 */
@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(name = "spring.data.redis.host")
    public ConfigCache redisConfigCache(StringRedisTemplate redisTemplate,
                                        ObjectMapper objectMapper,
                                        Environment environment) {
        Duration ttl = environment.getProperty("registry.cache.ttl", Duration.class, Duration.ofMinutes(10));
        return new RedisConfigCache(redisTemplate, objectMapper, ttl);
    }

    @Bean
    @ConditionalOnMissingBean(ConfigCache.class)
    public ConfigCache noOpConfigCache() {
        return new NoOpConfigCache();
    }
}
