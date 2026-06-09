package com.gme.pay.registry.partner;

import com.gme.pay.domain.Partner;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Registry-side accessor for {@link Partner}. Backed by {@link PartnerRepository}
 * (PostgreSQL in production, H2 in PostgreSQL mode for tests). Converts between
 * the JPA {@link PartnerEntity} and the immutable domain {@link Partner} record
 * at the persistence boundary, since records cannot be JPA entities.
 *
 * <p>The public API (save / get / updateRoundingMode) is preserved verbatim from
 * the prior in-memory implementation so existing controllers and tests continue
 * to compile and behave the same way; the storage backend is the only thing
 * that has changed.
 */
@Component
public class PartnerStore {

    private final PartnerRepository repository;

    public PartnerStore(PartnerRepository repository) {
        this.repository = repository;
    }

    /** Insert or update the given partner and return the persisted form. */
    public Partner save(Partner partner) {
        PartnerEntity saved = repository.save(PartnerEntity.fromDomain(partner));
        return saved.toDomain();
    }

    /**
     * Retrieve the partner by id.
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} when no row matches.
     */
    public Partner get(String partnerId) {
        PartnerEntity entity = repository.findById(partnerId).orElse(null);
        if (entity == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "unknown partner: " + partnerId);
        }
        return entity.toDomain();
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
}
