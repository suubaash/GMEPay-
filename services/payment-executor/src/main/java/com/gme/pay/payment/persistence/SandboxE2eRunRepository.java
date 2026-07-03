package com.gme.pay.payment.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Spring Data repository over {@code sandbox_e2e_run} (Flyway V004, sandbox E2E runner). */
public interface SandboxE2eRunRepository extends JpaRepository<SandboxE2eRunEntity, Long> {

    /** Newest-first page of runs for the runs list (limit applied via {@link Pageable}). */
    List<SandboxE2eRunEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** Loads a run with its steps eagerly (fetch join) so the detail mapper never lazy-loads. */
    @Query("select distinct r from SandboxE2eRunEntity r left join fetch r.steps where r.id = :id")
    Optional<SandboxE2eRunEntity> findByIdWithSteps(@Param("id") long id);
}
