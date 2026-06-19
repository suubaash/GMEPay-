package com.gme.pay.settlement.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Spring Data repository over the {@code outbox} table (Flyway V007). Oldest-first drain preserves order. */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    /** Oldest unpublished rows first; cap the batch via the {@link Pageable}. */
    @Query("select o from OutboxEntity o where o.publishedAt is null order by o.id asc")
    List<OutboxEntity> findUnpublished(Pageable pageable);

    /** Outbox lag: rows written but not yet acknowledged by the broker. */
    long countByPublishedAtIsNull();
}
