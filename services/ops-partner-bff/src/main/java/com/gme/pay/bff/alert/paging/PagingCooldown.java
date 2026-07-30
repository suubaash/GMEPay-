package com.gme.pay.bff.alert.paging;

import java.time.Duration;

/**
 * The paging dedupe cooldown (port). One key — {@code alertType|subjectRef} — may page a human at
 * most once per window.
 *
 * <p><b>Why this is a port at all.</b> The cooldown was a {@code ConcurrentHashMap} inside
 * {@link OpsPagingDispatcher}, so with N BFF replicas the same alert could page N times: the
 * escalation sweep runs on every replica, and even on the consume path a re-fired alert routed to a
 * different replica than last time finds an empty cooldown. Deduping a pager across replicas needs
 * shared state, which is what {@link RedisPagingCooldown} provides.
 *
 * <h2>Claim, then release on failure — not check-then-set</h2>
 * {@link #tryClaim} is atomic. Two replicas racing on the same key produce exactly one claim, so
 * exactly one page. A page that fails to deliver calls {@link #release} so the next tick (or
 * another replica) may retry — which preserves the pre-existing rule that <b>only a delivered page
 * opens the cooldown</b>, while making the decision race-free. Check-then-set could not: both
 * replicas would read "clear" and both would page.
 */
public interface PagingCooldown {

    /**
     * Atomically claim the right to page {@code key} for {@code window}.
     *
     * @return {@code true} if the caller may page (the cooldown was clear and is now held);
     *         {@code false} if a page for this key is already inside its cooldown window
     */
    boolean tryClaim(String key, Duration window);

    /**
     * Give the claim back, so a failed delivery does not silence the key for the whole window.
     * Must be safe to call for a key that is not held.
     */
    void release(String key);
}
