package com.gme.pay.reporting.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ReportFiling}. The natural-key lookup
 * ({@code lane + reportType + reportDate}) backs scheduler idempotency.
 */
public interface ReportFilingRepository extends JpaRepository<ReportFiling, Long> {

    Optional<ReportFiling> findByLaneAndReportTypeAndReportDate(
            String lane, String reportType, LocalDate reportDate);

    boolean existsByLaneAndReportTypeAndReportDate(
            String lane, String reportType, LocalDate reportDate);

    List<ReportFiling> findByReportDateOrderByIdAsc(LocalDate reportDate);
}
