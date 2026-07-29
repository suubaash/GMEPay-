package com.gme.pay.bff.client;

import com.gme.pay.contracts.PartnerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a partner BUSINESS CODE to config-registry's NUMERIC surrogate id.
 *
 * <h2>Why this exists (gap register T1-3)</h2>
 *
 * <p>The Partner Portal identifies a partner by its business code: the {@code {partnerId}} path
 * segment of {@code /v1/portal/{partnerId}/**} is authorized against the access token's
 * {@code partner_id} claim, and Keycloak seeds that claim with a <b>seeded partner code</b>
 * ({@code "GMEREMIT"}, {@code "SENDMN"} — see {@code docker/keycloak/README.md}).
 *
 * <p>Three of the four upstreams the portal reads key the partner by config-registry's
 * {@code BIGINT} surrogate instead:
 *
 * <ul>
 *   <li>auth-identity — {@code GET /internal/auth/keys?partnerId={long}&environment=}</li>
 *   <li>notification-webhook — {@code GET /v1/webhook-configs?partnerId={long}}</li>
 *   <li>transaction-mgmt — {@code GET /v1/transactions?partnerId={long}}</li>
 * </ul>
 *
 * <p>Nothing bridged the two, so every one of those reads was unreachable for a real partner:
 * the API Keys and Webhooks pages had no rest client at all, and
 * {@link com.gme.pay.bff.client.rest.RestTransactionMgmtClient} <em>correctly</em> failed closed on
 * a non-numeric partner id (returning an empty page rather than an unscoped query), which is why a
 * real partner's Transactions page and CSV statement came back empty while
 * {@code partner_test_001..003} appeared to work. This directory is the missing bridge: ONE read of
 * {@code GET /v1/partners/{code}} whose {@link PartnerView#id()} is the join key everything else
 * needs.
 *
 * <h2>Fail closed</h2>
 *
 * <p>{@link #numericIdOf(String)} returns an EMPTY optional whenever the code cannot be resolved —
 * unknown partner, config-registry unreachable, or a registry row whose {@code id} is null. Callers
 * must treat that as "no data" and never fall back to an unfiltered upstream query; an unresolved
 * code must not widen a partner-scoped read into a cross-partner one.
 *
 * <p>A code that is ALREADY numeric is passed through unchanged without touching config-registry,
 * so callers that legitimately hold a surrogate id (the Admin surface) keep working.
 *
 * <h2>Caching</h2>
 *
 * <p>The code -&gt; id mapping is immutable for the life of a partner ({@code partner_code} is
 * frozen once {@code go_live_at} is stamped — ADR-011 / {@code PartnerImmutabilityGuard}), so
 * successful resolutions are cached indefinitely. FAILURES are cached only briefly
 * ({@link #NEGATIVE_TTL}) so a partner activated moments ago, or a config-registry restart, is
 * picked up without bouncing the BFF — while a hot loop of bad codes still cannot hammer the
 * registry.
 */
@Component
public class PartnerDirectory {

    private static final Logger log = LoggerFactory.getLogger(PartnerDirectory.class);

    /** How long an UNRESOLVED code is remembered before config-registry is asked again. */
    static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    private final ConfigRegistryClient configRegistry;

    /** code -> surrogate id, for codes that resolved. Immutable facts, cached for the JVM's life. */
    private final Map<String, Long> resolved = new ConcurrentHashMap<>();

    /** code -> instant the resolution last FAILED, so retries are throttled but not blocked. */
    private final Map<String, Instant> failedAt = new ConcurrentHashMap<>();

    public PartnerDirectory(ConfigRegistryClient configRegistry) {
        this.configRegistry = Objects.requireNonNull(configRegistry, "configRegistry");
    }

    /**
     * config-registry's numeric surrogate for {@code partnerCode}, or {@link Optional#empty()} when
     * it cannot be resolved. Callers MUST fail closed on empty (see class javadoc).
     *
     * <p>A blank code yields empty; an already-numeric code is returned as-is.
     */
    public Optional<Long> numericIdOf(String partnerCode) {
        if (partnerCode == null || partnerCode.isBlank()) {
            return Optional.empty();
        }
        String code = partnerCode.trim();

        // Already a surrogate id (the Admin surface passes these) — no registry round-trip.
        Long asNumber = parseLongOrNull(code);
        if (asNumber != null) {
            return Optional.of(asNumber);
        }

        Long cached = resolved.get(code);
        if (cached != null) {
            return Optional.of(cached);
        }
        Instant lastFailure = failedAt.get(code);
        if (lastFailure != null && Instant.now().isBefore(lastFailure.plus(NEGATIVE_TTL))) {
            // Recently unresolvable — stay closed without re-asking on every request.
            return Optional.empty();
        }

        PartnerView view = configRegistry.getPartnerView(code);
        if (view == null || view.id() == null) {
            failedAt.put(code, Instant.now());
            log.warn("partner code '{}' does not resolve to a config-registry surrogate id "
                    + "({}) — partner-scoped upstream reads keyed by the numeric id will return "
                    + "NO DATA for it (failing closed rather than querying unscoped)",
                    code, view == null ? "no partner row / registry unreachable" : "row has null id");
            return Optional.empty();
        }
        resolved.put(code, view.id());
        failedAt.remove(code);
        return Optional.of(view.id());
    }

    /**
     * The full canonical partner row for {@code partnerCode}, or {@code null} when unknown /
     * unreachable. Not cached — unlike the surrogate id, the row's mutable fields (status,
     * settlement policy, {@code goLiveAt}) must be read fresh.
     */
    public PartnerView viewOf(String partnerCode) {
        if (partnerCode == null || partnerCode.isBlank()) {
            return null;
        }
        PartnerView view = configRegistry.getPartnerView(partnerCode.trim());
        if (view != null && view.id() != null) {
            // Opportunistically warm the id cache — this read already paid for it.
            resolved.put(partnerCode.trim(), view.id());
        }
        return view;
    }

    /** Drops every cached resolution. Test seam / operator escape hatch after a registry edit. */
    public void invalidate() {
        resolved.clear();
        failedAt.clear();
    }

    private static Long parseLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
