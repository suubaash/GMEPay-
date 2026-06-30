package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.domain.cpm.CpmTokenGenerator.GeneratedToken;
import com.gme.pay.qr.exception.DuplicatePartnerTxnRefException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the CPM generate flow (WBS 5.3-T07, qr-service half).
 *
 * <p>Steps: (1) reject a duplicate partner_txn_ref, (2) resolve the scheme for the country
 * ({@code NO_SCHEME_FOR_LOCATION} when none), (3) issue the prepare token via the issuance port
 * (scheme adapter in prod, local fallback now), (4) persist the ISSUED session.
 *
 * <p>Prefunding reservation (OVERSEAS) and authoritative scheme selection live in OTHER services
 * and are FROZEN — see INTEGRATION REQUESTS #1 and #2. They are intentionally not invoked here.
 */
@Service
public class CpmGenerateService {

    private final CpmSchemeResolver schemeResolver;
    private final CpmTokenGenerator tokenGenerator;
    private final CpmSessionStorePort sessionStore;

    public CpmGenerateService(CpmSchemeResolver schemeResolver,
                              CpmTokenGenerator tokenGenerator,
                              CpmSessionStorePort sessionStore) {
        this.schemeResolver = schemeResolver;
        this.tokenGenerator = tokenGenerator;
        this.sessionStore = sessionStore;
    }

    /**
     * Run the generate flow and persist the resulting session.
     *
     * @param schemeIdHint  optional partner-supplied scheme id
     * @param direction     transaction direction (already enum-validated upstream)
     * @param customerRef   hashed customer identifier
     * @param partnerTxnRef partner's transaction reference (must be unique)
     * @param countryCode   ISO 3166-1 alpha-2 country code
     * @return the issued CPM token
     */
    public CpmToken createSession(String schemeIdHint, String direction, String customerRef,
                                  String partnerTxnRef, String countryCode) {
        if (sessionStore.existsByPartnerTxnRef(partnerTxnRef)) {
            throw new DuplicatePartnerTxnRefException(partnerTxnRef);
        }

        String schemeId = schemeResolver.resolve(countryCode, schemeIdHint);

        GeneratedToken generated =
                tokenGenerator.generate(schemeId, partnerTxnRef, customerRef, countryCode);

        sessionStore.save(generated.token(), direction, countryCode, customerRef,
                generated.schemeIssued());

        return generated.token();
    }
}
