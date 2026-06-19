package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Spring Data repository for {@link ApprovalRequestEntity} ({@code approval_requests}). */
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, Long> {

    /** Pending queue, FIFO by insertion order (monotonic id — deterministic, unlike a wall-clock tie). */
    List<ApprovalRequestEntity> findByStatusOrderByIdAsc(String status);

    /**
     * Latest request for one operation, scoped to the tenant (platform-global rows have a NULL
     * tenant). Ordered by the strictly-monotonic surrogate id so "latest" is deterministic even when
     * two requests share a {@code requested_at} tick — the newest decides the grant state.
     */
    @Query("select r from ApprovalRequestEntity r "
            + "where r.requestType = :type and r.subjectRef = :subjectRef "
            + "and ((:tenantId is null and r.tenantId is null) or r.tenantId = :tenantId) "
            + "order by r.id desc")
    List<ApprovalRequestEntity> findLatestForOperation(@Param("type") String type,
                                                       @Param("subjectRef") String subjectRef,
                                                       @Param("tenantId") Long tenantId);
}
