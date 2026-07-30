package com.gme.pay.settlement.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link CorridorReconSummaryEntity}.
 * Owned by settlement-reconciliation; no other service accesses this table.
 */
@Repository
public interface CorridorReconSummaryRepository extends JpaRepository<CorridorReconSummaryEntity, Long> {

    /** The single summary row for a scheme's settlement date (unique per V011). */
    Optional<CorridorReconSummaryEntity> findBySettlementDateAndScheme(LocalDate settlementDate, String scheme);

    /** A scheme's summaries in date order — the series the cumulative variance is recomputed over. */
    List<CorridorReconSummaryEntity> findBySchemeOrderBySettlementDateAsc(String scheme);

    /** A scheme's summaries over a date range, oldest first (finance's period view). */
    List<CorridorReconSummaryEntity> findBySchemeAndSettlementDateBetweenOrderBySettlementDateAsc(
            String scheme, LocalDate fromInclusive, LocalDate toInclusive);

    /**
     * EVERY scheme's summaries over a date range, deterministically ordered — the obligation source for
     * the multilateral netting report (T4-5). Cross-scheme by design: netting collapses opposing flows
     * against the same counterparty, so the report has to see all counterparties in one window.
     */
    List<CorridorReconSummaryEntity> findBySettlementDateBetweenOrderBySettlementDateAscSchemeAsc(
            LocalDate fromInclusive, LocalDate toInclusive);
}
