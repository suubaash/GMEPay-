package com.gme.pay.registry.changerequest;

import com.gme.pay.changerequest.ChangeRequestState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link ChangeRequestEntity} (V005).
 *
 * <p>Primary key is the {@code BIGINT} surrogate {@code id}, populated by the
 * {@code change_request_id_seq} default at the DB level and pulled by
 * {@link ChangeRequestService} before insert (same dual-engine pattern as
 * partners_id_seq in V003).
 */
@Repository
public interface ChangeRequestRepository extends JpaRepository<ChangeRequestEntity, Long> {

    /**
     * List every change_request touching the given aggregate row, newest first.
     * Used by the partner detail screen (Slice 1 onward) to show audit trail.
     */
    List<ChangeRequestEntity> findByAggregateTypeAndAggregateIdOrderByProposedAtDesc(
            String aggregateType, String aggregateId);

    /**
     * Approval queue: every change_request in a given state, oldest first
     * (FIFO so makers' work is reviewed in submission order).
     */
    List<ChangeRequestEntity> findByStateOrderByProposedAtAsc(ChangeRequestState state);
}
