package com.gme.pay.registry.commercial;

import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared validation / normalisation helpers for the four Slice-6 commercial
 * services. The per-field rules mirror the conventions every earlier slice
 * service enforces privately ({@code PrefundingConfigService.validateMoney}
 * etc.); shared here because the four services land as one wizard step and
 * must reject with byte-identical message shapes.
 *
 * <h2>Numeric envelopes ({@code docs/MONEY_CONVENTION.md})</h2>
 *
 * <ul>
 *   <li>MONEY — NUMERIC(19,4): at most 4 decimal places, at most 15 integer
 *       digits, {@link BigDecimal} in major USD units.</li>
 *   <li>BPS — NUMERIC(7,4): at most 4 decimal places, at most 3 integer
 *       digits (&le; 999.9999 basis points).</li>
 * </ul>
 */
final class CommercialValidation {

    /** NUMERIC(19,4): at most 4 decimal places, at most 15 integer digits. */
    static final int MONEY_SCALE = 4;
    static final int MONEY_MAX_INTEGER_DIGITS = 15;

    /** NUMERIC(7,4): at most 4 decimal places, at most 3 integer digits. */
    static final int BPS_MAX_INTEGER_DIGITS = 3;

    private CommercialValidation() {
        // static utility
    }

    /** Resolve the CURRENT partner row by business code, 404 when unknown. */
    static PartnerEntity requirePartner(PartnerRepository partnerRepository, String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    /**
     * 409 unless the partner is still ONBOARDING — post-activation commercial
     * changes ride the change_request approval flow with the Slice 8 FSM,
     * same gate as every other step service.
     */
    static void requireOnboarding(PartnerEntity partner, String partnerCode, String step) {
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", " + step + " edits are only permitted while ONBOARDING");
        }
    }

    /**
     * One money field against the MONEY envelope: NUMERIC(19,4) shape and the
     * sign rule (strictly positive vs non-negative).
     */
    static void validateMoney(String field, BigDecimal value, boolean strictlyPositive) {
        if (value == null) {
            return;
        }
        if (strictlyPositive && value.signum() <= 0) {
            throw badRequest(field + " must be greater than 0, was: " + value.toPlainString());
        }
        if (!strictlyPositive && value.signum() < 0) {
            throw badRequest(field + " must not be negative, was: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw badRequest(field + " must have at most " + MONEY_SCALE
                    + " decimal places (NUMERIC(19,4)), was: " + value.toPlainString());
        }
        if (value.precision() - value.scale() > MONEY_MAX_INTEGER_DIGITS) {
            throw badRequest(field + " exceeds NUMERIC(19,4) (at most "
                    + MONEY_MAX_INTEGER_DIGITS + " integer digits), was: "
                    + value.toPlainString());
        }
    }

    /** One basis-points field against the NUMERIC(7,4) envelope; non-negative. */
    static void validateBps(String field, BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            throw badRequest(field + " must not be negative, was: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw badRequest(field + " must have at most " + MONEY_SCALE
                    + " decimal places (NUMERIC(7,4)), was: " + value.toPlainString());
        }
        if (value.precision() - value.scale() > BPS_MAX_INTEGER_DIGITS) {
            throw badRequest(field + " exceeds NUMERIC(7,4) (at most "
                    + BPS_MAX_INTEGER_DIGITS + " integer digits / 999.9999 bps), was: "
                    + value.toPlainString());
        }
    }

    /**
     * Normalise an accepted money/bps value to scale 4 so the persisted
     * NUMERIC equals the in-memory value on both engines and the
     * {@link CommercialJson} audit bytes are deterministic. Values arrive
     * pre-validated (&le; 4 dp), so the setScale never rounds.
     */
    static BigDecimal normalizeScale4(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE);
    }

    static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
