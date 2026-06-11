package com.gme.pay.registry.partner;

import com.gme.pay.domain.Partner;
import com.gme.pay.registry.cache.ConfigCache;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registry-side accessor for {@link Partner}. Backed by {@link PartnerRepository}
 * (PostgreSQL in production, H2 in PostgreSQL mode for unit slices). Converts
 * between the JPA {@link PartnerEntity} and the immutable domain {@link Partner}
 * record at the persistence boundary, since records cannot be JPA entities.
 *
 * <p>Hot-path reads ({@link #get}) are cache-aside through {@link ConfigCache}
 * (17.3-G03): try Redis, fall through to the DB on a miss and write back with a
 * TTL. Writes ({@link #save}, {@link #updateRoundingMode}) DEL the affected key
 * after the DB write, so readers observe updates well within 1s. Without a Redis
 * host configured the cache is a no-op and every call is a plain DB access.
 *
 * <p>Point-in-time reads ({@link #getEffectiveAt}) intentionally bypass the cache:
 * they are audit/inspection queries keyed by an arbitrary instant, not the hot path.
 */
@Component
public class PartnerStore {

    private final PartnerRepository repository;
    private final ConfigCache cache;

    public PartnerStore(PartnerRepository repository, ConfigCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    /**
     * Insert or update the given partner and return the persisted form. Updates
     * mutate the existing row in place so persistence-layer attributes the domain
     * record does not carry (the effective-dating window, created_at) survive.
     * The cache entry is evicted after the DB write (cache invalidation on write).
     */
    public Partner save(Partner partner) {
        PartnerEntity entity = repository.findById(partner.partnerId())
                .map(existing -> {
                    existing.setType(partner.type());
                    existing.setSettlementCurrency(partner.settlementCurrency());
                    existing.setSettlementRoundingMode(partner.settlementRoundingMode());
                    return existing;
                })
                .orElseGet(() -> PartnerEntity.fromDomain(partner));
        PartnerEntity saved = repository.save(entity);
        cache.evict(cacheKey(partner.partnerId()));
        return saved.toDomain();
    }

    /**
     * Retrieve the partner by id (cache-aside: Redis first, then DB with write-back).
     * @throws ResponseStatusException with 404 when no row matches.
     */
    public Partner get(String partnerId) {
        String key = cacheKey(partnerId);
        Optional<Partner> cached = cache.get(key, Partner.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        PartnerEntity entity = repository.findById(partnerId).orElse(null);
        if (entity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown partner: " + partnerId);
        }
        Partner partner = entity.toDomain();
        cache.put(key, partner);
        return partner;
    }

    /**
     * Retrieve the partner as effective at the given instant, honouring the half-open
     * {@code [effective_from, effective_to)} window (lower bound inclusive, upper
     * bound exclusive, NULL upper bound = open-ended). Bypasses the cache.
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} when no row is effective.
     */
    public Partner getEffectiveAt(String partnerId, Instant at) {
        return repository.findEffectiveAt(partnerId, at)
                .map(PartnerEntity::toDomain)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerId + "' effective at " + at));
    }

    /**
     * Update only the settlement rounding mode for the given partner; other fields
     * are preserved. The change is audit-relevant (see MONEY_CONVENTION.md).
     */
    public Partner updateRoundingMode(String partnerId, RoundingMode mode) {
        Partner current = get(partnerId);
        Partner updated = new Partner(
                current.partnerId(),
                current.type(),
                current.settlementCurrency(),
                mode);
        return save(updated);
    }

    /**
     * Snapshot of every partner currently in the registry. Used by the Admin UI's
     * partner list. Bypasses the cache: this is a low-frequency operator view and
     * each row would need its own cache lookup, so we go straight to the DB.
     */
    public List<Partner> listAll() {
        return repository.findAll().stream().map(PartnerEntity::toDomain).toList();
    }

    /** Cache key for a partner's current view. */
    static String cacheKey(String partnerId) {
        return "config:partner:" + partnerId;
    }
}
