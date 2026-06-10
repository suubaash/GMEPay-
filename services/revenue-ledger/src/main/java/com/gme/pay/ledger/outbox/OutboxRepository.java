package com.gme.pay.ledger.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Spring Data JPA repository for {@link OutboxEntity}.
 *
 * <p>{@link #findUnpublished(Pageable)} returns the oldest unpublished rows first (FIFO by id),
 * matching the order in which they were enqueued. The {@link Pageable} cap stops a backlog from
 * overwhelming a single publisher tick.
 */
public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    @Query("select o from OutboxEntity o where o.publishedAt is null order by o.id asc")
    List<OutboxEntity> findUnpublished(Pageable pageable);
}
