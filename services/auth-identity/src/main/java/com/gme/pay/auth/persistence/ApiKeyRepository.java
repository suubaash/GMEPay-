package com.gme.pay.auth.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository for {@link ApiKeyEntity} ({@code api_keys} table). */
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    Optional<ApiKeyEntity> findByApiKey(String apiKey);

    Optional<ApiKeyEntity> findByApiKeyAndStatus(String apiKey, ApiKeyEntity.Status status);

    List<ApiKeyEntity> findByPrincipalId(Long principalId);
}
