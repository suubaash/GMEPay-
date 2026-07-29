package com.gme.pay.ledger.domain.ledger;

import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Books a <b>reversing journal</b> that backs out the revenue captured for a transaction that has been
 * reversed (operator force-resolve to {@code REVERSED}, scheme reversal, timeout, …).
 *
 * <p><b>What it reverses.</b> The revenue-capture path posts one or more balanced journals against the
 * txnRef — the FX-margin + service-charge capture ({@code LedgerPostingService.postRevenueCapture}) and,
 * where applicable, the fee-share split ({@code postFeeShareSplit}). This service finds every such
 * <em>original</em> journal for the txnRef and posts a single balanced journal that mirrors all their
 * lines with the DEBIT/CREDIT side flipped. The net across capture + reversal is therefore exactly zero
 * on every account and in every currency — margin, service charge and fee-share are all backed out.
 *
 * <p><b>What it deliberately does NOT reverse (gap T2-9).</b> Lines belonging to a
 * <b>rounding-residual</b> journal ({@code REVENUE_ROUNDING}, posted by
 * {@code LedgerPostingService.postRoundingResidual}) are excluded from the mirror. Two reasons, and the
 * first one alone made every such reversal fail:
 * <ol>
 *   <li><b>It could not be stored.</b> A rounding residual is guarded by a {@code reference}-keyed row in
 *       {@code rounding_residual_keys} (Flyway V006). Mirroring a {@code REVENUE_ROUNDING} line made the
 *       reversing journal look like a second rounding journal for the same reference to
 *       {@code JpaJournalStore}, which then re-wrote / collided on that guard row. Reversing a
 *       transaction that also carried a rounding residual therefore failed (or, worse, silently
 *       re-pointed the residual guard at the reversal). See {@code JpaJournalStore.isRoundingJournal}.</li>
 *   <li><b>It is not captured revenue.</b> A residual is the rounding gain/loss between GMEPay+'s precise
 *       amount and the settlement liability a partner books — a settlement-booking artefact whose
 *       reference may even be a settlement BATCH id, not a txnRef. It has its own idempotent lifecycle and
 *       is not part of the FX-margin / service-charge / fee-share revenue this service is documented to
 *       back out.</li>
 * </ol>
 * Because a rounding journal is balanced on its own two lines, dropping <em>both</em> of them keeps the
 * reversing journal balanced. No existing posting's accounts or amounts change; only which lines a
 * <em>reversal</em> mirrors.
 *
 * <p><b>Idempotent on {@code txnRef}.</b> A repeat reversal is a no-op. A reversing line is the only
 * thing that ever posts a DEBIT to a {@code REVENUE_*} income account (capture always CREDITs them), so
 * a prior reversal is detected by any such contra line already present for the txnRef; on a hit
 * {@link #reverseCapture} returns {@link Optional#empty()} without posting a second contra. This keeps
 * the {@code payment.reversed} consumer safe under Kafka at-least-once redelivery.
 *
 * <p><b>No original capture.</b> When the txnRef has no capture journal to reverse, this is a safe no-op
 * ({@link Optional#empty()}) — a reversal that arrives before/without a capture is a benign ordering
 * artifact, not an error.
 *
 * <p>The reversing journal reuses the original capture's own accounts and carries the same {@code txnRef}
 * reference, so it nets to zero on those exact accounts and is discoverable via
 * {@link JournalStore#findByReference(String)}.
 */
@Service
public class RevenueReversalService {

    /** Income accounts credited on capture; a DEBIT to any of them marks a reversing line. */
    private static final String ACC_FX_MARGIN      = "REVENUE_FX_MARGIN";
    private static final String ACC_SERVICE_CHARGE = "REVENUE_SERVICE_CHARGE";
    private static final String ACC_GME_FEE_SHARE  = "REVENUE_GME_FEE_SHARE";

    /**
     * Rounding-residual account (T2-9). A journal touching it is a rounding journal with its own
     * {@code rounding_residual_keys} guard and its own idempotent lifecycle; it is NOT captured revenue,
     * and mirroring it broke the reversal outright. Excluded from the mirror — see the class javadoc.
     */
    private static final String ACC_ROUNDING       = "REVENUE_ROUNDING";

    private static final Logger log = LoggerFactory.getLogger(RevenueReversalService.class);

    private final JournalStore journalStore;

    public RevenueReversalService(JournalStore journalStore) {
        this.journalStore = Objects.requireNonNull(journalStore, "journalStore required");
    }

    /**
     * Post a balanced reversing journal that backs out all captured revenue for {@code txnRef}.
     *
     * @param txnRef the reversed transaction's reference
     * @return the posted reversing {@link Journal}, or {@link Optional#empty()} when there is nothing to
     *         reverse (no original capture) or it was already reversed (idempotent skip)
     */
    public Optional<Journal> reverseCapture(String txnRef) {
        Objects.requireNonNull(txnRef, "txnRef required");

        List<Journal> existing = journalStore.findByReference(txnRef);

        // Idempotency: a reversing line is the only DEBIT ever posted to a REVENUE_* income account
        // for a txnRef (capture only CREDITs them). If one is present, this txnRef was already reversed.
        boolean alreadyReversed = existing.stream()
                .flatMap(j -> j.entries().stream())
                .anyMatch(RevenueReversalService::isReversalEntry);
        if (alreadyReversed) {
            log.debug("capture already reversed, skipping: txnRef={}", txnRef);
            return Optional.empty();
        }

        // Collect every ORIGINAL (non-reversal) capture line for this txnRef, EXCLUDING whole
        // rounding-residual journals (T2-9). The exclusion is per-JOURNAL, not per-line, so both legs of a
        // residual (RECEIVABLE_PARTNER + REVENUE_ROUNDING) are dropped together and the mirror stays
        // balanced.
        List<LedgerEntry> originalLines = existing.stream()
                .filter(j -> !isRoundingJournal(j))
                .flatMap(j -> j.entries().stream())
                .filter(e -> txnRef.equals(e.reference()))
                .toList();

        if (originalLines.isEmpty()) {
            log.debug("no original capture to reverse: txnRef={}", txnRef);
            return Optional.empty();
        }

        // Mirror each original line with the side flipped, same account/amount/currency/reference. Because
        // we flip every line of a set of balanced journals, the reversing journal is itself balanced.
        List<LedgerEntry> reversalLines = new ArrayList<>(originalLines.size());
        for (LedgerEntry e : originalLines) {
            EntryType flipped = e.type() == EntryType.DEBIT ? EntryType.CREDIT : EntryType.DEBIT;
            reversalLines.add(new LedgerEntry(e.account(), e.amount(), e.currency(), flipped, txnRef));
        }

        Journal reversal = journalStore.save(Journal.post(reversalLines));
        log.info("reversing journal posted: txnRef={} journalId={} lines={}",
                txnRef, reversal.journalId(), reversalLines.size());
        return Optional.of(reversal);
    }

    /**
     * True when {@code journal} is a rounding-residual journal — i.e. any line posts to
     * {@code REVENUE_ROUNDING}. Same predicate {@code JpaJournalStore} uses to decide whether the
     * {@code rounding_residual_keys} guard applies, kept deliberately identical so the two cannot drift:
     * a journal this service refuses to mirror is exactly a journal the store would try to re-key.
     */
    private static boolean isRoundingJournal(Journal journal) {
        return journal.entries().stream().anyMatch(e -> ACC_ROUNDING.equals(e.account()));
    }

    /** True when {@code entry} is a reversing line: a DEBIT to a {@code REVENUE_*} income account. */
    private static boolean isReversalEntry(LedgerEntry entry) {
        if (entry.type() != EntryType.DEBIT) {
            return false;
        }
        String acc = entry.account();
        return ACC_FX_MARGIN.equals(acc) || ACC_SERVICE_CHARGE.equals(acc) || ACC_GME_FEE_SHARE.equals(acc);
    }
}
