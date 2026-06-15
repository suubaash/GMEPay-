package com.gme.pay.reporting.kofiu;

/**
 * Port (secondary / outbound): reads per-corridor STR flag from
 * config-registry V029_1 ({@code partner_corridor.str_enabled}).
 *
 * <p>KoFIU STR (Suspicious Transaction Report) generation is gated by the
 * per-corridor flag. A corridor is identified by source-currency / destination-
 * currency combination (ISO-4217). If the flag is absent or the corridor is
 * unknown, STR defaults to disabled (false).
 */
public interface CorridorConfigPort {

    /**
     * Returns true if STR is enabled for the given corridor on the given partner.
     *
     * @param partnerId the partner's BIGINT surrogate
     * @param srcCcy    ISO-4217 source currency (e.g. "KRW")
     * @param dstCcy    ISO-4217 destination currency (e.g. "USD")
     * @return true when {@code str_enabled = TRUE} on the corridor row; false otherwise
     */
    boolean isStrEnabled(long partnerId, String srcCcy, String dstCcy);
}
