package com.gme.pay.bff.alert.paging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * The one place the "Redis is down" policy for paging is decided — and it is <b>deliberately the
 * opposite of the api-gateway's</b>.
 *
 * <p>The gateway's rate limit and replay nonce fail <em>closed</em> when their store is unavailable,
 * because those controls authorise traffic and an unavailable authorisation must not become a
 * permission. This control authorises nothing. Its only job is to stop a re-firing sweep from
 * storming a pager, so its failure mode must be <b>noisier, not quieter</b>:
 *
 * <ul>
 *   <li>Failing closed here would mean <b>suppressing pages while Redis is down</b> — an alert
 *       nobody hears, during an incident, which is the exact scenario the pager exists for. A
 *       missed page is not recoverable; a duplicate page is an annoyance.</li>
 *   <li>So a store error falls back to the <b>per-JVM</b> cooldown: dedupe degrades from
 *       fleet-wide to per-replica (at worst N pages instead of 1) rather than disappearing, and
 *       nothing is silenced.</li>
 * </ul>
 *
 * <p>This is not configurable, and that is the decision rather than an omission: there is no
 * operational position from which "silence the pager when its dedupe cache is unreachable" is the
 * right answer, so offering it as a flag would only be a way to get it wrong. Every failover is
 * logged at {@code WARN} so the degradation is visible rather than inferred from duplicate pages.
 *
 * <p>{@link #release} failures are logged and swallowed: failing to release only re-imposes the
 * cooldown that would have applied anyway, and throwing from it would turn a failed page into an
 * exception on the consume path.
 */
public class FailoverPagingCooldown implements PagingCooldown {

    private static final Logger log = LoggerFactory.getLogger(FailoverPagingCooldown.class);

    private final PagingCooldown shared;
    private final PagingCooldown local;

    public FailoverPagingCooldown(PagingCooldown shared, PagingCooldown local) {
        this.shared = Objects.requireNonNull(shared, "shared");
        this.local = Objects.requireNonNull(local, "local");
    }

    @Override
    public boolean tryClaim(String key, Duration window) {
        try {
            return shared.tryClaim(key, window);
        } catch (RuntimeException e) {
            log.warn("shared paging cooldown unavailable for key={} — degrading to the per-JVM "
                    + "cooldown (dedupe becomes per-replica; pages are NOT suppressed): {}",
                    key, e.toString());
            return local.tryClaim(key, window);
        }
    }

    @Override
    public void release(String key) {
        try {
            shared.release(key);
        } catch (RuntimeException e) {
            log.warn("shared paging cooldown release failed for key={} (the key will simply "
                    + "expire): {}", key, e.toString());
        }
        local.release(key);
    }
}
