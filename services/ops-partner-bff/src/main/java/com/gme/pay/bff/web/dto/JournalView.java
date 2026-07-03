package com.gme.pay.bff.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * BFF view of one posted double-entry journal, passed through from revenue-ledger's
 * {@code GET /v1/journals} unchanged so the Admin UI binds one shape.
 *
 * <pre>
 *   { "journalId","reference","createdAt",
 *     "lines": [ { "account","side","amount","currency" } ] }
 * </pre>
 *
 * <p>{@code side} is the stored DR/CR indicator ("DEBIT"/"CREDIT"); {@code amount} rides as a
 * decimal string (money convention); {@code currency} is ISO-4217. Unknown upstream fields are
 * tolerated so an additive upstream change needs no code change here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JournalView(
        String journalId,
        String reference,
        Instant createdAt,
        List<Line> lines
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(
            String account,
            String side,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            String currency
    ) {}
}
