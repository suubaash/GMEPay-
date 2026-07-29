package com.gme.pay.scheme.ninepay.dto;

import java.util.List;

/**
 * Response for GET /scheme/balance — the partner-prefunded 9Pay balance.
 *
 * @param responseTime 9Pay response time (Y-m-d H:i:s, GMT+7)
 * @param available    usable balances per currency unit (may include USD entries even
 *                     though payouts are VND)
 */
public record BalanceResponse(String responseTime, List<Amount> available) {

    /** One available-balance entry. */
    public record Amount(String unit, Long value) {}
}
