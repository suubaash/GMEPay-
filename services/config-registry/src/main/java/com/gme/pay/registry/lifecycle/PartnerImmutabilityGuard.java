package com.gme.pay.registry.lifecycle;

import com.gme.pay.domain.Partner;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.registry.persistence.PartnerEntity;
import java.util.Objects;

/**
 * Slice 8 post-activation immutability (ADR-011): once a partner transitions
 * to LIVE for the FIRST time ({@code partners.go_live_at} stamped by the
 * lifecycle applier and never reset), the identity-critical columns freeze:
 *
 * <ul>
 *   <li>{@code partner_code}</li>
 *   <li>{@code country_of_incorporation}</li>
 *   <li>{@code partner_type}</li>
 *   <li>{@code collection_ccy}</li>
 *   <li>{@code settle_a_ccy}</li>
 * </ul>
 *
 * <p>Any PATCH/PUT mutating one of them is rejected with
 * {@link ErrorCode#IMMUTABLE_AFTER_ACTIVATION} (HTTP 400). The lock marker is
 * {@code go_live_at != null} — NOT {@code status == LIVE} — so a suspended or
 * terminated partner stays locked (it has been live; downstream services and
 * the regulator narrative already reference these attributes).
 *
 * <h2>Enforcement points</h2>
 *
 * <ul>
 *   <li>{@link #checkFourFieldWrite}: inside {@code PartnerStore.save}, the
 *       canonical SCD-6 mutation path every four-field write funnels through
 *       (legacy POST, rounding-mode PUT, {@code PartnerChangeRequestApplier}).
 *       Guards {@code partner_type} and the settlement currency whose V016
 *       Expand-phase mirror feeds {@code collection_ccy} / {@code settle_a_ccy}.</li>
 *   <li>{@link #checkIdentityWrite}: in the Identity PATCH paths
 *       ({@code PartnerController.updateStep1}; the draft path is already
 *       hard-stopped by its ONBOARDING-only 409). Guards
 *       {@code country_of_incorporation}.</li>
 * </ul>
 *
 * <p>{@code partner_code} needs no runtime comparison: every write path is
 * keyed BY the code (URL path / aggregate id), so a "change" would surface as
 * a different partner, and POST on an existing code 409s.
 */
public final class PartnerImmutabilityGuard {

    private PartnerImmutabilityGuard() {
        // static guard
    }

    /** True when the partner has been LIVE at least once and the lock is engaged. */
    public static boolean isLocked(PartnerEntity current) {
        return current != null && current.getGoLiveAt() != null;
    }

    /**
     * Guard the four-field SCD-6 write path ({@code PartnerStore.save}).
     * {@code null} incoming values mean "field not carried by this payload"
     * and never trip the lock.
     *
     * @param prior    the current row about to be superseded.
     * @param incoming the domain record about to become the fresh current row.
     * @throws ApiException {@link ErrorCode#IMMUTABLE_AFTER_ACTIVATION} when the
     *         lock is engaged and the write changes a locked attribute.
     */
    public static void checkFourFieldWrite(PartnerEntity prior, Partner incoming) {
        if (!isLocked(prior) || incoming == null) {
            return;
        }
        if (incoming.type() != null && incoming.type() != prior.getType()) {
            throw locked(prior.getPartnerCode(), "partner_type",
                    String.valueOf(prior.getType()), String.valueOf(incoming.type()));
        }
        // settlement_currency is the V016 Expand-phase source that mirrors into
        // collection_ccy + settle_a_ccy on rows without a real split — changing
        // it post-activation would mutate the locked currency pair.
        if (incoming.settlementCurrency() != null
                && !incoming.settlementCurrency().equals(prior.getSettlementCurrency())) {
            throw locked(prior.getPartnerCode(), "settle_a_ccy/collection_ccy",
                    prior.getSettlementCurrency(), incoming.settlementCurrency());
        }
    }

    /**
     * Guard the Identity PATCH paths. Only {@code country_of_incorporation} is
     * lifecycle-locked among the Step-1 fields; the rest (addresses, tax ids,
     * LEI…) stay editable post-activation through the change-request flow.
     *
     * @throws ApiException {@link ErrorCode#IMMUTABLE_AFTER_ACTIVATION} when the
     *         lock is engaged and the payload changes the incorporation country.
     */
    public static void checkIdentityWrite(PartnerEntity current,
                                          String newCountryOfIncorporation) {
        if (!isLocked(current)) {
            return;
        }
        if (!Objects.equals(current.getCountryOfIncorporation(), newCountryOfIncorporation)) {
            throw locked(current.getPartnerCode(), "country_of_incorporation",
                    current.getCountryOfIncorporation(), newCountryOfIncorporation);
        }
    }

    private static ApiException locked(String partnerCode, String column,
                                       String fromValue, String toValue) {
        return new ApiException(ErrorCode.IMMUTABLE_AFTER_ACTIVATION,
                "partner '" + partnerCode + "' has been activated (go_live_at set); '"
                        + column + "' is immutable after activation (attempted "
                        + fromValue + " -> " + toValue + ")");
    }
}
