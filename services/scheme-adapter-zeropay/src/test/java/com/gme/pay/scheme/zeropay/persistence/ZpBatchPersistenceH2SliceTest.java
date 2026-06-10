package com.gme.pay.scheme.zeropay.persistence;

/**
 * Local unit slice: runs the persistence contract against the in-memory H2
 * (PostgreSQL-mode) datasource from {@code application.properties}. No Docker.
 *
 * <p>Test configuration ({@code @DataJpaTest} etc.) is inherited from
 * {@link AbstractZpBatchPersistenceContract}. Real-PostgreSQL acceptance coverage
 * is {@link ZpBatchPersistencePostgresIT} (Testcontainers postgres:16,
 * {@code @Tag("docker")}, CI {@code integrationTest}).</p>
 */
class ZpBatchPersistenceH2SliceTest extends AbstractZpBatchPersistenceContract {
}
