package com.gme.pay.ratefx.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed {@link QuoteTtlStore} (17.3-G01): key {@code rq:{quoteId}},
 * value = quote JSON, with EXPIRE = the quote TTL (a single atomic
 * {@code SET key value EX ttl}). Because the lock lives in Redis, an issued
 * quote survives a service restart for the remainder of its TTL; after the
 * TTL Redis drops the key and {@link #require(String)} maps the miss to the
 * deterministic {@link ErrorCode#RATE_QUOTE_EXPIRED} domain error.
 *
 * <p>Activated by {@code spring.data.redis.host} (see {@code QuoteStoreConfig});
 * without it the in-memory default stays.
 */
public final class RedisQuoteTtlStore implements QuoteTtlStore {

    /** Key prefix mandated by 17.3-G01: {@code rq:{quoteId}}. */
    public static final String KEY_PREFIX = "rq:";

    private final StringRedisTemplate redis;
    // Private mapper: the stored JSON layout is this store's contract and must not
    // drift with web-layer ObjectMapper customizations. Money stays a decimal string
    // via @JsonFormat on StoredQuote; instants are written ISO-8601.
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public RedisQuoteTtlStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(StoredQuote quote, Duration ttl) {
        QuoteTtlStore.requirePositive(ttl);
        redis.opsForValue().set(key(quote.quoteId()), toJson(quote), ttl);
    }

    @Override
    public Optional<StoredQuote> find(String quoteId) {
        String json = redis.opsForValue().get(key(quoteId));
        return json == null ? Optional.empty() : Optional.of(fromJson(json));
    }

    @Override
    public void remove(String quoteId) {
        redis.delete(key(quoteId));
    }

    private static String key(String quoteId) {
        return KEY_PREFIX + quoteId;
    }

    private String toJson(StoredQuote quote) {
        try {
            return mapper.writeValueAsString(quote);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "failed to serialize quote " + quote.quoteId() + ": " + e.getOriginalMessage());
        }
    }

    private StoredQuote fromJson(String json) {
        try {
            return mapper.readValue(json, StoredQuote.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "corrupt quote payload in Redis: " + e.getOriginalMessage());
        }
    }
}
