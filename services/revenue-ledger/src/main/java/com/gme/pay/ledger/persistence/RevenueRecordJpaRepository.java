package com.gme.pay.ledger.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link RevenueRecordEntity}. Backs
 * {@link JpaRevenueRecordStore}. Sum queries use {@code COALESCE(...,0)} so an empty range
 * returns zero rather than null. Date predicates use inclusive BETWEEN.
 */
public interface RevenueRecordJpaRepository extends JpaRepository<RevenueRecordEntity, Long> {

    Optional<RevenueRecordEntity> findByTxnRef(String txnRef);

    @Query("SELECT COALESCE(SUM(r.fxMarginUsd), 0) FROM RevenueRecordEntity r "
            + "WHERE r.partnerId = :partnerId AND r.revenueDate BETWEEN :start AND :end")
    BigDecimal sumFxMarginUsdByPartner(@Param("partnerId") long partnerId,
                                       @Param("start") LocalDate start,
                                       @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(r.serviceChargeAmount), 0) FROM RevenueRecordEntity r "
            + "WHERE r.partnerId = :partnerId AND r.revenueDate BETWEEN :start AND :end")
    BigDecimal sumServiceChargeByPartner(@Param("partnerId") long partnerId,
                                         @Param("start") LocalDate start,
                                         @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(r.serviceChargeAmount), 0) FROM RevenueRecordEntity r "
            + "WHERE r.schemeId = :schemeId AND r.revenueDate BETWEEN :start AND :end")
    BigDecimal sumServiceChargeByScheme(@Param("schemeId") long schemeId,
                                        @Param("start") LocalDate start,
                                        @Param("end") LocalDate end);

    long countByPartnerIdAndRevenueDateBetween(long partnerId, LocalDate start, LocalDate end);

    Optional<RevenueRecordEntity> findFirstByPartnerIdAndRevenueDateBetweenOrderByRevenueDateDesc(
            long partnerId, LocalDate start, LocalDate end);

    List<RevenueRecordEntity> findByRevenueDateBetweenOrderByRevenueDateAsc(
            LocalDate start, LocalDate end, Pageable pageable);
}
