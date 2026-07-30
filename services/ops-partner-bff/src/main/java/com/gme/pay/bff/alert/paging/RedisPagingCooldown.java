package com.gme.pay.bff.alert.paging;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis-backed {@link PagingCooldown} — one atomic {@code SET key 1 NX EX window} per claim.
 *
 * <p>This is what makes the BFF safe to run at N&gt;1 <em>as a pager</em>: the first replica to
 * claim a key holds it for the whole window, so a re-firing alert consumed on a different replica,
 * and the escalation sweep running on every replica, page a human once between them rather than
 * once each.
 *
 * <p><b>Errors are propagated, not swallowed here.</b> The failure posture is a policy decision and
 * it belongs in one place — {@link FailoverPagingCooldown} — because it is the <em>opposite</em> of
 * the api-gateway's security controls and that contrast needs to be visible where it is decided,
 * not buried in a client.
 */
public class RedisPagingCooldown implements PagingCooldown {

    /** Redis key namespace: {@code ops:page:cooldown:{alertType|subjectRef}}. */
    public static final String KEY_PREFIX = "ops:page:cooldown:";

    private static final String MARKER = "1";

    private final StringRedisTemplate redis;

    public RedisPagingCooldown(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public boolean tryClaim(String key, Duration window) {
        Boolean claimed = redis.opsForValue().setIfAbsent(KEY_PREFIX + key, MARKER, window);
        // An absent reply means the command produced no answer. For a *pager* the non-permissive
        // reading is the noisy one: "I do not know whether this was already paged" must resolve to
        // "page it", because a duplicate page is recoverable and a missed page is not.
        return !Boolean.FALSE.equals(claimed);
    }

    @Override
    public void release(String key) {
        redis.delete(KEY_PREFIX + key);
    }
}
