package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for {@link ApprovalDecisionEntity} ({@code approval_decisions}). */
public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecisionEntity, Long> {

    /** All decisions for a request, in step order (the per-step approval trail). */
    List<ApprovalDecisionEntity> findByRequestIdOrderByStepIndexAsc(Long requestId);
}
