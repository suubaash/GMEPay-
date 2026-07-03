package com.gme.pay.bff.web;

import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.web.dto.JournalPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Admin journal view BFF endpoint (admin-ui journal-lines page → revenue-ledger).
 *
 * <pre>
 *   GET /v1/admin/journals?from={ISO}&to={ISO}&reference={str}&page=0&size=50
 *     -> { "items":[ { journalId, reference, createdAt,
 *                      lines:[ { account, side, amount, currency } ] } ],
 *          "page":0, "size":50, "total":137 }
 * </pre>
 *
 * <p>Pure pass-through of revenue-ledger's {@code GET /v1/journals} — same shape, so the Admin UI
 * shows the DR/CR lines behind every money movement. All params optional; the upstream applies the
 * defaults (last 30 days, size cap 200). Read-only.
 */
@RestController
@RequestMapping("/v1/admin/journals")
public class AdminJournalsController {

    private final RevenueLedgerClient revenueLedger;

    public AdminJournalsController(RevenueLedgerClient revenueLedger) {
        this.revenueLedger = revenueLedger;
    }

    @GetMapping
    public JournalPage list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String reference,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return revenueLedger.listJournals(
                parseInstant(from), parseInstant(to), emptyToNull(reference), page, size);
    }

    /** Parse an ISO-8601 instant; null/blank/unparseable → null (upstream applies its default window). */
    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
