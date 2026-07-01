package com.gme.pay.router.resolve;

import java.util.List;

/**
 * Port: the authoritative {@code partner_scheme} read surface (config-registry
 * V022) that data-driven scheme-for-location resolution queries. Unlike
 * {@link com.gme.pay.domain.routing.PartnerSchemeResolver} (scheme-id strings
 * only), this returns the full {@link PartnerSchemeRecord} rows the resolver
 * needs to branch on direction and presentment mode.
 *
 * <p>config-registry is FROZEN, so the read contract is requested via an
 * INTEGRATION REQUEST; until it lands, the router runs against
 * {@link InMemoryPartnerSchemeRegistry}. The production adapter (a REST client
 * over config-registry, mirroring {@code RestPartnerSchemeResolver}) slots in
 * behind this same port without touching the resolver.
 *
 * <p>Contract: implementations return ENABLED rows only, never {@code null}
 * (empty list when nothing is wired); "what to do with empty" is the resolver's
 * policy, not the port's.
 */
public interface PartnerSchemeRegistry {

    /**
     * All enabled scheme rows for partners operating in the given country.
     *
     * @param countryCode ISO-3166 alpha-2 (any case; implementations normalise).
     * @return enabled rows in priority order; empty when the country is unwired.
     */
    List<PartnerSchemeRecord> schemesForCountry(String countryCode);
}
