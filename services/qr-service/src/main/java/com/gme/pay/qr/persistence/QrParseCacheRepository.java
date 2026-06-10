package com.gme.pay.qr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link QrParseCacheEntity}; PK is the SHA-256 payload hash. */
public interface QrParseCacheRepository extends JpaRepository<QrParseCacheEntity, String> {
}
