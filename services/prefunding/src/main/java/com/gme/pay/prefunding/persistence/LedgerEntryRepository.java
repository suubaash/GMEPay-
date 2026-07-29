package com.gme.pay.prefunding.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for the append-only {@link LedgerEntryEntity} ledger. */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, Long> {

    List<LedgerEntryEntity> findByPartnerIdOrderByCreatedAtAscIdAsc(String partnerId);

    long countByPartnerId(String partnerId);

    /** All ledger entries for one (partner, txnRef) — used to compute + guard a reversal. */
    List<LedgerEntryEntity> findByPartnerIdAndTxnRef(String partnerId, String txnRef);

    /**
     * Most-recent-first deduction history for a partner, bounded by {@code Pageable} (limit N).
     * Backs {@code GET /v1/prefunding/{code}/deductions}; ordered by created-at then id descending so
     * ties within the same instant are deterministic.
     */
    List<LedgerEntryEntity> findByPartnerIdAndEntryTypeOrderByCreatedAtDescIdDesc(
            String partnerId, String entryType, Pageable pageable);

    /**
     * Date-ranged, <b>paged</b> movement query backing
     * {@code GET /v1/prefunding/{code}/movements} (GAP T2-8).
     *
     * <p>The window is <b>half-open</b>: {@code createdAt >= from} (inclusive) and
     * {@code createdAt < to} (exclusive), so consecutive days tile the timeline exactly once — an
     * entry stamped at midnight belongs to the later day only, and no movement can be double-counted
     * or dropped by two adjacent reconciliation runs.
     *
     * <p>Returns a {@link Page} rather than a bounded {@link List} deliberately: the caller gets
     * {@code totalElements}, so a truncated read is impossible to mistake for a complete one. Callers
     * MUST supply a total order in the {@link Pageable} ({@code createdAt} then {@code id}) — without
     * the {@code id} tie-break, entries sharing an instant could repeat or vanish across page
     * boundaries. Served by {@code idx_ledger_entry_partner_created} (V002).
     */
    Page<LedgerEntryEntity> findByPartnerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            String partnerId, Instant from, Instant to, Pageable pageable);

    /**
     * As {@link #findByPartnerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan} but restricted to
     * the given {@code entryTypes} — e.g. the {@code DEBIT, CREDIT, CAPTURE} balance-moving subset a
     * float reconciliation cares about, excluding holds (RESERVE/RELEASE) and the AML counters
     * (CUM_CHARGE/CUM_REVERSE) which never touch the balance. Served by
     * {@code idx_ledger_entry_partner_type_created} (V009).
     */
    Page<LedgerEntryEntity> findByPartnerIdAndEntryTypeInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            String partnerId, Collection<String> entryTypes, Instant from, Instant to,
            Pageable pageable);
}
