package com.gme.pay.payment.dayclose;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Spring Data repository over {@code day_close_reports} (Flyway V009, gap T2-5). */
public interface DayCloseReportRepository extends JpaRepository<DayCloseReportEntity, Long> {

    /** One row per business date — the upsert identity. */
    Optional<DayCloseReportEntity> findByBusinessDate(LocalDate businessDate);

    /** Recent closes, newest first — the finance read pattern. */
    List<DayCloseReportEntity> findAllByOrderByBusinessDateDesc(Pageable pageable);
}
