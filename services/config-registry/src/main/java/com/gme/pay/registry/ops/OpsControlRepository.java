package com.gme.pay.registry.ops;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for the singleton {@link OpsControlEntity}. The row (id = 1) always
 * exists (seeded by V038), so callers read it via {@code findById(SINGLETON_ID)}
 * and never insert.
 */
@Repository
public interface OpsControlRepository extends JpaRepository<OpsControlEntity, Integer> {
}
