package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.web.JournalPage;
import com.gme.pay.ledger.web.JournalView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side query for posted journals, backing {@code GET /v1/journals}.
 *
 * <p><b>Efficiency.</b> Pages only the journal HEADS ({@code journals} table) via a {@link Pageable}
 * sorted {@code posted_at} DESC (newest-first — {@code posted_at} is {@code NOT NULL}, so no id
 * fallback is needed). Then batch-loads the lines for exactly that page's ids in ONE query
 * ({@code findByJournalIdIn…}) — never loading all {@code ledger_entries} into memory.
 *
 * <p>The {@code side} field is the stored {@code entry_type} ("DEBIT"/"CREDIT") verbatim and
 * {@code currency} is the stored {@code currency} column — both faithful, no sign-based derivation.
 */
@Service
public class JournalQueryService {

    /** Hard cap on page size so an unbounded {@code size} can never load the whole table. */
    static final int MAX_SIZE = 200;
    static final int DEFAULT_SIZE = 50;

    private final JournalEntityRepository journals;
    private final LedgerEntryEntityRepository entries;

    public JournalQueryService(JournalEntityRepository journals, LedgerEntryEntityRepository entries) {
        this.journals = journals;
        this.entries = entries;
    }

    /**
     * List posted journals in {@code [from, to)} (end-exclusive), optionally filtered by
     * {@code reference}, newest-first, paged.
     *
     * @param from      inclusive lower bound (journal {@code posted_at})
     * @param to        exclusive upper bound
     * @param reference optional exact transaction reference; null/blank = no reference filter
     * @param page      zero-based page index (negatives coerced to 0)
     * @param size      requested page size (coerced to [1, {@value #MAX_SIZE}])
     */
    @Transactional(readOnly = true)
    public JournalPage list(Instant from, Instant to, String reference, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_SIZE, Math.max(1, size));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "postedAt"));

        Page<JournalEntity> heads = (reference == null || reference.isBlank())
                ? journals.findByPostedAtGreaterThanEqualAndPostedAtLessThan(from, to, pageable)
                : journals.findByReferenceAndPostedAtGreaterThanEqualAndPostedAtLessThan(
                        reference, from, to, pageable);

        List<String> ids = heads.getContent().stream().map(JournalEntity::getJournalId).toList();

        // Group this page's lines by journalId, preserving insertion order per journal.
        Map<String, List<JournalView.Line>> linesByJournal = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (LedgerEntryEntity e : entries.findByJournalIdInOrderByJournalIdAscIdAsc(ids)) {
                linesByJournal
                        .computeIfAbsent(e.getJournalId(), k -> new ArrayList<>())
                        .add(new JournalView.Line(e.getAccount(), e.getEntryType(), e.getAmount(), e.getCurrency()));
            }
        }

        List<JournalView> items = new ArrayList<>(heads.getNumberOfElements());
        for (JournalEntity h : heads.getContent()) {
            items.add(new JournalView(
                    h.getJournalId(),
                    h.getReference(),
                    h.getPostedAt(),
                    linesByJournal.getOrDefault(h.getJournalId(), List.of())
            ));
        }

        return new JournalPage(items, safePage, safeSize, heads.getTotalElements());
    }
}
