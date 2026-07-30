package com.gme.pay.ledger.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Wire representation of a posted double-entry {@link Journal}, returned by the two
 * {@code POST /v1/journals/*} endpoints ({@code /rounding-residual}, {@code /reversal}).
 *
 * <h2>Why this DTO exists (GAP T3-12 — the 406 defect)</h2>
 * Those endpoints used to return the <b>domain</b> {@link Journal} directly. {@code Journal} and
 * {@link LedgerEntry} are plain final classes with record-<i>style</i> accessors
 * ({@code journalId()}, {@code entries()}, {@code amount()}, …) rather than JavaBean getters, and
 * they are not Java records — so Jackson's bean introspection discovers <b>zero</b> properties.
 * With the default {@code FAIL_ON_EMPTY_BEANS}, {@code ObjectMapper.canSerialize(Journal.class)}
 * is {@code false}, which makes {@code MappingJackson2HttpMessageConverter.canWrite(...)} return
 * {@code false}. Spring MVC then finds no converter able to produce <i>any</i> representation of
 * the return value and raises {@code HttpMediaTypeNotAcceptableException} — surfacing to the
 * caller as <b>HTTP 406</b>, not the 500 one would expect from a serialization error, because the
 * failure happens during converter <i>selection</i>, before serialization is ever attempted.
 *
 * <p>The consequence in production was silent: {@code payment-executor}'s
 * {@code RestRevenueLedgerClient} swallows posting failures by design, so every rounding residual
 * 406'd and diverted into the {@code revenue_posting_failures} replay queue (T2-1) instead of
 * being journalled.
 *
 * <p>The fix is this explicit response DTO — a Java record, which Jackson introspects natively —
 * rather than loosening content negotiation or enabling {@code FAIL_ON_EMPTY_BEANS=false}
 * globally (which would have turned the 406 into a silently-empty {@code {}} body). It also keeps
 * the domain model off the wire, matching how every other endpoint in this service already works
 * ({@link RevenueCaptureResponse}, {@link JournalView}).
 *
 * <p>Wire shape (unchanged from what the endpoint contract always documented):
 * <pre>
 *   { "journalId": "…", "postedAt": "2026-07-28T00:00:00Z",
 *     "entries": [ { "account": "RECEIVABLE_PARTNER", "amount": "0.007",
 *                    "currency": "USD", "type": "DEBIT", "reference": "TXN-00001" }, … ] }
 * </pre>
 * Money fields serialize as decimal strings per {@code docs/MONEY_CONVENTION.md}.
 *
 * @param journalId the posted journal's id
 * @param postedAt  the journal head's post timestamp
 * @param entries   the balanced DR/CR lines
 */
public record JournalResponse(String journalId, Instant postedAt, List<Entry> entries) {

    /**
     * One DR/CR line. {@code type} is the stored side ({@code DEBIT}/{@code CREDIT}) verbatim;
     * {@code amount} is always the non-negative magnitude.
     */
    public record Entry(
            String account,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            String currency,
            String type,
            String reference) {

        static Entry from(LedgerEntry e) {
            return new Entry(e.account(), e.amount(), e.currency(), e.type().name(), e.reference());
        }
    }

    /** Map a domain journal onto the wire shape. */
    public static JournalResponse from(Journal journal) {
        return new JournalResponse(
                journal.journalId(),
                journal.postedAt(),
                journal.entries().stream().map(Entry::from).toList());
    }
}
