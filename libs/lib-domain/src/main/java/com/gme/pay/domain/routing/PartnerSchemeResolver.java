package com.gme.pay.domain.routing;

import java.util.List;

/**
 * Port: resolves which QR scheme(s) a transaction may route over, sourced from
 * the {@code partner_scheme} registry (config-registry V022) rather than code.
 *
 * <p>Smart-router's {@code SchemeRouter} consumes this port; the production
 * adapter ({@code RestPartnerSchemeResolver}) reads config-registry over REST,
 * tests substitute an in-memory stub. Scheme ids are the V022 roster strings
 * (e.g. {@code "ZEROPAY"}, {@code "BAKONG"}).
 *
 * <p>Contract: implementations return the ENABLED schemes only, in priority
 * order (first element = preferred scheme), and an EMPTY list — never
 * {@code null} — when nothing is wired. "Nothing is wired" policy (throw vs
 * empty) belongs to the caller, except a partner code unknown to the registry,
 * which implementations may surface as their own not-found failure.
 */
public interface PartnerSchemeResolver {

    /**
     * The enabled scheme ids wired to one partner (per-partner override path).
     *
     * @param partnerCode the human-facing partner business code (e.g. {@code "GMEREMIT"}).
     * @return enabled scheme ids in priority order; empty when the partner has
     *         no enabled scheme rows.
     */
    List<String> resolveForPartner(String partnerCode);

    /**
     * The enabled scheme ids available in one country (merchant-location path).
     *
     * @param countryCode ISO-3166 alpha-2, uppercase (e.g. {@code "KR"}).
     * @return enabled scheme ids in priority order; empty when no partner
     *         operating in that country has an enabled scheme.
     */
    List<String> resolveForCountry(String countryCode);
}
