package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.domain.cpm.CpmSessionStorePort.PrefundReservation;
import com.gme.pay.qr.domain.cpm.CpmTokenGenerator.GeneratedToken;
import com.gme.pay.qr.exception.DuplicatePartnerTxnRefException;
import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Orchestrates the CPM generate flow (WBS 5.3-T07, qr-service half).
 *
 * <p>Steps: (1) reject a duplicate partner_txn_ref, (2) resolve the scheme for the country
 * ({@code NO_SCHEME_FOR_LOCATION} when none), (3) issue the prepare token via the issuance port
 * (scheme adapter in prod, local fallback now), (4) for OVERSEAS (outbound) RESERVE the prefunding
 * USD via {@link PrefundingReservationPort} (idempotencyKey = the CPM token id), (5) persist the
 * ISSUED session carrying the reservation handle.
 *
 * <p>The reservation is released on expiry/decline by {@link CpmTokenExpiryScheduler}. A 402 overdraw
 * from prefunding surfaces as {@link QRErrorCode#INSUFFICIENT_PREFUNDING} (→402). The reservation
 * client is {@code @ConditionalOnProperty}-gated with an in-memory fallback, so LOCAL and
 * no-prefunding runs work unchanged.
 *
 * <p>The authoritative scheme-issued prepare token (IR-qr-1) remains the scheme-adapter-gated bean
 * (external, KFTC-cert) — the local issuer is the fallback here.
 */
@Service
public class CpmGenerateService {

    private static final String DIRECTION_OVERSEAS = "outbound";

    private final CpmSchemeResolver schemeResolver;
    private final CpmTokenGenerator tokenGenerator;
    private final CpmSessionStorePort sessionStore;
    private final PrefundingReservationPort prefundingReservation;

    public CpmGenerateService(CpmSchemeResolver schemeResolver,
                              CpmTokenGenerator tokenGenerator,
                              CpmSessionStorePort sessionStore,
                              PrefundingReservationPort prefundingReservation) {
        this.schemeResolver = schemeResolver;
        this.tokenGenerator = tokenGenerator;
        this.sessionStore = sessionStore;
        this.prefundingReservation = prefundingReservation;
    }

    /**
     * Run the generate flow and persist the resulting session.
     *
     * @param schemeIdHint     optional partner-supplied scheme id
     * @param direction        transaction direction (already enum-validated upstream)
     * @param customerRef      hashed customer identifier
     * @param partnerTxnRef    partner's transaction reference (must be unique)
     * @param countryCode      ISO 3166-1 alpha-2 country code
     * @param prefundReserveUsd OVERSEAS reservation amount in USD (nullable)
     * @param partnerId        partner whose prefunding balance backs an OVERSEAS reservation (nullable)
     * @return the issued CPM token
     */
    public CpmToken createSession(String schemeIdHint, String direction, String customerRef,
                                  String partnerTxnRef, String countryCode,
                                  BigDecimal prefundReserveUsd, Long partnerId) {
        if (sessionStore.existsByPartnerTxnRef(partnerTxnRef)) {
            throw new DuplicatePartnerTxnRefException(partnerTxnRef);
        }

        String schemeId = schemeResolver.resolve(countryCode, schemeIdHint);

        GeneratedToken generated =
                tokenGenerator.generate(schemeId, partnerTxnRef, customerRef, countryCode);
        CpmToken token = generated.token();

        PrefundReservation reservation =
                reserveIfOverseas(direction, partnerId, prefundReserveUsd, token);

        sessionStore.save(token, direction, countryCode, customerRef,
                generated.schemeIssued(), reservation);

        return token;
    }

    /**
     * Reserve prefunding for OVERSEAS (outbound) issuance. The CPM token id is the idempotency key,
     * so a retried generate of the same token cannot double-hold. Returns {@code null} (no hold) for
     * non-OVERSEAS directions or when no partner/amount was supplied.
     */
    private PrefundReservation reserveIfOverseas(String direction, Long partnerId,
                                                 BigDecimal amountUsd, CpmToken token) {
        if (!DIRECTION_OVERSEAS.equalsIgnoreCase(direction) || partnerId == null) {
            return null;
        }
        if (amountUsd == null || amountUsd.signum() <= 0) {
            throw new QRParseException(QRErrorCode.INSUFFICIENT_PREFUNDING,
                    "OVERSEAS CPM issuance requires a positive prefund_reserve_usd");
        }
        PrefundingReservationPort.Reservation r = prefundingReservation.reserve(
                partnerId, amountUsd, token.cpmTokenId(), token.partnerTxnRef());
        return new PrefundReservation(partnerId, r.reservationId(), r.reservedUsd());
    }
}
