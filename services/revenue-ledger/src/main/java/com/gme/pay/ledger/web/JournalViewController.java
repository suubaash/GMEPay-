package com.gme.pay.ledger.web;

import com.gme.pay.ledger.persistence.JournalQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * {@code GET /v1/journals} — list posted double-entry journals with their ledger lines, so the
 * Admin UI can show the DR/CR breakdown of every money movement. Read-only; posting lives on the
 * sibling {@link RoundingResidualController} (POST {@code /rounding-residual}, {@code /reversal})
 * under the same base path.
 *
 * <pre>
 *   GET /v1/journals?from={ISO-8601 instant}&to={ISO-8601 instant}&reference={str}&page=0&size=50
 *
 *   200 OK
 *   { "items": [ { "journalId","reference","createdAt",
 *                  "lines": [ { "account","side","amount","currency" } ] } ],
 *     "page": 0, "size": 50, "total": 137 }
 * </pre>
 *
 * <p>All params optional. Default window = last 30 days (to = now). {@code size} is capped at 200.
 * Results are newest-first by {@code createdAt} (= {@code journals.posted_at}).
 */
@RestController
@RequestMapping("/v1/journals")
public class JournalViewController {

    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final JournalQueryService queryService;

    public JournalViewController(JournalQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<JournalPage> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Instant toInstant = (to == null) ? Instant.now() : to;
        Instant fromInstant = (from == null) ? toInstant.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS) : from;

        return ResponseEntity.ok(queryService.list(fromInstant, toInstant, reference, page, size));
    }
}
