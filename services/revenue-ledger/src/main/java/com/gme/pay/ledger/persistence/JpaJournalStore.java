package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.domain.ledger.JournalStore;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.outbox.OutboxWriter;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JPA-backed {@link JournalStore} — the primary implementation in production.
 *
 * <p>When {@link #save(Journal)} is called this writes one {@link JournalEntity}
 * row plus one {@link LedgerEntryEntity} row per ledger line. The whole operation
 * runs in a single transaction (via {@link Transactional}).
 *
 * <p><strong>Transactional Outbox.</strong> Inside the same {@code @Transactional} save,
 * an {@code outbox} row is enqueued via {@link OutboxWriter#enqueue(String, String, String)}
 * with eventType {@code "journal.posted"}. The row is committed atomically with the journal
 * itself; an async {@code OutboxPublisher} drains it and hands the event to
 * {@code EventPublisher}. This gives at-least-once delivery without 2PC.
 *
 * <p>Idempotency by {@code journalId}: a second {@code save} of an already-stored
 * journal returns the previously persisted journal unchanged (no duplicates) and
 * does NOT enqueue a second outbox row.
 *
 * <p>The companion {@link InMemoryJournalStore} is kept as a non-primary fallback bean.
 */
@Component
@Primary
public class JpaJournalStore implements JournalStore {

    private final JournalEntityRepository journals;
    private final LedgerEntryEntityRepository entries;
    private final RoundingResidualKeyRepository roundingKeys;
    private final OutboxWriter outboxWriter;

    public JpaJournalStore(JournalEntityRepository journals,
                           LedgerEntryEntityRepository entries,
                           RoundingResidualKeyRepository roundingKeys,
                           OutboxWriter outboxWriter) {
        this.journals = Objects.requireNonNull(journals, "journals repo required");
        this.entries = Objects.requireNonNull(entries, "entries repo required");
        this.roundingKeys = Objects.requireNonNull(roundingKeys, "roundingKeys repo required");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter required");
    }

    @Override
    @Transactional
    public Journal save(Journal journal) {
        Objects.requireNonNull(journal, "journal required");
        if (journals.existsById(journal.journalId())) {
            // Idempotent: do not overwrite an existing journal AND do not re-enqueue an outbox row.
            return journal;
        }

        // Derive a stable reference from the first entry (every line in a journal carries the same reference).
        String reference = journal.entries().isEmpty() ? null : journal.entries().get(0).reference();

        // Rounding-residual idempotency backstop: insert the guard key in the SAME transaction. A
        // concurrent double-post of the same reference (racing LedgerPostingService's pre-check) trips
        // the PK on rounding_residual_keys and rolls back, so the residual is booked exactly once.
        if (reference != null && isRoundingJournal(journal)) {
            roundingKeys.save(new RoundingResidualKeyEntity(reference, journal.journalId(), journal.postedAt()));
        }

        journals.save(new JournalEntity(journal.journalId(), reference, journal.postedAt()));

        for (LedgerEntry e : journal.entries()) {
            entries.save(new LedgerEntryEntity(
                    journal.journalId(),
                    e.account(),
                    e.amount(),
                    e.currency(),
                    e.type().name(),
                    e.reference()
            ));
        }

        // Enqueue the domain event in the SAME transaction (Outbox pattern).
        // Payload is a tiny hand-built JSON snippet — keep it dependency-free and conservative.
        outboxWriter.enqueue(
                journal.journalId(),
                "journal.posted",
                buildJournalPostedPayload(journal.journalId(), reference, journal.entries().size())
        );
        return journal;
    }

    /**
     * Build a minimal JSON payload for the {@code journal.posted} event. Kept as plain
     * string concatenation so revenue-ledger does not pull in Jackson just for this hop.
     * The values are simple identifiers and an integer — JSON-escape only the strings.
     */
    private static String buildJournalPostedPayload(String journalId, String reference, int entryCount) {
        return "{\"journalId\":\"" + escapeJson(journalId)
                + "\",\"reference\":" + (reference == null ? "null" : "\"" + escapeJson(reference) + "\"")
                + ",\"entryCount\":" + entryCount + "}";
    }

    /** Minimal JSON string escape for the handful of identifier characters we actually emit. */
    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Journal> findById(String journalId) {
        Optional<JournalEntity> head = journals.findById(journalId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        List<LedgerEntryEntity> lines = entries.findByJournalIdOrderByIdAsc(journalId);
        return Optional.of(rehydrate(head.get(), lines));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal sumRoundingByDateRange(LocalDate start, LocalDate end, String currency) {
        Objects.requireNonNull(start, "start required");
        Objects.requireNonNull(end, "end required");
        Objects.requireNonNull(currency, "currency required");
        // [start 00:00 UTC, end+1 00:00 UTC) — inclusive of both endpoint dates.
        Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal total = entries.sumSignedByAccountAndCurrencyAndPostedAtBetween(
                ChartOfAccounts.REVENUE_ROUNDING, currency, from, toExclusive);
        return total == null ? BigDecimal.ZERO : total;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Journal> findRoundingResidualByReference(String reference) {
        Objects.requireNonNull(reference, "reference required");
        return roundingKeys.findById(reference)
                .flatMap(k -> findById(k.getJournalId()));
    }

    /** True when this journal posts to {@code REVENUE_ROUNDING} — i.e. it is a rounding-residual journal. */
    private static boolean isRoundingJournal(Journal journal) {
        return journal.entries().stream()
                .anyMatch(e -> ChartOfAccounts.REVENUE_ROUNDING.equals(e.account()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Journal> findByReference(String reference) {
        List<JournalEntity> heads = journals.findByReferenceOrderByPostedAtAsc(reference);
        List<Journal> out = new ArrayList<>(heads.size());
        for (JournalEntity h : heads) {
            List<LedgerEntryEntity> lines = entries.findByJournalIdOrderByIdAsc(h.getJournalId());
            out.add(rehydrate(h, lines));
        }
        return out;
    }

    /**
     * Rebuild a domain {@link Journal} from a persisted {@link JournalEntity} + its entries.
     *
     * <p>Uses {@link Journal#rehydrate(String, Instant, List)} so the stored {@code journalId}
     * and {@code postedAt} are preserved on read-back (unlike {@link Journal#post(List)}, which
     * mints a fresh id + timestamp on every call).
     */
    private static Journal rehydrate(JournalEntity head, List<LedgerEntryEntity> lines) {
        List<LedgerEntry> domainEntries = new ArrayList<>(lines.size());
        for (LedgerEntryEntity le : lines) {
            domainEntries.add(new LedgerEntry(
                    le.getAccount(),
                    le.getAmount(),
                    le.getCurrency(),
                    EntryType.valueOf(le.getEntryType()),
                    le.getReference()
            ));
        }
        return Journal.rehydrate(head.getJournalId(), head.getPostedAt(), domainEntries);
    }
}
